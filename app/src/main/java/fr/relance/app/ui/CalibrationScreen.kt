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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.relance.app.ble.Link
import fr.relance.app.core.Calibration
import fr.relance.app.core.Fmt
import kotlinx.coroutines.delay
import kotlin.math.abs

private fun mm(v: Double): String = String.format(java.util.Locale.FRANCE, "%.1f", v)

/**
 * Étalonnage du boîtier, bande à vide : on règle la console sur trois vitesses de marche
 * et on compare à ce que mesure le boîtier.
 */
@Composable
fun CalibrationScreen(vm: AppViewModel) {
    val ble = vm.ble
    val link by ble.treadmillLink.collectAsState()
    val speed by ble.speed.collectAsState()
    val current by ble.calibrationUm.collectAsState()
    val saved by ble.calibrationSaved.collectAsState()
    val points = remember { mutableStateListOf<Calibration.Point>() }
    val recent = remember { mutableStateListOf<Double>() }
    var sent by remember { mutableStateOf<Long?>(null) }

    // Une mesure par seconde, pour juger de la stabilité.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            recent += ble.speed.value ?: 0.0
            if (recent.size > 10) recent.removeAt(0)
        }
    }

    val step = points.size
    val done = step >= Calibration.STEPS.size
    val newUm = current?.let { Calibration.newDistance(it, points) }

    Column(Modifier.fillMaxSize().background(C.Ground).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Btn("‹ Capteurs", filled = false, outline = androidx.compose.ui.graphics.Color.Transparent, onOutline = C.Accent, height = 48.dp) { vm.back() }
            Title("Étalonner le tapis")
            when {
                link != Link.CONNECTED -> {
                    Body("Le boîtier n'est pas connecté. Allume-le et reviens sur cet écran.", C.Muted)
                    return@Column
                }
                current == null -> {
                    Body("Ce tapis ne propose pas de réglage d'étalonnage : il mesure sa vitesse lui-même. Rien à faire ici.", C.Muted)
                    return@Column
                }
            }
            val um = current ?: return@Column
            Card(color = C.Soft) {
                Strong("Sécurité d'abord")
                Body("Reste debout sur les repose-pieds de chaque côté, la bande tourne à vide. Clé de sécurité accrochée.")
            }
            Small("Réglage actuel du boîtier : ${mm(um / 1000.0)} mm par tour (rouleau de ${mm(Calibration.diameterMm(um))} mm).")

            if (!done) {
                val target = Calibration.STEPS[step]
                Label("Palier ${step + 1} sur ${Calibration.STEPS.size}")
                Card {
                    Strong("Règle la console sur ${Fmt.kmh(target)} km/h")
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(Fmt.kmh(speed ?: 0.0), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                        Text(" km/h mesurés par le boîtier", fontSize = 14.sp, color = C.Muted, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    val stable = Calibration.isStable(recent)
                    Small(
                        when {
                            (speed ?: 0.0) < 0.5 -> "Aucun tour de rouleau détecté. Vérifie l'aimant et le capteur (la LED du boîtier doit clignoter)."
                            !stable -> "Attends que la mesure se stabilise…"
                            abs((speed ?: 0.0) - target) / target > 0.5 -> "Mesure très éloignée de la console : vérifie la vitesse réglée."
                            else -> "Mesure stable. Valide quand la console affiche bien ${Fmt.kmh(target)} km/h."
                        }
                    )
                }
            } else {
                Label("Résultat")
                Card {
                    val err = Calibration.errorPercent(points)
                    Strong("Écart avant correction : ${if (err >= 0) "+" else ""}${mm(err)} %")
                    points.forEach { Small("Console ${Fmt.kmh(it.consoleKmh)} km/h · boîtier ${Fmt.kmh(it.measuredKmh)} km/h") }
                    if (newUm == null) Body("Les mesures sont incohérentes : recommence en vérifiant la vitesse affichée par la console.", C.Ink)
                    else Body("Nouveau réglage : ${mm(newUm / 1000.0)} mm par tour.")
                }
                when {
                    sent != null && saved == true && current == sent -> Card(color = C.Soft) { Strong("Étalonnage enregistré dans le boîtier.") }
                    sent != null && saved == false -> Body("L'envoi a échoué. Rapproche le téléphone du boîtier et réessaie.", C.Ink)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (link == Link.CONNECTED && current != null) {
            if (!done) {
                Btn("Valider ce palier", Modifier.fillMaxWidth(), enabled = Calibration.isStable(recent)) {
                    points += Calibration.Point(Calibration.STEPS[step], recent.takeLast(5).average())
                    recent.clear()
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Recommencer", Modifier.weight(1f), filled = false) { points.clear(); recent.clear(); sent = null }
                    Btn("Enregistrer", Modifier.weight(1.4f), enabled = newUm != null && sent == null) {
                        val um = newUm ?: return@Btn
                        if (ble.writeCalibration(um)) sent = um else ble.calibrationSaved.value = false
                    }
                }
            }
        }
    }
}
