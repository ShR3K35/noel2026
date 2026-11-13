package fr.relance.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.relance.app.ble.Link
import fr.relance.app.core.Coach
import fr.relance.app.core.Fmt
import fr.relance.app.core.Physio
import fr.relance.app.core.PlannedSession
import fr.relance.app.core.Program
import fr.relance.app.core.ProgramStatus
import fr.relance.app.core.Stats
import kotlin.math.max
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import fr.relance.app.data.HcState
import fr.relance.app.data.HealthConnect

private val TABS = listOf(
    "Aujourd'hui" to Icons.Filled.Home, "Planning" to Icons.Filled.DateRange, "Historique" to Icons.AutoMirrored.Filled.List,
    "Programmes" to Icons.Filled.Star, "Profil" to Icons.Filled.Person,
)

@Composable
fun HomeScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().background(C.Ground)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (vm.tab) {
                0 -> TodayTab(vm)
                1 -> PlanningTab(vm)
                2 -> HistoryTab(vm)
                3 -> ProgramsTab(vm)
                else -> ProfileTab(vm)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Track))
        Row(Modifier.fillMaxWidth().background(C.Card).padding(vertical = 6.dp)) {
            TABS.forEachIndexed { i, (label, icon) ->
                val on = vm.tab == i
                Column(
                    Modifier.weight(1f).semantics { selected = on }.clickable(role = Role.Tab) { vm.tab = i }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(icon, contentDescription = null, tint = if (on) C.Accent else C.Muted, modifier = Modifier.size(24.dp))
                    Text(label, color = if (on) C.Accent else C.Muted, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ProgramProgress(p: Program) {
    val total = max(p.weeksPlanned, p.week + 1)
    Card(padding = 14.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Strong("Programme · ${p.type.label}")
            Small("Semaine ${p.week + 1} sur $total")
        }
        ProgressBar(p.stepIndex.toFloat() / max(1, p.ladder.size))
        Small("${p.sessionsDone} séance${if (p.sessionsDone > 1) "s" else ""} tenue${if (p.sessionsDone > 1) "s" else ""}")
    }
}

@Composable
private fun SessionHero(s: PlannedSession, vm: AppViewModel, canStart: Boolean, startLabel: String) {
    Card(color = C.Accent, padding = 20.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Label(s.title, Color.White)
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(if (s.deload) "Allégée" else if (s.isKey) "Séance clé" else "Facile", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("${s.minutes} min", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Body(s.detail, Color.White)
        if (s.inclinePct > 0) Strong("Règle l'inclinaison avant de monter sur le tapis.", Color.White)
        if (canStart) {
            Spacer(Modifier.height(4.dp))
            Btn(startLabel, Modifier.fillMaxWidth(), fill = Color.White, onFill = C.Accent, height = 54.dp) { vm.startSession() }
        }
    }
}

@Composable
private fun TodayTab(vm: AppViewModel) {
    val today = vm.today()
    val profile = vm.state.profile ?: return
    val p = vm.state.program
    val hrLink by vm.ble.hrLink.collectAsState()
    val tmLink by vm.ble.treadmillLink.collectAsState()
    Small(Dates.long(today))
    if (p == null) {
        Title("Prêt pour la suite ?")
        val lastDone = vm.state.archived.firstOrNull()
        if (lastDone != null && lastDone.status == ProgramStatus.SUCCESS)
            Card(color = C.Soft) { Strong("${lastDone.type.label} : réussi"); Body("Bravo. Ne laisse pas de vide après une réussite : enchaîne sur un nouvel objectif ou un programme d'entretien.") }
        Body("Aucun programme en cours. Conseillé pour toi : ${Coach.recommended(profile).label}.", C.Muted)
        Btn("Choisir un programme", Modifier.fillMaxWidth()) { vm.open(Screen.Catalogue) }
        return
    }
    val next = vm.plan.firstOrNull()
    val doneToday = p.lastSessionDay == today
    Title(
        when {
            p.paused -> "Programme en pause"
            doneToday -> "Séance du jour faite"
            next != null && next.day == today -> "Ta séance du jour"
            else -> "Jour de repos"
        }
    )
    ProgramProgress(p)
    when {
        p.paused -> {
            Body("Le programme est figé. À la reprise, je recale le niveau selon la durée de la coupure.", C.Muted)
            Btn("Reprendre le programme", Modifier.fillMaxWidth()) { vm.togglePauseProgram() }
        }
        next == null -> Body("Plus de séance planifiée.", C.Muted)
        doneToday -> {
            Card(color = C.Soft) { Label("Le mot du coach", C.Accent); Body(vm.state.sessions.firstOrNull()?.coachMessage ?: "") }
            Label("Prochaine séance · ${Dates.relative(next.day, today)}")
            SessionHero(next, vm, canStart = false, startLabel = "")
        }
        next.day == today -> {
            SessionHero(next, vm, canStart = true, startLabel = "Démarrer la séance")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (next.minutes > 15) Btn("Je n'ai que 15 min", Modifier.weight(1f), filled = false, height = 48.dp) { vm.startSession(15) }
                Btn("Reporter", Modifier.weight(1f), filled = false, height = 48.dp) { vm.postpone() }
            }
            ManualButton(vm)
        }
        else -> {
            val resting = p.restUntilDay > today && p.painCount > 0
            Body(
                if (resting) "Repos après la douleur signalée. Le corps récupère : c'est une partie de l'entraînement."
                else "Le repos fait partie du programme : c'est là que le corps assimile.", C.Muted,
            )
            Label("Prochaine séance · ${Dates.relative(next.day, today)}")
            SessionHero(next, vm, canStart = !resting, startLabel = "La faire aujourd'hui")
            if (!resting) ManualButton(vm)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SensorPill("Ceinture cardio", profile.hrAddress.isNotBlank(), hrLink, Modifier.weight(1f)) { vm.open(Screen.Sensors) }
        SensorPill("Tapis", profile.treadmillAddress.isNotBlank(), tmLink, Modifier.weight(1f)) { vm.open(Screen.Sensors) }
    }
}

@Composable
private fun SensorPill(name: String, paired: Boolean, link: Link, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier, padding = 12.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(if (link == Link.CONNECTED) C.Accent else C.Line)
            Column {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                Small(if (!paired) "À associer" else link.label)
            }
        }
    }
}

@Composable
private fun PlanRow(s: PlannedSession, today: Long) {
    val isToday = s.day == today
    Card(color = if (isToday) C.Accent else C.Card, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Dates.dow(s.day), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (isToday) Color.White else C.Muted)
                Text(Dates.dom(s.day), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (isToday) Color.White else C.Ink)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Strong("${s.title} · ${s.minutes} min", if (isToday) Color.White else C.Ink)
                Small(s.detail, if (isToday) Color.White else C.Muted)
            }
        }
    }
}

@Composable
private fun PlanningTab(vm: AppViewModel) {
    val today = vm.today()
    Title("Planning")
    val p = vm.state.program
    if (p == null || vm.plan.isEmpty()) {
        Body(if (p?.paused == true) "Programme en pause : pas de séance planifiée." else "Aucun programme en cours.", C.Muted)
        return
    }
    val weeks = vm.plan.groupBy { it.week }.toSortedMap()
    val detailed = weeks.keys.take(2)
    for (w in detailed) {
        val list = weeks[w] ?: continue
        Label("Semaine ${w + 1}" + if (list.first().deload) " · allégée" else "")
        list.forEach { PlanRow(it, today) }
    }
    val rest = weeks.keys.drop(2)
    if (rest.isNotEmpty()) {
        Label("Ensuite · prévisionnel")
        Card(padding = 14.dp) {
            for (w in rest) {
                val list = weeks[w] ?: continue
                val key = list.firstOrNull { it.isKey } ?: list.first()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sem. ${w + 1}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.width(62.dp))
                    Small(if (list.first().deload) "Allégée + test de forme" else key.detail, modifier = Modifier.weight(1f))
                }
            }
        }
        Small("Arrivée estimée : ${Dates.long(vm.plan.last().day)}. Ce calendrier s'ajuste à tes séances réelles.")
    }
    Btn("Modifier mes jours", Modifier.fillMaxWidth(), filled = false) { vm.tab = 4 }
}

@Composable
private fun HistoryTab(vm: AppViewModel) {
    val today = vm.today()
    val sessions = vm.state.sessions
    Title("Historique")
    if (sessions.isEmpty()) {
        Body("Tes séances apparaîtront ici, avec leurs statistiques.", C.Muted)
        return
    }
    val weeks = Stats.lastWeeks(sessions, today)
    val maxMin = max(1, weeks.maxOf { it.minutes })
    Card {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Strong("4 dernières semaines"); Small("minutes par semaine") }
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            weeks.forEachIndexed { i, w ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${w.minutes}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                    Box(
                        Modifier.fillMaxWidth().height((4 + 70 * w.minutes / maxMin).dp).clip(RoundedCornerShape(6.dp))
                            .background(if (i == weeks.size - 1) C.Accent else Color(0xFF9CC7B5))
                    )
                    Small(if (i == weeks.size - 1) "en cours" else Dates.dom(w.startDay) + " " + Dates.short(w.startDay).substringAfterLast(' '))
                }
            }
        }
        val t = Stats.totals(sessions)
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Track))
        Row(Modifier.fillMaxWidth()) {
            Stat("${t.sessions}", "séances", Modifier.weight(1f))
            Stat(Fmt.duration(t.seconds), "temps actif", Modifier.weight(1f))
            Stat("${Fmt.km(t.distanceM)} km", "distance", Modifier.weight(1f))
        }
    }
    val r = Stats.records(sessions)
    Card {
        Strong("Records")
        Row(Modifier.fillMaxWidth()) {
            Stat(if (r.longestRunMin > 0) "${r.longestRunMin} min" else "—", "course continue", Modifier.weight(1f))
            Stat("${Fmt.km(r.longestDistanceM)} km", "plus longue distance", Modifier.weight(1f))
            Stat(Fmt.duration(r.longestSessionSec), "plus longue séance", Modifier.weight(1f))
        }
    }
    Label("Séances")
    sessions.forEach { s ->
        Card(padding = 14.dp, onClick = { vm.open(Screen.Detail(s.id)) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Strong(s.title)
                    Small("${Dates.short(s.day)} · ${Fmt.duration(s.durationSec)} · ${Fmt.km(s.distanceM)} km" + if (s.manual) " · saisie manuelle" else "")
                }
                Small("Effort ${s.rpe}")
            }
        }
    }
}

@Composable
private fun ProgramsTab(vm: AppViewModel) {
    val p = vm.state.program
    var confirmStop by remember { mutableStateOf(false) }
    Title("Programmes")
    Label("En cours")
    if (p == null) {
        Body("Aucun programme en cours.", C.Muted)
        Btn("Choisir un programme", Modifier.fillMaxWidth()) { vm.open(Screen.Catalogue) }
    } else {
        Card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(p.type.label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                Small(if (p.paused) "En pause" else "Semaine ${p.week + 1} sur ${max(p.weeksPlanned, p.week + 1)}")
            }
            ProgressBar(p.stepIndex.toFloat() / max(1, p.ladder.size))
            Row(Modifier.fillMaxWidth()) {
                Stat("${p.sessionsDone}", "séances tenues", Modifier.weight(1f))
                Stat("${Fmt.kmh(vm.state.profile?.vr ?: 0.0)} km/h", "vitesse de référence", Modifier.weight(1f))
                Stat(vm.plan.lastOrNull()?.let { Dates.short(it.day).substringAfter(' ') } ?: "—", "arrivée estimée", Modifier.weight(1f))
            }
            Small("Objectif : ${p.type.validation}.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (p.paused) "Reprendre" else "Mettre en pause", Modifier.weight(1f), filled = false, height = 48.dp) { vm.togglePauseProgram() }
                Btn("Arrêter", Modifier.weight(1f), filled = false, height = 48.dp) { confirmStop = true }
            }
        }
        Small("Un seul programme à la fois. Pour en commencer un autre, termine ou arrête celui-ci : il sera gardé dans la liste ci-dessous.")
    }
    Label("Programmes effectués")
    if (vm.state.archived.isEmpty()) Body("Aucun pour l'instant.", C.Muted)
    vm.state.archived.forEach { a ->
        Card(padding = 14.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(a.type.label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                Text(a.status.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (a.status == ProgramStatus.SUCCESS) C.Accent else C.Muted)
            }
            Small("Du ${Dates.short(a.startDay)} au ${Dates.short(a.endDay)} · ${a.week + (if (a.sessionInWeek > 0) 1 else 0)} semaine(s)")
            Body("${a.sessionsDone} séance(s) sur ${max(a.sessionsPlanned, a.sessionsDone)} prévues")
            Strong("Vitesse de référence : ${Fmt.kmh(a.vrStart)} → ${Fmt.kmh(a.vrEnd)} km/h")
        }
    }
    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false }, containerColor = C.Card,
        title = { Text("Arrêter ce programme ?") },
        text = { Text("Il sera rangé dans les programmes effectués comme « interrompu », avec ton avancement. Tu pourras ensuite en choisir un autre.") },
        confirmButton = { TextButton(onClick = { confirmStop = false; vm.stopProgram() }) { Text("Arrêter") } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Continuer") } },
    )
}

@Composable
private fun ProfileTab(vm: AppViewModel) {
    val profile = vm.state.profile ?: return
    val hrLink by vm.ble.hrLink.collectAsState()
    val tmLink by vm.ble.treadmillLink.collectAsState()
    Title("Profil")
    Label("Capteurs")
    Card(padding = 14.dp) {
        SettingRow("Ceinture cardio", if (profile.hrAddress.isBlank()) "Associer" else hrLink.label) { vm.open(Screen.Sensors) }
        SettingRow("Tapis (boîtier ou FTMS)", if (profile.treadmillAddress.isBlank()) "Associer" else tmLink.label) { vm.open(Screen.Sensors) }
    }
    Label("Mes disponibilités")
    Card {
        DayPicker(profile.days) { d -> if (d.isNotEmpty()) vm.updateProfile { it.copy(days = d) } }
        MinutesPicker(profile.minutesPerSession) { m -> vm.updateProfile { it.copy(minutesPerSession = m) } }
        Small("${Coach.trainingDays(profile).size} séance(s) par semaine. Le planning se recalcule tout de suite.")
    }
    Label("Mon corps")
    Card {
        Strong("Fréquence cardiaque de repos")
        Stepper("${profile.restHr}", "bpm", { vm.updateProfile { it.copy(restHr = (it.restHr - 1).coerceAtLeast(35)) } }, { vm.updateProfile { it.copy(restHr = (it.restHr + 1).coerceAtMost(110)) } })
        Btn("Mesurer avec ma ceinture", Modifier.fillMaxWidth(), filled = false, height = 48.dp) { vm.open(Screen.RestingHr) }
        Small("Zones cardiaques calculées avec un maximum de ${Physio.maxHr(profile)} bpm" + if (profile.hrMedication) " — peu fiables avec ton traitement : fie-toi au ressenti." else ".")
        Strong("Poids")
        Stepper("${profile.weightKg.toInt()}", "kg", { vm.updateProfile { it.copy(weightKg = (it.weightKg - 1).coerceAtLeast(35.0)) } }, { vm.updateProfile { it.copy(weightKg = (it.weightKg + 1).coerceAtMost(200.0)) } })
        SettingRow("Niveau de départ", profile.level.label)
        SettingRow("Vitesse de référence", "${Fmt.kmh(profile.vr)} km/h")
    }
    Label("Mon tapis")
    Card {
        Strong("Vitesse maximale")
        Stepper(Fmt.kmh(profile.treadmillMaxKmh), "km/h", { vm.updateProfile { it.copy(treadmillMaxKmh = (it.treadmillMaxKmh - 1).coerceAtLeast(6.0)) } }, { vm.updateProfile { it.copy(treadmillMaxKmh = (it.treadmillMaxKmh + 1).coerceAtMost(25.0)) } })
        Strong("Inclinaison disponible")
        ChipRow(listOf("Aucune", "3 %", "5 %"), listOf(0.0, 3.0, 5.0).indexOf(profile.inclinePct)) { i -> vm.updateProfile { it.copy(inclinePct = listOf(0.0, 3.0, 5.0)[i]) } }
        Small("Réglages par défaut : Domyos Run100 (14 km/h, inclinaison manuelle 0 ou 3 %).")
    }
    Label("Santé Connect")
    HealthConnectCard(vm)
    Label("Pendant la séance")
    Card {
        Strong("Coureur animé")
        ChipRow(listOf("Activé", "Désactivé"), if (profile.runnerAnimation) 0 else 1) { i -> vm.updateProfile { it.copy(runnerAnimation = i == 0) } }
        Strong("Annonces vocales")
        ChipRow(listOf("Activées", "Désactivées"), if (profile.voice) 0 else 1) { i -> vm.updateProfile { it.copy(voice = i == 0) } }
    }
    Small("Relance donne des conseils d'entraînement, pas un avis médical. En cas de douleur dans la poitrine ou de vertige, arrête-toi et consulte.")
}

/** Envoi des séances vers Santé Connect, pour les retrouver dans Google Health. */
@Composable
private fun HealthConnectCard(vm: AppViewModel) {
    val profile = vm.state.profile ?: return
    val ctx = LocalContext.current
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract(HealthConnect.PROVIDER)) { granted ->
        vm.onHealthPermissions(granted.intersect(HealthConnect.PERMISSIONS))
    }
    LaunchedEffect(Unit) { vm.refreshHealthConnect() }
    Card {
        when (vm.hcState) {
            HcState.UNAVAILABLE -> {
                Strong("Non disponible sur ce téléphone")
                Small("Santé Connect demande Android 9 ou plus récent.")
            }
            HcState.INSTALL_REQUIRED -> {
                Strong("Santé Connect n'est pas installé")
                Small("Sur Android 13 et avant, c'est une application à installer depuis le Play Store. Elle est intégrée à partir d'Android 14.")
                Btn("Installer Santé Connect", Modifier.fillMaxWidth(), height = 48.dp) {
                    try { ctx.startActivity(vm.hc.installIntent()) } catch (e: Exception) { vm.hcMessage = "Play Store introuvable sur ce téléphone." }
                }
            }
            HcState.NO_PERMISSION -> {
                Strong("Retrouve tes séances dans Google Health")
                Small("Relance écrit chaque séance dans Santé Connect : exercice, distance, calories actives estimées, vitesse et fréquence cardiaque. Elle ne lit rien.")
                Btn("Connecter Santé Connect", Modifier.fillMaxWidth(), height = 48.dp) { ask.launch(HealthConnect.PERMISSIONS) }
            }
            HcState.READY -> {
                Strong("Envoi après chaque séance")
                ChipRow(listOf("Activé", "Désactivé"), if (profile.healthConnect) 0 else 1) { vm.setHealthConnectAuto(it == 0) }
                val pending = vm.unsyncedCount()
                Small(if (pending == 0) "Toutes tes séances sont dans Santé Connect." else "$pending séance(s) pas encore envoyée(s).")
                Btn(
                    if (vm.hcBusy) "Envoi en cours…" else "Envoyer l'historique", Modifier.fillMaxWidth(), filled = false,
                    enabled = !vm.hcBusy && pending > 0, height = 48.dp,
                ) { vm.syncHealthConnect(null) }
                Btn("Revoir les autorisations", Modifier.fillMaxWidth(), filled = false, outline = Color.Transparent, onOutline = C.Accent, height = 44.dp) {
                    ask.launch(HealthConnect.PERMISSIONS)
                }
            }
        }
        if (vm.hcMessage.isNotBlank()) Small(vm.hcMessage, C.Ink)
    }
}

/** Pour déclarer une séance faite sans le téléphone. */
@Composable
private fun ManualButton(vm: AppViewModel) {
    Btn("Je l'ai faite sans le téléphone", Modifier.fillMaxWidth(), filled = false, outline = Color.Transparent, onOutline = C.Accent, height = 48.dp) {
        vm.open(Screen.ManualLog)
    }
}
