package fr.relance.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.relance.app.ble.Link
import fr.relance.app.core.Export
import fr.relance.app.core.Fmt
import fr.relance.app.core.Sample
import kotlin.math.max

@Composable
private fun LineChart(samples: List<Sample>, value: (Sample) -> Float, color: Color, description: String) {
    val points = samples.filter { value(it) > 0f }
    Canvas(Modifier.fillMaxWidth().height(90.dp).semantics { contentDescription = description }) {
        drawLine(C.Track, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2f)
        if (points.size < 2) return@Canvas
        val maxT = max(1, samples.last().t).toFloat()
        val top = points.maxOf(value) * 1.1f
        val low = points.minOf(value) * 0.8f
        val span = max(0.1f, top - low)
        // Un point par pixel suffit : on saute les mesures intermédiaires.
        val stride = max(1, points.size / size.width.toInt().coerceAtLeast(1))
        val path = Path()
        points.filterIndexed { i, _ -> i % stride == 0 }.forEachIndexed { i, s ->
            val x = s.t / maxT * size.width
            val y = size.height - (value(s) - low) / span * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Fiche d'une séance : statistiques, courbes, zones cardiaques et export. */
@Composable
fun DetailScreen(vm: AppViewModel, id: String) {
    val s = vm.session(id)
    if (s == null) {
        vm.back()
        return
    }
    val samples = remember(id) { vm.samples(id) }
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf("") }
    var exported by remember { mutableStateOf("") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(pending.toByteArray()) }
                exported = "Fichier enregistré."
            } catch (e: Exception) {
                exported = "L'enregistrement a échoué : ${e.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize().background(C.Ground).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Btn("‹ Historique", filled = false, outline = Color.Transparent, onOutline = C.Accent, height = 48.dp) { vm.back() }
            Title(s.title)
            Small(
                "${Dates.long(s.day)}" + (if (s.programLabel.isNotBlank()) " · ${s.programLabel}, semaine ${s.week + 1}" else "") +
                    " · effort ${s.rpe} sur 10" + if (!s.completed) " · écourtée" else ""
            )
            if (s.coachMessage.isNotBlank()) Card(color = C.Soft) { Label("Le mot du coach", C.Accent); Body(s.coachMessage) }
            Card(padding = 14.dp) {
                Row(Modifier.fillMaxWidth()) {
                    Stat(Fmt.clock(s.durationSec), "durée", Modifier.weight(1f))
                    Stat("${Fmt.km(s.distanceM)} km", "distance", Modifier.weight(1f))
                    Stat(Fmt.pace(s.avgSpeed), "allure moy. /km", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Stat("${Fmt.kmh(s.avgSpeed)} km/h", "vitesse moy.", Modifier.weight(1f))
                    Stat(if (s.avgHr > 0) "${s.avgHr} / ${s.maxHr}" else "—", "FC moy. / max", Modifier.weight(1f))
                    Stat("${s.calories} kcal", "calories (est.)", Modifier.weight(1f))
                }
                Small(
                    (if (s.speedMeasured) "Vitesse et distance mesurées par le tapis." else "Tapis non connecté : vitesse et distance estimées d'après les consignes.") +
                        if (s.elevationM >= 1) " Dénivelé équivalent : ${s.elevationM.toInt()} m." else ""
                )
            }
            Card {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Strong(if (s.speedMeasured) "Vitesse du tapis" else "Vitesse demandée")
                    if (s.runBlocksPlanned > 0) Small("${s.runBlocksDone} bloc(s) de course sur ${s.runBlocksPlanned}")
                }
                LineChart(samples, { it.speed }, C.Accent, "Courbe de vitesse, maximum ${Fmt.kmh(s.maxSpeed)} km/h")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Small("0 min"); Small("max ${Fmt.kmh(s.maxSpeed)} km/h"); Small(Fmt.duration(s.durationSec)) }
            }
            if (s.avgHr > 0) Card {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Strong("Fréquence cardiaque")
                    if (s.recovery1Min > 0) Small("récup. 1 min : −${s.recovery1Min} bpm")
                }
                LineChart(samples, { it.hr.toFloat() }, Color(0xFFCC6A1F), "Courbe de fréquence cardiaque, maximum ${s.maxHr} battements par minute")
                val total = max(1, s.zoneSeconds.sum())
                Row(Modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    s.zoneSeconds.forEachIndexed { i, sec ->
                        if (sec > 0) Box(Modifier.weight(sec.toFloat() / total).height(14.dp).clip(RoundedCornerShape(4.dp)).background(C.Zones[i]))
                    }
                }
                Small(s.zoneSeconds.mapIndexedNotNull { i, sec -> if (sec >= 30) "Z${i + 1} · ${(sec + 30) / 60} min" else null }.joinToString("   "))
            }
            if (s.note.isNotBlank()) Card { Label("Ta note"); Body(s.note) }
            if (exported.isNotBlank()) Small(exported, C.Ink)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Exporter en TCX", Modifier.weight(1.6f), enabled = samples.isNotEmpty()) {
                pending = Export.tcx(s, samples); saver.launch(Export.fileName(s, "tcx"))
            }
            Btn("CSV", Modifier.weight(1f), filled = false, enabled = samples.isNotEmpty()) {
                pending = Export.csv(samples); saver.launch(Export.fileName(s, "csv"))
            }
        }
    }
}

/** Association de la ceinture cardio et du tapis (ou du boîtier ESP32) en Bluetooth. */
@Composable
fun SensorsScreen(vm: AppViewModel) {
    val profile = vm.state.profile ?: return
    val ble = vm.ble
    val hrLink by ble.hrLink.collectAsState()
    val tmLink by ble.treadmillLink.collectAsState()
    val hr by ble.hr.collectAsState()
    val speed by ble.speed.collectAsState()
    val incline by ble.incline.collectAsState()
    val found by ble.found.collectAsState()
    val scanning by ble.scanning.collectAsState()
    var granted by remember { mutableStateOf(ble.hasPermissions()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
        if (granted) {
            vm.reconnectSensors()
            ble.startScan()
        }
    }
    DisposableEffect(Unit) { onDispose { ble.stopScan() } }

    Column(Modifier.fillMaxSize().background(C.Ground).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Btn("‹ Retour", filled = false, outline = Color.Transparent, onOutline = C.Accent, height = 48.dp) { vm.back() }
            Title("Capteurs")
            Body("Facultatifs : sans eux, l'app se guide au ressenti et aux vitesses demandées.", C.Muted)
            Card {
                Strong("Ceinture cardio")
                if (profile.hrAddress.isBlank()) Small("Aucune ceinture associée. Toute ceinture Bluetooth standard convient.")
                else {
                    Small("${profile.hrName} · ${hrLink.label}" + if (hr != null) " · $hr bpm" else "")
                    Btn("Oublier cette ceinture", filled = false, height = 48.dp) { vm.forgetHeartRate() }
                }
            }
            Card {
                Strong("Tapis")
                if (profile.treadmillAddress.isBlank()) Small("Aucun tapis associé. Compatible avec un boîtier ESP32 ou un tapis qui parle le standard FTMS.")
                else {
                    Small(
                        "${profile.treadmillName} · ${tmLink.label}" +
                            (if (speed != null) " · ${Fmt.kmh(speed ?: 0.0)} km/h" else "") +
                            (if (incline != null) " · ${Fmt.kmh(incline ?: 0.0)} %" else "")
                    )
                    val calib by ble.calibrationUm.collectAsState()
                    if (tmLink == Link.CONNECTED && calib != null)
                        Btn("Étalonner le tapis", filled = true, height = 48.dp) { vm.open(Screen.Calibration) }
                    Btn("Oublier ce tapis", filled = false, height = 48.dp) { vm.forgetTreadmill() }
                }
                Small("Lecture seule : l'app ne commande jamais le tapis.")
            }
            Label("Appareils à proximité")
            if (!granted) Body("L'app a besoin de l'autorisation Bluetooth pour chercher tes capteurs.", C.Muted)
            if (scanning && found.isEmpty()) Body("Recherche en cours… Mets la ceinture (électrodes humides) et allume le tapis.", C.Muted)
            found.forEach { d ->
                Card(padding = 14.dp, onClick = { vm.pair(d) }) {
                    Strong(d.name)
                    Small((if (d.isTreadmill) "Tapis" else "Ceinture cardio") + " · touche pour associer")
                }
            }
            if (hrLink == Link.CONNECTING || tmLink == Link.CONNECTING) Small("Un capteur associé mais éteint sera rattrapé dès qu'il se rallume.")
        }
        Spacer(Modifier.height(10.dp))
        Btn(if (scanning) "Arrêter la recherche" else "Chercher mes capteurs", Modifier.fillMaxWidth()) {
            when {
                !granted -> ask.launch(ble.requiredPermissions())
                scanning -> ble.stopScan()
                else -> ble.startScan()
            }
        }
    }
}
