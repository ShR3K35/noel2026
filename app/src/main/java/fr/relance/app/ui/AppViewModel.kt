package fr.relance.app.ui

import android.app.Application
import android.os.SystemClock
import android.speech.tts.TextToSpeech
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
import fr.relance.app.core.Fmt
import fr.relance.app.core.Physio
import fr.relance.app.core.PlannedSession
import fr.relance.app.core.Profile
import fr.relance.app.core.ProgramStatus
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
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun today(): Long = LocalDate.now().toEpochDay()

    init {
        var loaded = store.load()
        loaded.program?.let { p -> loaded = loaded.copy(program = Coach.applyGap(p, today())) }
        commit(loaded)
        stack += if (loaded.profile == null) Screen.Onboarding else Screen.Home
        reconnectSensors()
        refreshHealthConnect()
        try {
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.FRANCE
                    ttsReady = true
                }
            }
        } catch (e: Exception) {
            // Pas de synthèse vocale sur cet appareil : le guidage reste visuel.
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
        stack.removeAt(stack.size - 1)
        return true
    }

    // ---------- Profil et programmes ----------

    fun completeOnboarding(profile: Profile, type: ProgramType, testFirst: Boolean) {
        val prof = profile.copy(level = Physio.assessLevel(profile))
        val program = Coach.newProgram(type, prof, today()).copy(testNext = testFirst)
        commit(state.copy(profile = prof, program = program))
        stack.clear()
        stack += Screen.Home
        tab = 0
    }

    fun updateProfile(change: (Profile) -> Profile) {
        val prof = state.profile ?: return
        commit(state.copy(profile = change(prof)))
    }

    fun startProgram(type: ProgramType) {
        val prof = state.profile ?: return
        if (state.program != null) return
        commit(state.copy(program = Coach.newProgram(type, prof, today())))
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

    fun startSession(capMinutes: Int = 0) {
        val p = state.program ?: return
        val prof = state.profile ?: return
        val session = Coach.sessionFor(p, prof, today(), capMinutes)
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
        var summary = e.summary(id, sessionStartMillis, today(), p)
        var newProfile = prof
        var newProgram = p
        var message = "Séance enregistrée."
        if (p != null) {
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

    // ---------- Voix ----------

    private fun announce(kind: BlockKind, seconds: Int, speed: Double, prefix: String) {
        val time = if (seconds % 60 == 0) "${seconds / 60} minute${if (seconds >= 120) "s" else ""}" else "$seconds secondes"
        val target = if (kind == BlockKind.TEST) "monte jusqu'à l'allure la plus vive où tu peux encore parler"
        else "à ${Fmt.kmh(speed)} kilomètres heure"
        say("$prefix${kind.label}, $time, $target.")
    }

    private fun say(text: String) {
        if (!ttsReady || state.profile?.voice == false) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "relance")
    }

    override fun onCleared() {
        tts?.shutdown()
        ble.shutdown()
    }
}
