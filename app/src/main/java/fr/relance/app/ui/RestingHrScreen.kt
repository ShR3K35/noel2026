package fr.relance.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.relance.app.ble.Link
import fr.relance.app.core.Fmt
import fr.relance.app.core.RestingHr
import kotlinx.coroutines.delay

private enum class Phase { CONNECT, SIT, MEASURE, RESULT }

/** Mesure de la fréquence cardiaque de repos avec la ceinture : connexion, s'asseoir, 2 minutes d'écoute. */
@Composable
fun RestingHrScreen(vm: AppViewModel) {
    val profile = vm.state.profile ?: return
    val ble = vm.ble
    val link by ble.hrLink.collectAsState()
    val hr by ble.hr.collectAsState()
    var granted by remember { mutableStateOf(ble.hasPermissions()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.all { it }
        if (granted) vm.ensureHeartRate()
    }
    var phase by remember { mutableStateOf(Phase.CONNECT) }
    val samples = remember { mutableStateListOf<Int>() }
    var result by remember { mutableStateOf<RestingHr.Result?>(null) }
    var previous by remember { mutableIntStateOf(profile.restHr) }

    LaunchedEffect(Unit) { vm.ensureHeartRate() }
    // Dès que la ceinture envoie un battement, on passe à l'étape suivante.
    LaunchedEffect(link, hr) { if (phase == Phase.CONNECT && link == Link.CONNECTED && hr != null) phase = Phase.SIT }

    if (phase == Phase.MEASURE) {
        val view = LocalView.current
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        LaunchedEffect(Unit) {
            samples.clear()
            while (samples.size < RestingHr.DURATION_S) {
                delay(1000)
                samples += ble.hr.value ?: 0
            }
            val r = RestingHr.compute(samples)
            if (r is RestingHr.Result.Ok) {
                previous = vm.state.profile?.restHr ?: previous
                vm.updateProfile { it.copy(restHr = r.bpm) }
            }
            result = r
            phase = Phase.RESULT
        }
    }

    Column(Modifier.fillMaxSize().background(C.Ground).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Btn("‹ Profil", filled = false, outline = Color.Transparent, onOutline = C.Accent, height = 48.dp) { vm.back() }
            Title("Fréquence cardiaque de repos")
            when (phase) {
                Phase.CONNECT -> {
                    Body("Mets ta ceinture cardio, électrodes humidifiées. L'app s'y connecte.", C.Muted)
                    Card {
                        when {
                            profile.hrAddress.isBlank() -> {
                                Strong("Aucune ceinture associée")
                                Body("Associe d'abord ta ceinture dans Capteurs.")
                            }
                            !granted -> {
                                Strong("Autorisation Bluetooth nécessaire")
                                Body("L'app en a besoin pour écouter ta ceinture.")
                            }
                            else -> {
                                Strong("${profile.hrName} · ${link.label}")
                                Body(
                                    if (link == Link.CONNECTED) "Connectée. En attente des premiers battements…"
                                    else "Recherche de la ceinture… Elle se réveille au contact de la peau."
                                )
                            }
                        }
                    }
                }
                Phase.SIT -> {
                    Card(color = C.Soft) {
                        Strong("Assieds-toi confortablement")
                        Body("Dos appuyé, pieds au sol, sans parler ni bouger. Respire normalement. Le plus fiable : le matin, avant le café, ou après quelques minutes de calme.")
                    }
                    LiveHr(hr)
                    Small("La mesure dure 2 minutes. Garde le téléphone près de toi.")
                }
                Phase.MEASURE -> {
                    val left = RestingHr.DURATION_S - samples.size
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Fmt.clock(left), fontSize = 64.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                        Small(if (samples.size < RestingHr.SETTLE_S) "Installe-toi, la mesure commence…" else "Écoute en cours, reste immobile.")
                    }
                    ProgressBar(samples.size.toFloat() / RestingHr.DURATION_S)
                    LiveHr(hr)
                    val silent = samples.takeLast(10).let { it.size == 10 && it.all { v -> v == 0 } }
                    if (silent || link != Link.CONNECTED) Small("Plus de signal de la ceinture : humidifie les électrodes et vérifie qu'elle est bien en place.", C.Ink)
                }
                Phase.RESULT -> when (val r = result) {
                    is RestingHr.Result.Ok -> {
                        Card {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${r.bpm}", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = C.Accent)
                                Text(" bpm au repos", fontSize = 16.sp, color = C.Muted, modifier = Modifier.padding(bottom = 10.dp))
                            }
                            Body("Enregistré dans ton profil (avant : $previous bpm). Tes zones cardiaques sont recalculées.")
                        }
                        Small("Elle baisse souvent de quelques battements quand la forme revient : refais la mesure dans un mois.")
                    }
                    is RestingHr.Result.Implausible -> Card {
                        Strong("Mesure à refaire : ${r.bpm} bpm")
                        Body("C'est inhabituel au repos. Repose-toi quelques minutes au calme, puis recommence. Ton profil n'a pas été modifié.")
                    }
                    else -> Card {
                        Strong("Pas assez de mesures")
                        Body("La ceinture a trop souvent perdu le signal. Humidifie les électrodes, resserre la sangle et recommence. Ton profil n'a pas été modifié.")
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        when (phase) {
            Phase.CONNECT -> when {
                profile.hrAddress.isBlank() -> Btn("Associer ma ceinture", Modifier.fillMaxWidth()) { vm.back(); vm.open(Screen.Sensors) }
                !granted -> Btn("Autoriser le Bluetooth", Modifier.fillMaxWidth()) { ask.launch(ble.requiredPermissions()) }
                else -> Btn("En attente de la ceinture…", Modifier.fillMaxWidth(), enabled = false) {}
            }
            Phase.SIT -> Btn("Je suis assis, commencer", Modifier.fillMaxWidth(), enabled = hr != null) { phase = Phase.MEASURE }
            Phase.MEASURE -> Btn("Annuler la mesure", Modifier.fillMaxWidth(), filled = false) { samples.clear(); phase = Phase.SIT }
            Phase.RESULT -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Refaire", Modifier.weight(1f), filled = false) { result = null; phase = Phase.SIT }
                Btn("Terminé", Modifier.weight(1.4f)) { vm.back() }
            }
        }
    }
}

@Composable
private fun LiveHr(hr: Int?) {
    Card {
        Small("Fréquence cardiaque en direct")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(hr?.toString() ?: "—", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCC6A1F))
            Text(" bpm", fontSize = 15.sp, color = C.Muted, modifier = Modifier.padding(bottom = 7.dp))
        }
    }
}
