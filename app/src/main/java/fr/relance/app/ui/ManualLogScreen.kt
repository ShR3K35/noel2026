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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.relance.app.core.Feedback
import fr.relance.app.core.Fmt
import fr.relance.app.core.ManualEntry
import fr.relance.app.core.Pain
import fr.relance.app.core.Physio
import fr.relance.app.core.SessionKind
import kotlin.math.roundToInt

/** Déclarer une séance faite sans le téléphone : quand, combien de temps, et le ressenti. */
@Composable
fun ManualLogScreen(vm: AppViewModel) {
    val profile = vm.state.profile ?: return
    val days = remember { vm.manualDays() }
    if (days.isEmpty()) {
        vm.back()
        return
    }
    val today = vm.today()
    var day by remember { mutableStateOf(days.first()) }
    val planned = vm.plannedFor(day) ?: return
    var moment by remember { mutableIntStateOf(2) }
    var minutes by remember(planned.minutes) { mutableIntStateOf(planned.minutes) }
    var knowDistance by remember { mutableStateOf(false) }
    val estimated = ManualEntry.estimatedDistance(planned, minutes * 60)
    var distanceHm by remember { mutableIntStateOf(0) } // centaines de mètres
    var rpe by remember { mutableIntStateOf(0) }
    var pain by remember { mutableStateOf(Pain.NONE) }
    var completed by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var testSpeed by remember { mutableStateOf(profile.vr) }
    val isTest = planned.kind == SessionKind.TEST

    Column(Modifier.fillMaxSize().background(C.Ground).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Btn("‹ Retour", filled = false, outline = Color.Transparent, onOutline = C.Accent, height = 48.dp) { vm.back() }
            Title("Séance faite sans le téléphone")
            Body("Pas de souci : indique ce que tu as fait, le programme s'adapte comme après une séance guidée.", C.Muted)
            Card(padding = 14.dp) {
                Label("Séance prévue")
                Strong("${planned.title} · ${planned.minutes} min")
                Small(planned.detail)
            }

            Strong("Quand ?")
            days.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { d ->
                        val label = when (today - d) { 0L -> "Aujourd'hui"; 1L -> "Hier"; else -> Dates.short(d) }
                        Chip(label, d == day, Modifier.weight(1f)) { day = d }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            ChipRow(ManualEntry.MOMENTS.map { it.first }, moment) { moment = it }

            Strong("Durée")
            Stepper("$minutes", "min", { minutes = (minutes - 1).coerceAtLeast(5) }, { minutes = (minutes + 1).coerceAtMost(180) })

            Strong("Distance")
            ChipRow(listOf("Je ne sais pas", "Je la connais"), if (knowDistance) 1 else 0) {
                knowDistance = it == 1
                if (knowDistance && distanceHm == 0) distanceHm = (estimated / 100).roundToInt().coerceAtLeast(1)
            }
            if (knowDistance) {
                Stepper(Fmt.km(distanceHm * 100.0).dropLast(1), "km", { distanceHm = (distanceHm - 1).coerceAtLeast(1) }, { distanceHm = (distanceHm + 1).coerceAtMost(300) })
                Small("Celle affichée par la console à la fin de la séance.")
            } else {
                Small("Estimée d'après la séance prévue : ${Fmt.km(estimated)} km.")
            }

            if (isTest) Card {
                Strong("Quelle vitesse as-tu tenue pendant les 6 min du test ?")
                Stepper(Fmt.kmh(testSpeed), "km/h", { testSpeed = Physio.round1((testSpeed - 0.1).coerceAtLeast(3.0)) }, { testSpeed = Physio.round1((testSpeed + 0.1).coerceAtMost(9.0)) })
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Strong("Effort ressenti"); Small("1 très facile · 10 maximal") }
            ChipRow((1..5).map { "$it" }, rpe - 1) { rpe = it + 1 }
            ChipRow((6..10).map { "$it" }, rpe - 6) { rpe = it + 6 }
            Strong("Une douleur ?")
            ChipRow(Pain.values().map { it.label }, pain.ordinal) { pain = Pain.values()[it] }
            Strong("La séance")
            ChipRow(listOf("Faite en entier", "Écourtée"), if (completed) 0 else 1) { completed = it == 0 }
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(200) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Une note (facultatif)") }, singleLine = true,
            )
        }
        Spacer(Modifier.height(12.dp))
        Btn(if (rpe == 0) "Indique ton effort ressenti" else "Enregistrer la séance", Modifier.fillMaxWidth(), enabled = rpe > 0) {
            vm.logManual(
                day, ManualEntry.MOMENTS[moment].second, minutes,
                if (knowDistance) distanceHm * 100.0 else null,
                Feedback(rpe, pain, completed, note.trim(), if (isTest) testSpeed else 0.0),
            )
        }
    }
}
