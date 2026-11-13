package fr.relance.app.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.relance.app.ble.BleManager
import fr.relance.app.ble.FoundDevice
import fr.relance.app.core.AppState
import fr.relance.app.core.BlockKind
import fr.relance.app.core.Coach
import fr.relance.app.core.CompletedSession
import fr.relance.app.core.Export
import fr.relance.app.core.Feedback
import fr.relance.app.core.Level
import fr.relance.app.core.Fmt
import fr.relance.app.core.Physio
import fr.relance.app.core.PlannedSession
import fr.relance.app.core.Profile
import fr.relance.app.core.ProgramStatus
import fr.relance.app.core.Catalogs
import fr.relance.app.core.ProgramTemplate
import fr.relance.app.core.ProgramType
import fr.relance.app.core.Sample
import fr.relance.app.core.SessionEngine
import fr.relance.app.data.HcState
import fr.relance.app.data.HealthConnect
import fr.relance.app.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import kotlin.math.min

sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data object Catalogue : Screen
    data object Session : Screen
    data object Bilan : Screen
    data class Detail(val id: String) : Screen
    data object Sensors : Screen
    data object Calibration : Screen
    data object RestingHr : Screen
    data object ManualLog : Screen
    /** Séance du planning, par sa place dans la liste des séances à venir. */
    data class Planned(val index: Int) : Screen
    /** Mes allures : références, tests de calibration, historique. */
    data object Paces : Screen
    data object PacePrep : Screen
    data object PaceRun : Screen
    data object PaceResult : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app.filesDir)
    val ble = BleManager(app)
    val hc = HealthConnect(app)
    var hcState by mutableStateOf(HcState.UNAVAILABLE)
    var hcBusy by mutableStateOf(false)
        private set
    var hcMessage by mutableStateOf("")

    var state by mutableStateOf(AppState())
        private set
    var plan by mutableStateOf<List<PlannedSession>>(emptyList())
        private set
    val stack = mutableStateListOf<Screen>()
    var tab by mutableIntStateOf(0)

    var engine by mutableStateOf<SessionEngine?>(null)
        private set
    /** Incrémenté à chaque seconde de séance : les écrans le lisent pour se redessiner. */
    var tick by mutableIntStateOf(0)
        private set

    private var sessionStartMillis = 0L
    private var tickJob: Job? = null
    val speaker = fr.relance.app.voice.Speaker(app)

    /** Test de calibration des allures (voix locale, horloge monotone). */
    val pace = PaceController(
        app, speaker, persist = { persistPace(it) },
        voiceOn = { state.profile?.voice != false }, keepDiagnostics = { state.profile?.keepVoiceDiagnostics == true },
    )
    var paceRecord by mutableStateOf<fr.relance.app.core.CalibrationRecord?>(null)
        private set
    private var paceJob: Job? = null

    init {
        pace.onFinished = { showPaceResult() }
    }

    fun today(): Long = LocalDate.now().toEpochDay()

    val catalogSync = fr.relance.app.data.CatalogSync(app.filesDir)
    var catalogVersion by mutableIntStateOf(Catalogs.current.version)
        private set
    var catalogCheckedAt by mutableStateOf(0L)
        private set
    var catalogBusy by mutableStateOf(false)
        private set
    var catalogMessage by mutableStateOf("")

    init {
        // Le dernier catalogue publié par le coach, s'il a déjà été téléchargé ; sinon celui livré avec l'app.
        catalogSync.loadCached()?.let { if (it.version >= Catalogs.builtin.version) Catalogs.current = it }
        catalogVersion = Catalogs.current.version
        catalogCheckedAt = catalogSync.meta().checkedAt
        var loaded = store.load()
        // Disponibilités proposées : 30 min à 2 h. Un ancien profil à 15 ou 20 min passe à 30 min.
        loaded.profile?.let { if (it.minutesPerSession < 30) loaded = loaded.copy(profile = it.copy(minutesPerSession = 30)) }
        loaded.program?.let { p ->
            val prof = loaded.profile
            val migrated = if (prof != null) Coach.restoreDistances(Coach.migrate(p, prof), prof) else p
            loaded = loaded.copy(program = Coach.applyGap(migrated, today()))
        }
        commit(loaded)
        stack += if (loaded.profile == null) Screen.Onboarding else Screen.Home
        // Test de calibration interrompu (appli fermée, téléphone redémarré) : affiché en pause, jamais repris tout seul.
        loaded.paceTest?.let { t ->
            if (loaded.profile != null) {
                pace.restore(t)
                stack += Screen.PaceRun
                startPaceLoop()
            }
        }
        reconnectSensors()
        refreshHealthConnect()
        try {
            fr.relance.app.data.CatalogSync.schedule(app)
        } catch (e: Exception) {
            // WorkManager indisponible (tests) : la mise à jour manuelle reste possible.
        }
    }

    private fun commit(s: AppState) {
        state = s
        val p = s.program
        val prof = s.profile
        plan = if (p != null && prof != null) Coach.plan(p, prof, today()) else emptyList()
        viewModelScope.launch(Dispatchers.IO) { store.save(s) }
    }

    // ---------- Navigation ----------

    fun open(screen: Screen) {
        stack += screen
    }

    /** Renvoie false quand il n'y a plus rien à dépiler : le système ferme alors l'app. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        if (stack.last() == Screen.Session || stack.last() == Screen.Bilan) return true
        if (stack.last() == Screen.PaceRun || stack.last() == Screen.PaceResult) return true
        stack.removeAt(stack.size - 1)
        return true
    }

    // ---------- Profil et programmes ----------

    fun completeOnboarding(profile: Profile, type: ProgramType, testFirst: Boolean) =
        completeOnboarding(profile, Catalogs.current.program(type.id) ?: Catalogs.builtin.program(type.id)!!, testFirst)

    fun completeOnboarding(profile: Profile, template: ProgramTemplate, testFirst: Boolean, level: Level? = null) {
        val prof = profile.copy(level = Physio.assessLevel(profile))
        val program = Coach.newProgram(template, prof, today(), level = level ?: Coach.defaultLevel(template, prof)).copy(testNext = testFirst)
        commit(state.copy(profile = prof, program = program))
        stack.clear()
        stack += Screen.Home
        tab = 0
    }

    fun updateProfile(change: (Profile) -> Profile) {
        val prof = state.profile ?: return
        commit(state.copy(profile = change(prof)))
    }

    fun startProgram(template: ProgramTemplate, level: Level? = null) {
        val prof = state.profile ?: return
        if (state.program != null) return
        commit(state.copy(program = Coach.newProgram(template, prof, today(), level = level ?: Coach.defaultLevel(template, prof))))
        stack.clear()
        stack += Screen.Home
        tab = 0
    }

    fun stopProgram() {
        val p = state.program ?: return
        val prof = state.profile ?: return
        commit(state.copy(program = null, archived = listOf(Coach.stop(p, prof, today())) + state.archived))
    }

    fun togglePauseProgram() {
        val p = state.program ?: return
        val resumed = if (p.paused) Coach.applyGap(p.copy(paused = false), today()) else p.copy(paused = true)
        commit(state.copy(program = resumed))
    }

    /** « Reporter » : la séance glisse au prochain jour disponible, sans rattrapage. */
    fun postpone() {
        val p = state.program ?: return
        commit(state.copy(program = p.copy(restUntilDay = today() + 1)))
    }

    // ---------- Séance ----------

    /** Test de marche lancé depuis le profil, hors programme : il ne sert qu'à recalculer la vitesse de référence. */
    var standaloneTest by mutableStateOf(false)
        private set

    fun startSession(capMinutes: Int = 0) {
        val p = state.program ?: return
        val prof = state.profile ?: return
        standaloneTest = false
        launchSession(Coach.sessionFor(p, prof, today(), capMinutes), prof)
    }

    /** Relance le test de marche (12 min) pour mesurer à nouveau la vitesse de référence. */
    fun startTestSession() {
        val prof = state.profile ?: return
        val c = Catalogs.current
        val type = c.type(c.specials.test) ?: Catalogs.builtin.type(Catalogs.builtin.specials.test) ?: return
        val session = fr.relance.app.core.Templates.build(type, emptyMap(), prof, Physio.speedsFor(prof, 1.0, c.paces), today(), 0, false, false, 0)
        standaloneTest = true
        launchSession(session, prof)
    }

    /** Vitesse de référence saisie à la main, entre 3 et 9 km/h. */
    fun setReferenceSpeed(kmh: Double) {
        updateProfile { it.copy(vr = Physio.round1(kmh.coerceIn(3.0, 9.0))) }
    }

    private fun launchSession(session: fr.relance.app.core.PlannedSession, prof: Profile) {
        notice = ""
        engine = SessionEngine(session, prof)
        sessionStartMillis = System.currentTimeMillis()
        tick = 0
        stack += Screen.Session
        announce(session.blocks.first().kind, session.blocks.first().seconds, session.blocks.first().speedKmh, "C'est parti. ")
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            var post = 0
            while (isActive) {
                delay(250)
                val e = engine ?: break
                val steps = ((SystemClock.elapsedRealtime() - last) / 1000).toInt()
                if (steps <= 0) continue
                last += steps * 1000L
                repeat(min(steps, 300)) {
                    if (e.finished) {
                        if (post++ < 90) e.recordPostHr(ble.hr.value ?: 0)
                    } else if (e.tick(ble.speed.value, ble.hr.value, ble.incline.value)) {
                        if (e.finished) say("Séance terminée. Bravo.")
                        else announce(e.current.kind, e.current.seconds, e.current.speedKmh, "")
                    }
                }
                tick++
                if (e.finished && stack.lastOrNull() == Screen.Session) {
                    stack.removeAt(stack.size - 1)
                    stack += Screen.Bilan
                }
            }
        }
    }

    fun togglePause() {
        val e = engine ?: return
        e.paused = !e.paused
        tick++
    }

    fun easeOff() {
        engine?.easeOff()
        say("D'accord, on allège la suite.")
        tick++
    }

    fun endSession() {
        val e = engine ?: return
        e.stop()
        if (stack.lastOrNull() == Screen.Session) stack.removeAt(stack.size - 1)
        // Moins d'une minute : rien à enregistrer.
        if (e.elapsed < 60) discardSession() else stack += Screen.Bilan
    }

    fun discardSession() {
        tickJob?.cancel()
        engine = null
        while (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    fun submitFeedback(fb: Feedback) {
        val e = engine ?: return
        val p = state.program
        val prof = state.profile ?: return
        tickJob?.cancel()
        val id = "s${sessionStartMillis}"
        val standalone = standaloneTest
        standaloneTest = false
        var summary = if (standalone) e.summary(id, sessionStartMillis, today(), null)
        else e.summary(id, sessionStartMillis, today(), p).copy(programBefore = p, profileBefore = fr.relance.app.core.CoachSnapshot.of(prof))
        var newProfile = prof
        var newProgram = p
        var message = "Séance enregistrée."
        if (standalone) {
            // Test hors programme : seule la vitesse de référence change, le programme ne bouge pas.
            val v = e.measuredTestSpeed().takeIf { it >= 3.0 } ?: fb.testSpeed
            if (v >= 3.0) {
                val old = prof.vr
                newProfile = prof.copy(vr = Physio.round1(v), testRpe = fb.rpe)
                message = when {
                    newProfile.vr > old + 0.05 -> "Vitesse de référence : ${Fmt.kmh(old)} → ${Fmt.kmh(newProfile.vr)} km/h. Tes allures sont recalculées pour les prochaines séances."
                    newProfile.vr < old - 0.05 -> "Vitesse de référence ajustée à ${Fmt.kmh(newProfile.vr)} km/h. Tes allures sont recalculées, sans jugement : on part d'où tu es."
                    else -> "Vitesse de référence stable à ${Fmt.kmh(newProfile.vr)} km/h."
                }
            } else message = "Test enregistré, sans vitesse : ta vitesse de référence n'a pas changé."
        } else if (p != null) {
            val r = Coach.applyFeedback(p, prof, e.session, fb, today(), e.measuredTestSpeed(), summary.longestRunMin)
            newProfile = r.profile
            newProgram = r.program
            message = r.message
        }
        if (summary.maxHr > Physio.maxHr(newProfile)) newProfile = newProfile.copy(maxHrObserved = summary.maxHr)
        summary = summary.copy(rpe = fb.rpe, pain = fb.pain, completed = fb.completed, note = fb.note, coachMessage = message)
        // Écrit tout de suite : la fiche de séance, ouverte juste après, relit ces mesures.
        store.saveSamples(id, e.samples.toList())
        val ended = newProgram != null && newProgram.status != ProgramStatus.ACTIVE
        commit(
            state.copy(
                profile = newProfile,
                program = if (ended) null else newProgram,
                archived = if (ended && newProgram != null) listOf(newProgram) + state.archived else state.archived,
                sessions = listOf(summary) + state.sessions,
            )
        )
        engine = null
        while (stack.size > 1) stack.removeAt(stack.size - 1)
        tab = 2
        stack += Screen.Detail(id)
        if (newProfile.healthConnect) syncHealthConnect(listOf(id))
    }

    // ---------- Annulation ----------

    /** Message affiché une fois sur l'écran Aujourd'hui ou l'historique, après une annulation. */
    var notice by mutableStateOf("")

    fun canRollback(id: String): Boolean = session(id)?.let { fr.relance.app.core.History.canRollback(state, it) } ?: false

    fun cancelSession(id: String) {
        val s = session(id) ?: return
        val (next, effect) = fr.relance.app.core.History.cancel(state, id) ?: return
        commit(next)
        viewModelScope.launch(Dispatchers.IO) {
            store.deleteSamples(id)
            if (s.healthConnectSynced) try { hc.delete(s) } catch (e: Exception) { /* Santé Connect indisponible : la séance y reste. */ }
        }
        while (stack.size > 1) stack.removeAt(stack.size - 1)
        if (effect == fr.relance.app.core.History.Effect.ROLLED_BACK) {
            notice = "Séance annulée : « ${s.title} » est de nouveau la prochaine séance du planning."
            tab = 0
        } else {
            notice = "Séance retirée de l'historique. Le programme n'a pas changé."
            tab = 2
        }
    }

    // ---------- Séance faite sans le téléphone ----------

    /** Jours où l'on peut déclarer la séance : de la dernière séance enregistrée (ou du début du programme) à aujourd'hui, 7 jours au plus. */
    fun manualDays(): List<Long> {
        val p = state.program ?: return emptyList()
        val from = maxOf(p.startDay, p.lastSessionDay, today() - 6)
        return (today() downTo from).toList()
    }

    fun plannedFor(day: Long): PlannedSession? {
        val p = state.program ?: return null
        val prof = state.profile ?: return null
        return Coach.sessionFor(p, prof, day)
    }

    fun logManual(day: Long, minuteOfDay: Int, durationMin: Int, distanceM: Double?, fb: Feedback) {
        val p = state.program ?: return
        val prof = state.profile ?: return
        val planned = Coach.sessionFor(p, prof, day)
        val start = java.time.LocalDate.ofEpochDay(day).atStartOfDay(java.time.ZoneId.systemDefault())
            .plusMinutes(minuteOfDay.toLong()).toInstant().toEpochMilli()
        val id = "m$start"
        var summary = fr.relance.app.core.ManualEntry.summary(planned, prof, p, id, day, start, durationMin * 60, distanceM, fb.completed)
            .copy(programBefore = p, profileBefore = fr.relance.app.core.CoachSnapshot.of(prof))
        val r = Coach.applyFeedback(p, prof, planned, fb, day, 0.0, summary.longestRunMin)
        summary = summary.copy(rpe = fb.rpe, pain = fb.pain, completed = fb.completed, note = fb.note, coachMessage = r.message)
        val ended = r.program.status != ProgramStatus.ACTIVE
        commit(
            state.copy(
                profile = r.profile,
                program = if (ended) null else r.program,
                archived = if (ended) listOf(r.program) + state.archived else state.archived,
                sessions = (listOf(summary) + state.sessions).sortedByDescending { it.startMillis },
            )
        )
        while (stack.size > 1) stack.removeAt(stack.size - 1)
        tab = 2
        stack += Screen.Detail(id)
        if (r.profile.healthConnect) syncHealthConnect(listOf(id))
    }

    // ---------- Catalogue des programmes ----------

    /** Bouton « Mettre à jour les programmes ». */
    fun syncCatalog() {
        if (catalogBusy) return
        catalogBusy = true
        catalogMessage = ""
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { catalogSync.fetch() }
            when (r) {
                is fr.relance.app.data.CatalogSync.Result.Updated -> {
                    Catalogs.current = r.catalog
                    catalogMessage = "Programmes mis à jour (version ${r.catalog.version})."
                }
                is fr.relance.app.data.CatalogSync.Result.UpToDate -> catalogMessage = "Les programmes sont à jour."
                is fr.relance.app.data.CatalogSync.Result.Failed -> catalogMessage = r.message
            }
            catalogVersion = Catalogs.current.version
            catalogCheckedAt = catalogSync.meta().checkedAt
            catalogBusy = false
            commit(state)
        }
    }

    fun programUpdateAvailable(): Boolean = state.program?.let { Coach.hasUpdate(it) } ?: false

    fun applyProgramUpdate() {
        val p = state.program ?: return
        val prof = state.profile ?: return
        val next = Coach.scheduleUpdate(p, prof)
        commit(state.copy(program = next))
        catalogMessage = if (next.pending != null) "La nouvelle version s'appliquera au début de la semaine prochaine."
        else "Nouvelle version du programme appliquée."
    }

    // ---------- Santé Connect ----------

    fun refreshHealthConnect() {
        viewModelScope.launch { hcState = hc.state() }
    }

    /** Après la fenêtre d'autorisation de Santé Connect. */
    fun onHealthPermissions(granted: Set<String>) {
        viewModelScope.launch {
            hcState = hc.state()
            if (granted.isEmpty()) {
                hcMessage = "Aucune autorisation accordée : rien ne sera envoyé."
                return@launch
            }
            updateProfile { it.copy(healthConnect = true) }
            syncHealthConnect(null)
        }
    }

    fun setHealthConnectAuto(on: Boolean) {
        updateProfile { it.copy(healthConnect = on) }
        if (on) syncHealthConnect(null)
    }

    fun unsyncedCount(): Int = state.sessions.count { !it.healthConnectSynced }

    /** Envoie les séances données, ou toutes celles pas encore envoyées si `ids` est null. */
    fun syncHealthConnect(ids: List<String>?) {
        if (hcBusy) return
        val weight = state.profile?.weightKg ?: return
        val targets = state.sessions.filter { if (ids != null) it.id in ids else !it.healthConnectSynced }
        if (targets.isEmpty()) {
            hcMessage = "Toutes les séances sont déjà dans Santé Connect."
            return
        }
        hcBusy = true
        viewModelScope.launch {
            val done = HashSet<String>()
            var error: String? = null
            withContext(Dispatchers.IO) {
                for (s in targets) {
                    try {
                        if (hc.write(s, store.loadSamples(s.id), weight) > 0) done += s.id
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                        break
                    }
                }
            }
            if (done.isNotEmpty()) {
                commit(state.copy(sessions = state.sessions.map { if (it.id in done) it.copy(healthConnectSynced = true) else it }))
            }
            hcState = hc.state()
            hcMessage = when {
                error != null -> "Envoi interrompu (${done.size} séance(s) envoyée(s)) : $error"
                done.isEmpty() -> "Rien n'a été envoyé : vérifie les autorisations dans Santé Connect."
                done.size == 1 -> "Séance envoyée vers Santé Connect."
                else -> "${done.size} séances envoyées vers Santé Connect."
            }
            hcBusy = false
        }
    }

    // ---------- Historique et export ----------

    fun session(id: String): CompletedSession? = state.sessions.firstOrNull { it.id == id }

    fun samples(id: String): List<Sample> = store.loadSamples(id)

    fun exportTcx(id: String): String = session(id)?.let { Export.tcx(it, samples(id)) } ?: ""

    fun exportCsv(id: String): String = Export.csv(samples(id))

    // ---------- Capteurs ----------

    /** Connecte la ceinture si elle est associée et pas déjà connectée ou en cours de connexion. */
    fun ensureHeartRate() {
        val prof = state.profile ?: return
        if (prof.hrAddress.isBlank() || !ble.available()) return
        if (ble.hrLink.value == fr.relance.app.ble.Link.OFF) ble.connectHeartRate(prof.hrAddress)
    }

    fun reconnectSensors() {
        val prof = state.profile ?: return
        if (!ble.available()) return
        if (prof.hrAddress.isNotBlank()) ble.connectHeartRate(prof.hrAddress)
        if (prof.treadmillAddress.isNotBlank()) ble.connectTreadmill(prof.treadmillAddress)
    }

    fun pair(d: FoundDevice) {
        if (d.isTreadmill) {
            updateProfile { it.copy(treadmillAddress = d.address, treadmillName = d.name) }
            ble.connectTreadmill(d.address)
        } else {
            updateProfile { it.copy(hrAddress = d.address, hrName = d.name) }
            ble.connectHeartRate(d.address)
        }
    }

    fun forgetHeartRate() {
        ble.disconnectHeartRate()
        updateProfile { it.copy(hrAddress = "", hrName = "") }
    }

    fun forgetTreadmill() {
        ble.disconnectTreadmill()
        updateProfile { it.copy(treadmillAddress = "", treadmillName = "") }
    }

    // ---------- Calibration des allures ----------

    /** Sauvegarde de l'état du test, sans recalculer le planning. */
    private fun persistPace(t: fr.relance.app.core.PaceTestState?) {
        state = state.copy(paceTest = t)
        val s = state
        viewModelScope.launch(Dispatchers.IO) { store.save(s) }
    }

    fun beginPaceTest(kind: fr.relance.app.core.TestKind, easyKmh: Double, candidateKmh: Double, usualActiveKmh: Double) {
        val prof = state.profile ?: return
        if (pace.engine != null) return
        val st = fr.relance.app.core.PaceTestState(
            "c${System.currentTimeMillis()}", kind, today(), fr.relance.app.core.Treadmill.spec(prof),
            easyKmh, candidateKmh, usualActiveKmh,
        )
        pace.begin(st, easyKmh)
        stack.remove(Screen.PacePrep)
        stack += Screen.PaceRun
        startPaceLoop()
    }

    private fun startPaceLoop() {
        paceJob?.cancel()
        paceJob = viewModelScope.launch {
            while (isActive) {
                val e = pace.engine ?: break
                if (e.s.phase == fr.relance.app.core.Phase.FINISHED) {
                    showPaceResult(); break
                }
                pace.tick()
                delay(250)
            }
        }
    }

    private fun showPaceResult() {
        if (paceRecord != null && stack.lastOrNull() == Screen.PaceResult) return
        paceJob?.cancel()
        paceRecord = pace.result()
        stack.remove(Screen.PaceRun)
        if (stack.lastOrNull() != Screen.PaceResult) stack += Screen.PaceResult
    }

    /**
     * Enregistre le résultat dans l'historique et, si demandé, applique les références au profil : le planning à venir
     * est recalculé d'un seul coup ; les séances déjà faites gardent leurs vitesses.
     */
    fun savePaceResult(apply: Boolean) {
        val r = paceRecord ?: return
        val prof = state.profile ?: return
        val (refs, kept) = if (apply) prof.paceRefs.merge(r.refs) else prof.paceRefs to r.refs
        val applied = r.refs.filter { it !in kept }.map { it.kind }
        commit(state.copy(profile = prof.copy(paceRefs = refs), calibrations = listOf(r.copy(applied = applied)) + state.calibrations, paceTest = null))
        paceRecord = null
        pace.close()
        stack.remove(Screen.PaceResult)
        if (Screen.Paces !in stack) stack += Screen.Paces
        while (stack.size > 1 && stack.last() != Screen.Paces) stack.removeAt(stack.size - 1)
    }

    /** Saisie manuelle d'une référence : identifiable comme telle, jamais « confirmée ». */
    fun setManualRef(kind: fr.relance.app.core.RefKind, kmh: Double) {
        updateProfile { p ->
            val v = fr.relance.app.core.Treadmill.fit(kmh, fr.relance.app.core.Treadmill.spec(p))
            p.copy(paceRefs = p.paceRefs.with(fr.relance.app.core.PaceRef(kind, v, 0.0, today(), "manuel", fr.relance.app.core.PaceProtocol.VERSION, fr.relance.app.core.RefSource.MANUAL, fr.relance.app.core.RefStatus.PROVISIONAL, 0)))
        }
    }

    fun clearRef(kind: fr.relance.app.core.RefKind) = updateProfile { it.copy(paceRefs = it.paceRefs.without(kind)) }

    /** L'app passe en arrière-plan (appel, autre app) : un test en cours se met en pause. */
    fun onBackground() {
        val ph = pace.engine?.s?.phase ?: return
        if (ph == fr.relance.app.core.Phase.RUNNING || ph == fr.relance.app.core.Phase.AWAIT_SPEED) pace.pause(fr.relance.app.core.PauseReason.BACKGROUND)
    }

    fun setVoiceDiagnostics(on: Boolean) {
        updateProfile { it.copy(keepVoiceDiagnostics = on) }
        if (!on) pace.diagnostics.clear()
    }

    // ---------- Voix ----------

    private fun announce(kind: BlockKind, seconds: Int, speed: Double, prefix: String) {
        val time = if (seconds % 60 == 0) "${seconds / 60} minute${if (seconds >= 120) "s" else ""}" else "$seconds secondes"
        val target = if (kind == BlockKind.TEST) "monte jusqu'à l'allure la plus vive où tu peux encore parler"
        else "à ${Fmt.kmh(speed)} kilomètres heure"
        say("$prefix${kind.label}, $time, $target.")
    }

    private fun say(text: String) {
        if (state.profile?.voice == false) return
        speaker.say(text)
    }

    override fun onCleared() {
        speaker.shutdown()
        pace.shutdown()
        ble.shutdown()
    }
}
