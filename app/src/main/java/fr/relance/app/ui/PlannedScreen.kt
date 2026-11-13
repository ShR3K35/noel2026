package fr.relance.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.relance.app.core.BlockKind
import fr.relance.app.core.Fmt
import fr.relance.app.core.ManualEntry
import fr.relance.app.core.Physio
import fr.relance.app.core.SessionKind
import kotlin.math.roundToInt

/** Pourquoi cette séance, en une phrase de coach. */
fun purposeOf(kind: SessionKind): String = when (kind) {
    SessionKind.WALK -> "Marche active : la base de l'endurance. Tu dois pouvoir parler tout du long."
    SessionKind.HILL -> "Marche en côte : le cœur travaille davantage, sans les chocs de la course."
    SessionKind.INTERVALS -> "Marche / course : de courts passages de trot, entrecoupés de marche pour récupérer. La durée de trot augmente semaine après semaine."
    SessionKind.CONTINUOUS -> "Course continue, à allure facile : tu dois pouvoir dire une phrase sans être essoufflé."
    SessionKind.RECOVERY -> "Récupération : marche lente. Elle compte comme une séance tenue."
    SessionKind.TEST -> "Test de forme : il mesure ta nouvelle vitesse de référence, qui recale toutes tes allures."
    SessionKind.PERF_INTERVALS -> "Fractionné : des passages rapides et courts, pour gagner en vitesse."
    SessionKind.PERF_TEMPO -> "Allure soutenue : un effort tenu, un peu en dessous de ta limite, pour repousser le seuil de fatigue."
}

/** Détail d'une séance du planning : blocs, vitesses, estimations, et la possibilité de la faire maintenant. */
@Composable
fun PlannedScreen(vm: AppViewModel, index: Int) {
    val s = vm.plan.getOrNull(index)
    val p = vm.state.program
    val profile = vm.state.profile
    if (s == null || p == null || profile == null) {
        vm.back()
        return
    }
    val today = vm.today()
    val first = index == 0
    val resting = p.restUntilDay > today && p.painCount > 0
    val doneToday = p.lastSessionDay == today
    val distance = ManualEntry.estimatedDistance(s, s.blocks.sumOf { it.seconds })
    val kcal = s.blocks.sumOf { Physio.kcalPerMin(it.speedKmh, it.inclinePct, profile.weightKg) * it.seconds / 60 }

    Column(Modifier.fillMaxSize().background(C.Ground).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Btn("‹ Retour", filled = false, outline = Color.Transparent, onOutline = C.Accent, height = 48.dp) { vm.back() }
            Small(
                "${Dates.long(s.day)} · semaine ${s.week + 1}" + (if (s.deload) " · semaine allégée" else "") +
                    if (index >= 1 && s.week > vm.plan.first().week + 1) " · prévisionnel" else ""
            )
            Title(s.title)
            Body(purposeOf(s.kind), C.Muted)
            Card(padding = 14.dp) {
                Row(Modifier.fillMaxWidth()) {
                    Stat("${s.minutes} min", "durée", Modifier.weight(1f))
                    Stat("~${Fmt.km(distance)} km", "distance", Modifier.weight(1f))
                    Stat("~${kcal.roundToInt()} kcal", "calories", Modifier.weight(1f))
                }
                if (s.inclinePct > 0) Small("Inclinaison ${Fmt.kmh(s.inclinePct).removeSuffix(",0")} % pour toute la séance : règle-la tapis à l'arrêt, avant de commencer.", C.Ink)
            }
            Label("Bloc par bloc")
            Card(padding = 14.dp) {
                var t = 0
                s.blocks.forEachIndexed { i, b ->
                    val fast = b.kind == BlockKind.RUN || b.kind == BlockKind.FAST
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(Fmt.clock(t), fontSize = 13.sp, color = C.Muted, modifier = Modifier.width(44.dp))
                        Text(
                            b.kind.label, fontSize = 15.sp, color = if (fast) C.Accent else C.Ink,
                            fontWeight = if (fast) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f),
                        )
                        Text(Fmt.duration(b.seconds), fontSize = 14.sp, color = C.Ink, modifier = Modifier.width(58.dp))
                        Text(
                            if (b.kind == BlockKind.TEST) "au ressenti" else "${Fmt.kmh(b.speedKmh)} km/h",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.width(82.dp),
                        )
                    }
                    t += b.seconds
                    if (i == s.blocks.size - 1) Small("Fin à ${Fmt.clock(t)}")
                }
            }
            if (!first) Card(color = C.Soft) {
                Body(
                    "Elle vient après ${if (index == 1) "la prochaine séance" else "les $index prochaines séances"}. " +
                        "Le programme avance pas à pas : fais d'abord celles-là, et cette séance arrivera."
                )
            }
            if (first && resting) Card(color = C.Soft) { Body("Repos après la douleur signalée : cette séance sera possible ${Dates.relative(p.restUntilDay, today)}.") }
            if (first && s.day > today && !resting) Small("Prévue ${Dates.relative(s.day, today)}. Si tu la fais maintenant, les séances suivantes avancent d'autant.")
        }
        if (first && !resting) {
            Spacer(Modifier.height(10.dp))
            if (doneToday) Small("Tu as déjà fait une séance aujourd'hui : celle-ci sera possible demain.")
            else {
                Btn(if (s.day == today) "Démarrer la séance" else "La faire maintenant", Modifier.fillMaxWidth()) { vm.startSession() }
                Spacer(Modifier.height(6.dp))
                Btn("Je l'ai faite sans le téléphone", Modifier.fillMaxWidth(), filled = false, height = 48.dp) { vm.open(Screen.ManualLog) }
            }
        }
    }
}
