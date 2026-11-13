package fr.relance.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.relance.app.core.CalStatus
import fr.relance.app.core.CalibrationRecord
import fr.relance.app.core.Capacity
import fr.relance.app.core.Fmt
import fr.relance.app.core.PaceProtocol
import fr.relance.app.core.PaceRef
import fr.relance.app.core.PaceRefs
import fr.relance.app.core.PaceTests
import fr.relance.app.core.PauseReason
import fr.relance.app.core.Phase
import fr.relance.app.core.Profile
import fr.relance.app.core.RefKind
import fr.relance.app.core.RefStatus
import fr.relance.app.core.SegKind
import fr.relance.app.core.Speech
import fr.relance.app.core.Symptom
import fr.relance.app.core.TestKind
import fr.relance.app.core.Treadmill
import fr.relance.app.core.Verdicts
import fr.relance.app.voice.MicState

private val TestKind.description: String
    get() = when (this) {
        TestKind.WALK_STEPS -> "5 min de marche facile, jusqu'à 3 paliers de 3 min (+0,3 km/h au plus), 3 min de retour au calme. Pour trouver ta marche facile et ta marche active."
        TestKind.CONFIRM_WALK -> "5 min d'échauffement, 10 min à une seule vitesse de marche active, 3 min de retour au calme. Confirme une marche active déjà repérée."
        TestKind.CONFIRM_RUN -> "5 min d'échauffement, 10 min de trot à une seule vitesse, 3 min de retour au calme. Pour qui court déjà 10 min à l'aise."
        TestKind.WALK_RUN -> "5 min de marche, 3 fois 1 min de trot lent avec 2 min de marche entre, 3 min de retour au calme. Pour qui alterne marche et trot."
    }

private fun refUse(k: RefKind) = when (k) {
    RefKind.WALK_EASY -> "Échauffements, récupérations et retours au calme."
    RefKind.WALK_ACTIVE -> "Séances de marche active."
    RefKind.RUN_EASY -> "Trot facile, blocs de course, durée des séances à objectif de distance."
}

private fun mmss(sec: Int) = Fmt.clock(sec.coerceAtLeast(0))

// ---------- Mes allures ----------

@Composable
fun PacesScreen(vm: AppViewModel) {
    val profile = vm.state.profile ?: return
    val refs = profile.paceRefs
    Column(Modifier.fillMaxSize().background(C.Ground).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Btn("‹ Profil", filled = false, outline = Color.Transparent, onOutline = C.Accent, height = 48.dp) { vm.back() }
            Title("Mes allures")
            Body("Des repères de séance, mesurés pendant des tests dédiés et associés à une durée observée. Ce ne sont ni une VMA, ni une VO2max, ni un seuil.", C.Muted)
            for (k in RefKind.values()) RefCard(vm, profile, k, refs[k])
            Card(color = C.Soft) {
                Strong("Côte, allure soutenue et rapide")
                Small("Elles demandent d'autres informations : elles gardent les réglages du programme (côte depuis ta vitesse de référence de ${Fmt.kmh(profile.vr)} km/h, soutenue et rapide depuis le trot). Aucun test maximal n'est proposé.")
            }
            Btn("Faire un test de calibration", Modifier.fillMaxWidth(), enabled = vm.engine == null) { vm.open(Screen.PacePrep) }
            Small("Conseillé pour toi : ${PaceTests.recommend(profile).label}. Les références ne changent qu'à la fin d'un test dédié, jamais pendant les séances ordinaires.")
            if (vm.state.calibrations.isNotEmpty()) {
                Label("Tests passés")
                vm.state.calibrations.take(10).forEach { HistoryCard(it) }
            }
            Label("Voix")
            Card {
                Strong("Enregistrements de diagnostic")
                ChipRow(listOf("Effacés après analyse", "Conservés"), if (profile.keepVoiceDiagnostics) 1 else 0) { vm.setVoiceDiagnostics(it == 1) }
                Small(
                    "Par défaut, l'audio des lectures reste en mémoire le temps de l'analyse, puis il est effacé ; seuls les résultats sont gardés." +
                        if (profile.keepVoiceDiagnostics) " Enregistrements conservés sur le téléphone : ${vm.pace.diagnostics.count()}. Repasser sur « Effacés » les supprime." else ""
                )
            }
        }
    }
}

@Composable
private fun RefCard(vm: AppViewModel, profile: Profile, k: RefKind, r: PaceRef?) {
    var editing by remember { mutableStateOf(false) }
    var value by remember { mutableDoubleStateOf(r?.kmh ?: if (k == RefKind.RUN_EASY) 6.5 else if (k == RefKind.WALK_EASY) 3.5 else 5.0) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Strong(k.label, modifier = Modifier.weight(1f))
            Text(r?.let { "${Fmt.kmh(it.kmh)} km/h" } ?: "non mesurée", color = if (r != null) C.Accent else C.Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (r != null) {
            Small(
                when (r.source) {
                    fr.relance.app.core.RefSource.MANUAL -> "Saisie manuelle le ${Dates.date(r.day).let { "${it.dayOfMonth}/${it.monthValue}" }}. Non confirmée par un test."
                    fr.relance.app.core.RefSource.LEGACY -> "Reprise de l'ancienne vitesse de référence."
                    fr.relance.app.core.RefSource.TEST -> "Test du ${Dates.date(r.day).let { "${it.dayOfMonth}/${it.monthValue}" }} · ${r.status.label}" +
                        (if (r.status == RefStatus.CONFIRMED) " pendant ${r.observedSec / 60} min continues" else ", observée ${Templates.durationText(r.observedSec)}") +
                        " · pente ${r.inclinePct.toInt()} % · protocole ${r.protocol} v${r.protocolVersion}"
                }
            )
        } else Small("Repli : ancien calcul depuis ta vitesse de référence (${Fmt.kmh(profile.vr)} km/h).")
        Small("Sert à : ${refUse(k)}")
        if (editing) {
            Stepper(Fmt.kmh(value), "km/h", { value = Treadmill.fit(value - profile.treadmillStepKmh, Treadmill.spec(profile)) }, { value = Treadmill.fit(value + profile.treadmillStepKmh, Treadmill.spec(profile)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Annuler", Modifier.weight(1f), filled = false, height = 48.dp) { editing = false }
                Btn("Enregistrer", Modifier.weight(1f), height = 48.dp) { vm.setManualRef(k, value); editing = false }
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Saisir à la main", Modifier.weight(1f), filled = false, height = 48.dp) { editing = true }
            if (r != null) Btn("Effacer", Modifier.weight(1f), filled = false, height = 48.dp) { vm.clearRef(k) }
        }
    }
}

private object Templates {
    fun durationText(sec: Int): String = if (sec >= 60) "${sec / 60} min${if (sec % 60 != 0) " ${sec % 60} s" else ""}" else "$sec s"
}

@Composable
private fun HistoryCard(r: CalibrationRecord) {
    var open by remember { mutableStateOf(false) }
    Card(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Strong(r.kind.label, modifier = Modifier.weight(1f))
            Small("${Dates.date(r.day).let { "${it.dayOfMonth}/${it.monthValue}" }} · ${r.status.label}")
        }
        Small(if (r.refs.isEmpty()) "Aucune référence." else r.refs.joinToString(" · ") { "${it.kind.label} ${Fmt.kmh(it.kmh)} km/h (${it.status.label})" + if (it.kind in r.applied) "" else ", non appliquée" })
        if (open) {
            r.notes.forEach { Small("• $it") }
            SegmentsTable(r)
        }
    }
}

@Composable
private fun SegmentsTable(r: CalibrationRecord) {
    r.segments.forEach { s ->
        val v = if (s.checks.isEmpty()) "" else " · " + s.verdicts().joinToString(", ") { it.label.lowercase() }
        val speeds = if (Treadmill.same(s.proposedKmh, s.declaredKmh) || s.proposedKmh <= 0) "${Fmt.kmh(s.declaredKmh)} km/h" else "proposé ${Fmt.kmh(s.proposedKmh)}, réglé ${Fmt.kmh(s.declaredKmh)} km/h"
        Small("${s.kind.label}${if (s.n > 0) " ${s.n}" else ""} : $speeds · ${mmss(s.durationSec)}$v${if (s.interrupted) " · interrompu" else ""}${if (s.note.isNotBlank()) " · ${s.note}" else ""}")
        s.observations.mapNotNull { it.acoustic }.forEach { a -> a.summary.forEach { Small("   Lecture : $it") } }
    }
}

// ---------- Préparation ----------

@Composable
fun PacePrepScreen(vm: AppViewModel) {
    val profile = vm.state.profile ?: return
    val c = vm.pace
    var step by remember { mutableIntStateOf(0) }
    var today by remember { mutableIntStateOf(-1) }
    var kind by remember { mutableStateOf(PaceTests.recommend(profile)) }
    var candidate by remember { mutableDoubleStateOf(0.0) }
    var useUsual by remember { mutableStateOf(profile.usualWalkKmh > 0) }
    var easy by remember { mutableDoubleStateOf(profile.paceRefs.walkEasy?.kmh ?: 3.5) }
    var guided by remember { mutableStateOf(profile.paceRefs.walkEasy == null) }
    var flat by remember { mutableStateOf(false) }
    val tm = Treadmill.spec(profile)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) c.prepareVoice() else c.permissionDenied() }

    Column(Modifier.fillMaxSize().background(C.Ground).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Btn(if (step == 0) "‹ Mes allures" else "‹ Étape précédente", filled = false, outline = Color.Transparent, onOutline = C.Accent, height = 48.dp) {
                if (step == 0) { c.stopVoice(); vm.back() } else step--
            }
            Label("Test de calibration · étape ${step + 1} sur 5")
            when (step) {
                0 -> {
                    Title("Avant de commencer")
                    Strong("Aujourd'hui, comment te sens-tu ?")
                    val states = listOf("En forme", "Fatigué", "Une gêne limite mes mouvements")
                    states.forEachIndexed { i, s -> Chip(s, today == i, Modifier.fillMaxWidth()) { today = i } }
                    if (today == 2) Card(color = C.Soft) {
                        Strong("Pas de test aujourd'hui")
                        Body("Un inconfort qui limite le mouvement fausserait les repères, et le test pourrait l'aggraver. Reviens quand il a disparu ; si une douleur persiste, parles-en à un médecin ou un kiné.")
                    }
                    if (today == 1) Small("Fatigue : le test reste possible, ses résultats seront simplement plus prudents.")
                    Strong("Ce que tu fais aujourd'hui")
                    Capacity.values().forEach { cap -> Chip(cap.label, profile.capacity == cap, Modifier.fillMaxWidth()) { vm.updateProfile { it.copy(capacity = cap) } } }
                    Strong("Tes vitesses habituelles, si tu les connais")
                    KnownSpeed("Marche active habituelle", profile.usualWalkKmh, tm) { v -> vm.updateProfile { it.copy(usualWalkKmh = v) } }
                    if (profile.capacity != Capacity.WALK_ONLY) KnownSpeed("Trot habituel", profile.usualRunKmh, tm) { v -> vm.updateProfile { it.copy(usualRunKmh = v) } }
                    Strong("Ton tapis")
                    TreadmillSettings(vm, profile)
                    Btn("Continuer", Modifier.fillMaxWidth(), enabled = today in 0..1 && profile.capacity != null && profile.treadmillChecked) { step = 1 }
                    if (!profile.treadmillChecked) Small("Confirme d'abord les réglages de ton tapis.")
                }
                1 -> {
                    Title("Quel test ?")
                    Small("Chaque test dure 20 minutes au plus à partir du premier pas, échauffement, réglages et retour au calme compris. Plusieurs tests, à des dates différentes, affinent les repères ; le premier donne déjà un résultat utile.")
                    for (k in TestKind.values()) {
                        val locked = PaceTests.lockedReason(profile, k, vm.state.calibrations)
                        Card(color = if (kind == k) C.Soft else C.Card, onClick = if (locked == null) ({ kind = k; candidate = PaceTests.candidate(profile, k, vm.state.calibrations) }) else null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Strong(k.label, modifier = Modifier.weight(1f))
                                if (k == PaceTests.recommend(profile)) Text("conseillé", color = C.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Small(k.description)
                            if (locked != null) Small(locked, C.Orange)
                        }
                    }
                    if (candidate <= 0) candidate = PaceTests.candidate(profile, kind, vm.state.calibrations)
                    when (kind) {
                        TestKind.CONFIRM_WALK, TestKind.CONFIRM_RUN -> {
                            Strong("Vitesse à confirmer pendant 10 minutes")
                            Stepper(Fmt.kmh(candidate), "km/h", { candidate = Treadmill.fit(candidate - tm.stepKmh, tm) }, { candidate = Treadmill.fit(candidate + tm.stepKmh, tm) })
                            Small(if (kind == TestKind.CONFIRM_RUN) "Ton trot habituel, une allure où tu peux parler. Pas de plancher ni de plafond imposés, sinon ceux de ton tapis." else "La marche active repérée lors d'un test, ou ta marche active habituelle.")
                        }
                        TestKind.WALK_RUN -> Small("Tu choisiras ton trot lent naturel au début de la première répétition.")
                        TestKind.WALK_STEPS -> if (profile.usualWalkKmh > 0) {
                            Strong("Premier palier")
                            ChipRow(listOf("À ma marche active (${Fmt.kmh(profile.usualWalkKmh)})", "À ma marche facile"), if (useUsual) 0 else 1) { useUsual = it == 0 }
                            Small("Ta marche active habituelle seulement si elle te semble accessible aujourd'hui.")
                        }
                    }
                    Btn("Continuer", Modifier.fillMaxWidth(), enabled = PaceTests.lockedReason(profile, kind, vm.state.calibrations) == null && (kind == TestKind.WALK_STEPS || kind == TestKind.WALK_RUN || candidate > 0)) { step = 2 }
                }
                2 -> {
                    Title("Ta marche facile")
                    ChipRow(listOf("Recherche guidée", "Je la connais"), if (guided) 0 else 1) { guided = it == 0 }
                    Body(
                        if (guided) "Démarre à une allure qui te semble confortable : tu pourras la corriger pendant l'échauffement. Elle doit rester stable pendant ses 2 dernières minutes."
                        else "Indique ta marche facile : celle de tes échauffements et retours au calme."
                    )
                    Stepper(Fmt.kmh(easy), "km/h", { easy = Treadmill.fit(easy - tm.stepKmh, tm) }, { easy = Treadmill.fit(easy + tm.stepKmh, tm) })
                    Chip("Le tapis est à plat (pente 0 %)", flat, Modifier.fillMaxWidth()) { flat = !flat }
                    Small("Tout le test se fait à 0 % de pente.")
                    Btn("Continuer", Modifier.fillMaxWidth(), enabled = flat) { step = 3 }
                }
                3 -> {
                    Title("Le micro (facultatif)")
                    Body("La voix permet de répondre sans toucher l'écran et de comparer tes lectures à celles du départ. Tout se fait sur le téléphone, gratuitement, sans Internet une fois le modèle installé. Les boutons restent toujours disponibles.", C.Muted)
                    MicChip(c.mic, light = true)
                    if (c.micMessage.isNotBlank()) Small(c.micMessage)
                    when (c.mic) {
                        MicState.OFF, MicState.ERROR -> Btn("Utiliser la voix", Modifier.fillMaxWidth(), filled = false) {
                            if (vm.pace.models.ready || true) ask.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        MicState.NO_MODEL -> Btn("Télécharger le modèle vocal (41 Mo)", Modifier.fillMaxWidth(), filled = false) { c.downloadModel() }
                        MicState.DOWNLOADING -> {
                            ProgressBar(c.downloadProgress)
                            Btn("Annuler", Modifier.fillMaxWidth(), filled = false, height = 48.dp) { c.cancelDownload() }
                        }
                        else -> Unit
                    }
                    if (c.voiceAvailable) {
                        Card(color = C.Soft) {
                            Strong("Référence personnelle, avant de monter sur le tapis")
                            Small("1. Trois secondes de silence. 2. Deux lectures naturelles du passage, respiration libre. Garde le téléphone là où il sera pendant le test.")
                            Btn(if (c.ambientDb == null) "Mesurer le silence (3 s)" else "Silence mesuré ✓", Modifier.fillMaxWidth(), filled = false, height = 48.dp, enabled = c.prepBusy.isBlank()) { c.recordAmbient() }
                            Passage(dark = false)
                            Btn("Lecture ${minOf(c.baselineReadings.size + 1, 2)} sur 2", Modifier.fillMaxWidth(), height = 48.dp, enabled = c.prepBusy.isBlank() && c.ambientDb != null) {
                                if (c.baselineReadings.size >= 2) c.resetBaseline()
                                c.recordBaselineReading()
                            }
                            if (c.prepBusy.isNotBlank()) Strong(c.prepBusy, C.Accent)
                            c.baselineReadings.forEachIndexed { i, f ->
                                Small("Lecture ${i + 1} : ${Fmt.kmh(f.readingSec)} s, ${f.pauses} pause${if (f.pauses > 1) "s" else ""}" + if (f.usable) "" else " — inutilisable : " + f.issues.joinToString { it.label.lowercase() })
                            }
                            c.baseline?.let { b ->
                                if (b.valid) Small("Référence enregistrée.", C.Accent) else Small("Les deux lectures ne sont pas exploitables ou trop différentes : recommence, ou continue sans comparaison.", C.Orange)
                            }
                        }
                    }
                    Btn(if (c.voiceAvailable) "Continuer" else "Continuer sans micro", Modifier.fillMaxWidth()) { step = 4 }
                }
                else -> {
                    Title("Prêt ?")
                    Card(color = C.Soft) {
                        Strong(kind.label)
                        Small(kind.description)
                    }
                    Body("Monte sur le tapis, clé de sécurité attachée, pente 0 %. Démarre à ${Fmt.kmh(easy)} km/h. Les 20 minutes commencent quand tu confirmes.")
                    Card {
                        Strong("Sécurité")
                        Small("L'app recommande des vitesses : c'est toi qui règles le tapis. Les boutons Ralentir et Arrêter restent toujours à l'écran. Le micro n'est pas un arrêt d'urgence : en cas de problème, arrête le tapis avec sa clé.")
                        Small("Douleur dans la poitrine, malaise, essoufflement inhabituel : arrête-toi et appelle le 15 ou le 112.")
                    }
                    Btn("Je marche à ${Fmt.kmh(easy)} km/h", Modifier.fillMaxWidth()) {
                        val usual = if (kind == TestKind.WALK_STEPS && useUsual) profile.usualWalkKmh else 0.0
                        vm.beginPaceTest(kind, easy, if (kind == TestKind.WALK_STEPS) 0.0 else candidate, usual)
                    }
                }
            }
        }
    }
}

@Composable
private fun KnownSpeed(title: String, kmh: Double, tm: fr.relance.app.core.TreadmillSpec, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Small(title, modifier = Modifier.weight(1f))
        Chip("Je ne sais pas", kmh <= 0, padding = 8.dp) { onChange(0.0) }
    }
    if (kmh > 0) Stepper(Fmt.kmh(kmh), "km/h", { onChange(Treadmill.fit(kmh - tm.stepKmh, tm)) }, { onChange(Treadmill.fit(kmh + tm.stepKmh, tm)) })
    else Btn("Je la connais", Modifier.fillMaxWidth(), filled = false, height = 48.dp) { onChange(if (title.contains("Trot")) 7.0 else 5.0) }
}

@Composable
fun TreadmillSettings(vm: AppViewModel, profile: Profile) {
    Small("Vitesse minimale")
    Stepper(Fmt.kmh(profile.treadmillMinKmh), "km/h", { vm.updateProfile { it.copy(treadmillMinKmh = (it.treadmillMinKmh - 0.1).coerceAtLeast(0.1).let(fr.relance.app.core.Physio::round1)) } }, { vm.updateProfile { it.copy(treadmillMinKmh = (it.treadmillMinKmh + 0.1).coerceAtMost(4.0).let(fr.relance.app.core.Physio::round1)) } })
    Small("Vitesse maximale")
    Stepper(Fmt.kmh(profile.treadmillMaxKmh), "km/h", { vm.updateProfile { it.copy(treadmillMaxKmh = (it.treadmillMaxKmh - 1).coerceAtLeast(6.0)) } }, { vm.updateProfile { it.copy(treadmillMaxKmh = (it.treadmillMaxKmh + 1).coerceAtMost(25.0)) } })
    Small("Pas de réglage de la vitesse")
    val steps = listOf(0.1, 0.2, 0.5, 1.0)
    ChipRow(steps.map { Fmt.kmh(it) }, steps.indexOfFirst { Treadmill.same(it, profile.treadmillStepKmh) }) { i -> vm.updateProfile { it.copy(treadmillStepKmh = steps[i]) } }
    Chip(if (profile.treadmillChecked) "Réglages de mon tapis confirmés ✓" else "Ce sont bien les réglages de mon tapis", profile.treadmillChecked, Modifier.fillMaxWidth()) {
        vm.updateProfile { it.copy(treadmillChecked = !it.treadmillChecked) }
    }
}

@Composable
private fun Passage(dark: Boolean) {
    Text(
        PaceProtocol.PASSAGE, color = if (dark) Color.White else C.Ink, fontSize = if (dark) 26.sp else 20.sp, lineHeight = if (dark) 36.sp else 28.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (dark) C.NightCard else C.Card).padding(16.dp),
    )
}

@Composable
private fun MicChip(m: MicState, light: Boolean = false) {
    val on = m in setOf(MicState.READY, MicState.LISTENING, MicState.READING)
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (light) C.Soft else C.NightCard).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(if (on) C.Mint else if (m == MicState.SPEAKING) C.Sweat else C.Orange)
        Text(m.label, color = if (light) C.Ink else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- Déroulé ----------

@Composable
fun PaceRunScreen(vm: AppViewModel) {
    val c = vm.pace
    val e = c.engine ?: return
    @Suppress("UNUSED_VARIABLE") val v = c.version
    val s = e.s
    val profile = vm.state.profile ?: return
    val tm = s.treadmill
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var picker by remember { mutableStateOf(false) }
    var symptoms by remember { mutableStateOf(false) }
    val seg = s.current
    val now = maxOf(s.now, 0)

    Column(Modifier.fillMaxSize().background(C.Night).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.kind.label, color = C.NightMuted, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("Reste ${mmss(c.budgetLeft)} / 20:00", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            MicChip(c.mic)
            if (c.micMessage.isNotBlank() && (c.mic == MicState.ERROR || c.mic == MicState.NO_PERMISSION)) Text(c.micMessage, color = C.NightMuted, fontSize = 13.sp)

            when (s.phase) {
                Phase.PAUSED -> Card(color = C.NightCard) {
                    Text("Test en pause", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("${s.pauseReason?.label ?: ""}. L'effort ne reprend pas tout seul : la partie en cours recommencera après confirmation de ta vitesse, s'il reste assez de temps. Les 20 minutes continuent de s'écouler.", color = C.NightMuted, fontSize = 16.sp)
                    Btn("Reprendre", Modifier.fillMaxWidth(), fill = C.Mint, onFill = C.Night) { c.resume() }
                    Btn("Terminer le test", Modifier.fillMaxWidth(), filled = false, outline = C.NightLine, onOutline = Color.White) { c.stop() }
                }
                Phase.AWAIT_SPEED -> s.pending?.let { p ->
                    val limit = if (p.kind == SegKind.STEP || p.kind == SegKind.BLOCK) p.proposedKmh else tm.maxKmh
                    var chosen by remember(p.kind, p.n) { mutableDoubleStateOf(if (p.proposedKmh > 0) p.proposedKmh else profile.usualRunKmh.takeIf { it > 0 } ?: 6.0) }
                    Card(color = C.NightCard) {
                        Text(when (p.kind) { SegKind.STEP -> "Palier ${p.n} sur 3"; SegKind.REP -> "Trot ${p.n} sur 3"; else -> "10 minutes à allure constante" }, color = C.NightMuted, fontSize = 16.sp)
                        Text(if (p.proposedKmh > 0) "Règle le tapis sur" else "Règle ton trot lent naturel", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        BigSpeed(chosen)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Btn("−", Modifier.weight(1f), filled = false, outline = C.NightLine, onOutline = Color.White, height = 64.dp) { chosen = Treadmill.fit(chosen - tm.stepKmh, tm) }
                            Btn("+", Modifier.weight(1f), filled = false, outline = C.NightLine, onOutline = Color.White, height = 64.dp, enabled = chosen + 0.001 < limit) { chosen = minOf(limit, Treadmill.fit(chosen + tm.stepKmh, tm)) }
                        }
                        Btn("C'est réglé : ${Fmt.kmh(chosen)} km/h", Modifier.fillMaxWidth(), fill = C.Mint, onFill = C.Night, height = 64.dp) { c.confirmSpeed(chosen) }
                        Text("Le palier démarre quand tu confirmes. Indique la vitesse réellement affichée par le tapis.", color = C.NightMuted, fontSize = 14.sp)
                    }
                }
                Phase.RUNNING -> if (seg != null) {
                    val left = seg.plannedSec - (now - seg.startAt)
                    Card(color = C.NightCard) {
                        Text(seg.kind.label + if (seg.n > 0) " ${seg.n}" else "", color = C.NightMuted, fontSize = 18.sp)
                        Row(verticalAlignment = Alignment.Bottom) {
                            BigSpeed(seg.declaredKmh, Modifier.weight(1f))
                            Text(mmss(left), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        }
                        if (seg.kind == SegKind.WARMUP && seg.checks.isNotEmpty()) Text("Marche facile stable depuis ${mmss(now - seg.stableSince)} (2:00 demandées).", color = C.NightMuted, fontSize = 14.sp)
                        if (seg.kind == SegKind.COOLDOWN) Text("Ralentis si tu en as besoin : c'est toi qui règles le tapis.", color = C.NightMuted, fontSize = 14.sp)
                    }
                }
                Phase.FINISHED, Phase.READY -> Unit
            }

            c.flow?.let { f -> CheckPanel(vm, f) }

            if (picker && seg != null && s.phase == Phase.RUNNING) {
                var chosen by remember { mutableDoubleStateOf(seg.declaredKmh) }
                Card(color = C.NightCard) {
                    Text("Vitesse réellement réglée", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    BigSpeed(chosen)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Btn("−", Modifier.weight(1f), filled = false, outline = C.NightLine, onOutline = Color.White, height = 60.dp) { chosen = Treadmill.fit(chosen - tm.stepKmh, tm) }
                        Btn("+", Modifier.weight(1f), filled = false, outline = C.NightLine, onOutline = Color.White, height = 60.dp) { chosen = Treadmill.fit(chosen + tm.stepKmh, tm) }
                    }
                    Btn("Corriger : ${Fmt.kmh(chosen)} km/h", Modifier.fillMaxWidth(), fill = C.Mint, onFill = C.Night) { c.correctSpeed(chosen); picker = false }
                    Text(if (seg.kind == SegKind.STEP || seg.kind == SegKind.BLOCK || seg.kind == SegKind.REP) "Une correction fait repartir cette partie de zéro (si le temps le permet)." else "Pendant l'échauffement, la marche doit ensuite rester stable 2 minutes.", color = C.NightMuted, fontSize = 13.sp)
                }
            }
            if (symptoms) Card(color = C.NightCard) {
                Text("Qu'est-ce qui se passe ?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Symptom.values().forEach { sy -> Btn(sy.label, Modifier.fillMaxWidth(), fill = C.Orange, onFill = C.Night) { c.symptom(sy); symptoms = false } }
                Btn("Fausse alerte", Modifier.fillMaxWidth(), filled = false, outline = C.NightLine, onOutline = Color.White) { symptoms = false }
            }
        }
        // Actions toujours accessibles.
        if (s.phase != Phase.FINISHED) Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            val extra = when {
                s.phase != Phase.RUNNING || seg == null -> null
                seg.kind == SegKind.STEP -> listOf("Marche difficile", "Envie de trotter")
                seg.kind == SegKind.REP -> listOf("Trot pas naturel")
                seg.kind == SegKind.RECOVERY -> listOf("Prolonger la récupération")
                else -> null
            }
            if (extra != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                extra.forEach { label ->
                    Btn(label, Modifier.weight(1f), filled = false, outline = C.NightLine, onOutline = Color.White, height = 52.dp) {
                        when (label) {
                            "Prolonger la récupération" -> c.extendRecovery()
                            "Trot pas naturel" -> c.endProgression("Trot pas naturel à cette vitesse : essayer des blocs plus courts ou une progression en marche.")
                            "Envie de trotter" -> c.endProgression("Envie de passer au trot : limite de la marche atteinte.")
                            else -> c.endProgression("Marche difficile : progression arrêtée.")
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Ralentir", Modifier.weight(1f), fill = C.Sweat, onFill = C.Night, height = 60.dp) { if (!c.slowDown()) picker = true }
                Btn(if (s.phase == Phase.PAUSED) "En pause" else "Pause", Modifier.weight(1f), filled = false, outline = C.NightLine, onOutline = Color.White, height = 60.dp, enabled = s.phase != Phase.PAUSED) { c.pause(PauseReason.USER) }
                Btn("Arrêter", Modifier.weight(1f), fill = C.Orange, onFill = C.Night, height = 60.dp) { c.stop() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Corriger la vitesse", Modifier.weight(1f), filled = false, outline = C.NightLine, onOutline = Color.White, height = 52.dp, enabled = s.phase == Phase.RUNNING) { picker = !picker }
                Btn("Douleur, malaise", Modifier.weight(1f), filled = false, outline = C.Orange, onOutline = C.Orange, height = 52.dp) { symptoms = true }
            }
        }
    }
}

@Composable
private fun BigSpeed(kmh: Double, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(Fmt.kmh(kmh), color = C.Mint, fontSize = 64.sp, fontWeight = FontWeight.Bold, lineHeight = 64.sp)
        Text(" km/h", color = C.NightMuted, fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun CheckPanel(vm: AppViewModel, f: CheckFlow) {
    val c = vm.pace
    @Suppress("UNUSED_VARIABLE") val v = c.version
    Card(color = C.NightCard) {
        Text(if (f.late) "Réponds pour le trot que tu viens de finir" else "Contrôle de parole", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (f.reading) {
            Text("Lis à voix haute, naturellement, sans retenir ta respiration :", color = C.NightMuted, fontSize = 15.sp)
            Passage(dark = true)
            Btn("J'ai lu le texte", Modifier.fillMaxWidth(), filled = false, outline = C.NightLine, onOutline = Color.White, height = 56.dp) { c.readingDone() }
            return@Card
        }
        f.acoustic?.let { a -> a.summary.forEach { Text(it, color = if (a.changed) C.Orange else C.NightMuted, fontSize = 14.sp) } }
        if (f.extra && !f.extraAsked) {
            Text("Ta lecture semblait moins fluide qu'au départ. Parler reste-t-il vraiment confortable ?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        } else Text("Parler était-il confortable ?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Speech.values().forEach { sp -> NightChip(sp.label, f.speech == sp && !(f.extra && !f.extraAsked), Modifier.weight(1f)) { c.setSpeech(sp) } }
        }
        Text("Effort global : 0 repos · 3 modéré · 5 difficile · 10 maximal", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Small("Un repère simple propre à l'app.", C.NightMuted)
        for (row in listOf(0..5, 6..10)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { n -> NightChip("$n", f.rpe == n, Modifier.weight(1f)) { c.setRpe(n) } }
        }
        Text("Ton déplacement était-il naturel et confortable ?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NightChip("Oui", f.movement == true, Modifier.weight(1f)) { c.setMovement(true) }
            NightChip("Non", f.movement == false, Modifier.weight(1f)) { c.setMovement(false) }
        }
        if (f.heard.isNotBlank()) Text("Entendu : « ${f.heard} »", color = C.NightMuted, fontSize = 13.sp)
        if (!f.complete) Btn("Valider sans tout renseigner", Modifier.fillMaxWidth(), filled = false, outline = C.NightLine, onOutline = C.NightMuted, height = 48.dp) { c.submit() }
        if (!f.complete) Text("Une réponse manquante ne permet aucune progression.", color = C.NightMuted, fontSize = 13.sp)
    }
}

@Composable
private fun NightChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Btn(text, modifier, filled = selected, fill = C.Mint, onFill = C.Night, outline = C.NightLine, onOutline = Color.White, height = 56.dp, onClick = onClick)
}

// ---------- Résultat ----------

@Composable
fun PaceResultScreen(vm: AppViewModel) {
    val r = vm.paceRecord ?: return
    val profile = vm.state.profile ?: return
    val (merged, kept) = profile.paceRefs.merge(r.refs)
    Column(Modifier.fillMaxSize().background(C.Ground).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Label(r.kind.label)
            Title(
                when (r.status) {
                    CalStatus.COMPLETE -> "Test terminé"
                    CalStatus.PARTIAL -> "Test incomplet"
                    CalStatus.STOPPED -> "Test arrêté"
                    CalStatus.ABORTED -> "Test interrompu"
                }
            )
            if (r.symptom != null) Card(color = Color(0xFFFFE7D6)) {
                Strong("${r.symptom.label} : arrête l'effort")
                Body(
                    if (r.symptom == Symptom.GAIT_PAIN) "Arrête le tapis et ne reprends pas aujourd'hui. Si la douleur persiste ou t'empêche de marcher normalement, consulte un médecin ou un kiné."
                    else "Arrête le tapis, assieds-toi ou allonge-toi. Si la douleur dans la poitrine, le malaise ou l'essoufflement ne passent pas en quelques minutes, ou s'ils sont intenses : appelle le 15 (SAMU) ou le 112. Ne reprends pas d'effort sans avis médical."
                )
                Small("Aucune référence n'est enregistrée à partir de ce test.")
            }
            Small("Durée depuis le premier pas : ${mmss(r.durationSec)} · ${r.endReason?.label ?: ""}")
            if (r.refs.isEmpty() && r.symptom == null) Card(color = C.Soft) {
                Strong("Pas de nouvelle référence")
                Small("Tes séances gardent leurs allures actuelles.")
            }
            r.refs.forEach { ref ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Strong(ref.kind.label, modifier = Modifier.weight(1f))
                        Text("${Fmt.kmh(ref.kmh)} km/h", color = C.Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Small(
                        (if (ref.status == RefStatus.CONFIRMED) "Confirmée pendant ${ref.observedSec / 60} min à vitesse constante." else "Provisoire : observée ${Templates.durationText(ref.observedSec)}.") +
                            " Servira à : ${refUse(ref.kind).lowercase()}"
                    )
                    if (ref in kept) Small("Ta référence confirmée (${Fmt.kmh(profile.paceRefs[ref.kind]!!.kmh)} km/h) est gardée : celle-ci reste une candidate à confirmer.", C.Orange)
                    else profile.paceRefs[ref.kind]?.let { Small("Remplace ${Fmt.kmh(it.kmh)} km/h (${it.status.label}).") }
                    if (ref.kind == RefKind.RUN_EASY && ref.status == RefStatus.PROVISIONAL) Small("Valable pour des blocs courts : elle ne dit pas que tu peux courir longtemps à cette vitesse.")
                }
            }
            if (r.notes.isNotEmpty()) Card {
                Strong("Ce qui s'est passé")
                r.notes.forEach { Small("• $it") }
            }
            if (r.suggestions.isNotEmpty()) Card(color = C.Soft) {
                Strong("Pour un prochain test")
                r.suggestions.forEach { Small("• ${it.kind.label} à ${Fmt.kmh(it.kmh)} km/h : ${it.reason}") }
            }
            Card {
                Strong("Détail")
                SegmentsTable(r)
                r.voiceBaseline?.let { b -> Small("Lectures de départ : ${b.readings.joinToString { "${Fmt.kmh(it.readingSec)} s, ${it.pauses} pauses" }} (${b.device}).") }
                Small("Les écarts de lecture décrivent un changement mesuré, pas un état physiologique : ils n'ont servi qu'à poser une question de plus.")
            }
            if (r.refs.isNotEmpty() && r.symptom == null) {
                Btn("Enregistrer et utiliser ces repères", Modifier.fillMaxWidth()) { vm.savePaceResult(apply = true) }
                Small("Tes prochaines séances seront recalculées. Les séances déjà faites gardent leurs vitesses.")
            }
            Btn(if (r.refs.isEmpty() || r.symptom != null) "Enregistrer dans l'historique" else "Garder dans l'historique sans les utiliser", Modifier.fillMaxWidth(), filled = r.refs.isEmpty() || r.symptom != null) { vm.savePaceResult(apply = false) }
            @Suppress("UNUSED_VARIABLE") val unused: PaceRefs = merged
        }
    }
}

private fun Verdicts.label(o: fr.relance.app.core.Observation?) = classify(o).label
