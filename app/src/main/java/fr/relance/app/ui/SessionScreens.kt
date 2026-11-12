package fr.relance.app.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.relance.app.core.BlockKind
import fr.relance.app.core.Feedback
import fr.relance.app.core.Fmt
import fr.relance.app.core.Pain
import fr.relance.app.core.Physio
import fr.relance.app.core.SessionKind
import kotlin.math.abs

@Composable
private fun NightCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(C.NightCard).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun BigValue(label: String, value: String, unit: String, color: Color = Color.White) {
    Text(label, color = C.NightMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = 42.sp, fontWeight = FontWeight.Bold, lineHeight = 44.sp)
        Text(" $unit", color = C.NightMuted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 7.dp))
    }
}

/** Séance guidée : gros chiffres lisibles depuis le tapis, écran toujours allumé. */
@Composable
fun SessionScreen(vm: AppViewModel) {
    val e = vm.engine ?: return
    val profile = vm.state.profile ?: return
    @Suppress("UNUSED_VARIABLE") val redraw = vm.tick
    val hr by vm.ble.hr.collectAsState()
    val measured by vm.ble.speed.collectAsState()
    val incline by vm.ble.incline.collectAsState()

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val ctx = LocalContext.current
    val systemAnimations = remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    val block = e.current
    val speed = e.displaySpeed(measured)
    val state = Physio.runnerState(speed, profile)

    Column(
        Modifier.fillMaxSize().background(C.Night).padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Bloc ${e.index + 1} sur ${e.blocks.size}", color = C.NightMuted, fontSize = 14.sp)
            Text("${Fmt.clock(e.elapsed)} sur ${Fmt.clock(e.totalSeconds)}", color = C.NightMuted, fontSize = 14.sp)
        }
        Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            e.blocks.forEachIndexed { i, b ->
                Box(
                    Modifier.weight(b.seconds.toFloat().coerceAtLeast(1f)).height(8.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (i < e.index) C.Mint else if (i == e.index) Color.White else C.NightLine)
                )
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (e.paused) "EN PAUSE" else block.kind.label.uppercase(), color = if (e.paused) C.Orange else C.Mint,
                fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
            )
            Text(Fmt.clock(e.remainingInBlock), color = Color.White, fontSize = 84.sp, fontWeight = FontWeight.Bold, lineHeight = 88.sp)
            Text("restant sur ce bloc", color = C.NightMuted, fontSize = 15.sp)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF151C1F))) {
            Runner(speed, state, profile.runnerAnimation && systemAnimations, Modifier.fillMaxWidth().height(136.dp))
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(if (measured != null) "suit le tapis" else "suit la consigne", color = C.NightMuted, fontSize = 13.sp)
            }
        }
        val targetLabel = if (block.kind == BlockKind.TEST) "Repère de départ" else "Vitesse à régler"
        if (measured != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NightCard(Modifier.weight(1f)) { BigValue(targetLabel, Fmt.kmh(block.speedKmh), "km/h") }
                NightCard(Modifier.weight(1f)) {
                    val onTarget = block.kind == BlockKind.TEST || abs((measured ?: 0.0) - block.speedKmh) <= 0.3
                    BigValue("Vitesse du tapis", Fmt.kmh(measured ?: 0.0), "km/h", if (onTarget) C.Mint else C.Orange)
                }
            }
        } else {
            NightCard(Modifier.fillMaxWidth()) {
                BigValue(if (block.kind == BlockKind.TEST) targetLabel else "Vitesse demandée", Fmt.kmh(block.speedKmh), "km/h")
                Text(
                    if (block.kind == BlockKind.TEST) "Monte jusqu'à l'allure la plus vive où tu peux encore parler."
                    else "Tapis non connecté : règle cette vitesse sur la console.",
                    color = C.NightMuted, fontSize = 13.sp,
                )
            }
        }
        NightCard(Modifier.fillMaxWidth()) {
            val h = hr
            if (h != null) {
                val zone = Physio.zoneOf(h, profile)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$h", color = C.Orange, fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
                        Text(" bpm", color = C.NightMuted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Text("Zone $zone", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (z in 1..5) Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(if (z == zone) C.Orange else C.NightLine))
                }
                if (h >= Physio.maxHr(profile) * 0.9) Text("Cœur très haut : ralentis.", color = C.Orange, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val inc = incline ?: block.inclinePct
                Text("Inclinaison ${Fmt.kmh(inc).removeSuffix(",0")} %", color = C.NightMuted, fontSize = 13.sp)
                Text("${Fmt.km(e.distanceM)} km" + if (h == null) " · pas de ceinture cardio" else "", color = C.NightMuted, fontSize = 13.sp)
            }
        }
        e.next?.let { n ->
            Row(
                Modifier.fillMaxWidth().border(1.5.dp, C.NightLine, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ensuite", color = C.NightMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("${n.kind.label} ${Fmt.duration(n.seconds)} à ${Fmt.kmh(n.speedKmh)} km/h", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Trop dur", Modifier.weight(1f), filled = false, outline = Color(0xFF5C6A6E), onOutline = Color.White, height = 56.dp) { vm.easeOff() }
            Btn(if (e.paused) "Reprendre" else "Pause", Modifier.weight(1.6f), fill = Color.White, onFill = C.Night, height = 56.dp) { vm.togglePause() }
            Btn("Terminer", Modifier.weight(1f), filled = false, outline = Color(0xFF5C6A6E), onOutline = Color.White, height = 56.dp) { vm.endSession() }
        }
    }
}

/** Trois questions après la séance : c'est ce qui règle la suite. */
@Composable
fun BilanScreen(vm: AppViewModel) {
    val e = vm.engine ?: return
    val profile = vm.state.profile ?: return
    val whole = e.elapsed >= e.session.blocks.sumOf { it.seconds } - 5
    var rpe by remember { mutableIntStateOf(0) }
    var pain by remember { mutableStateOf(Pain.NONE) }
    var completed by remember { mutableStateOf(whole) }
    var note by remember { mutableStateOf("") }
    val isTest = e.session.kind == SessionKind.TEST
    val measuredTest = e.measuredTestSpeed()
    var testSpeed by remember { mutableStateOf(profile.vr) }
    val hrs = e.samples.filter { it.hr > 0 }

    Column(Modifier.fillMaxSize().background(C.Ground).padding(horizontal = 20.dp, vertical = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Title(if (whole) "Séance tenue" else "Séance arrêtée")
            Body("Trois questions, dix secondes. C'est ce qui règle la suite.", C.Muted)
            Card(padding = 14.dp) {
                Row(Modifier.fillMaxWidth()) {
                    Stat(Fmt.clock(e.elapsed), "durée", Modifier.weight(1f))
                    Stat("${Fmt.km(e.distanceM)} km", "distance", Modifier.weight(1f))
                    Stat(if (hrs.isEmpty()) "—" else "${hrs.sumOf { it.hr } / hrs.size} bpm", "FC moyenne", Modifier.weight(1f))
                }
            }
            if (isTest) Card {
                if (measuredTest >= 3.0) {
                    Strong("Vitesse mesurée pendant le test : ${Fmt.kmh(measuredTest)} km/h")
                } else {
                    Strong("Quelle vitesse as-tu tenue pendant les 6 min ?")
                    Stepper(Fmt.kmh(testSpeed), "km/h", { testSpeed = Physio.round1((testSpeed - 0.1).coerceAtLeast(3.0)) }, { testSpeed = Physio.round1((testSpeed + 0.1).coerceAtMost(9.0)) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Strong("Effort ressenti"); Small("1 très facile · 10 maximal") }
            ChipRow((1..5).map { "$it" }, rpe - 1) { rpe = it + 1 }
            ChipRow((6..10).map { "$it" }, rpe - 6) { rpe = it + 6 }
            Strong("Une douleur ?")
            ChipRow(Pain.values().map { it.label }, pain.ordinal) { pain = Pain.values()[it] }
            if (pain == Pain.PAIN) Small("Douleur dans la poitrine, vertige, malaise : ne reprends pas sans avis médical.", C.Ink)
            Strong("La séance")
            ChipRow(listOf("Faite en entier", "Écourtée"), if (completed) 0 else 1) { completed = it == 0 }
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(200) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Une note (facultatif)") }, singleLine = true,
            )
        }
        Spacer(Modifier.height(12.dp))
        Btn(if (rpe == 0) "Indique ton effort ressenti" else "Enregistrer", Modifier.fillMaxWidth(), enabled = rpe > 0) {
            vm.submitFeedback(Feedback(rpe, pain, completed, note.trim(), if (isTest) testSpeed else 0.0))
        }
        Spacer(Modifier.height(6.dp))
        Btn("Ne pas enregistrer cette séance", Modifier.fillMaxWidth(), filled = false, outline = Color.Transparent, onOutline = C.Muted, height = 48.dp) { vm.discardSession() }
    }
}
