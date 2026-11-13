package fr.relance.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.relance.app.ui.AppViewModel
import fr.relance.app.ui.BilanScreen
import fr.relance.app.ui.C
import fr.relance.app.ui.CalibrationScreen
import fr.relance.app.ui.CatalogueScreen
import fr.relance.app.ui.DetailScreen
import fr.relance.app.ui.HomeScreen
import fr.relance.app.ui.ManualLogScreen
import fr.relance.app.ui.OnboardingScreen
import fr.relance.app.ui.PlannedScreen
import fr.relance.app.ui.RestingHrScreen
import fr.relance.app.ui.Screen
import fr.relance.app.ui.SensorsScreen
import fr.relance.app.ui.SessionScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = C.Accent, surface = C.Card, background = C.Ground)) {
                val vm: AppViewModel = viewModel()
                val screen = vm.stack.lastOrNull() ?: Screen.Home
                val night = screen == Screen.Session || screen == Screen.PaceRun
                BackHandler(enabled = vm.stack.size > 1) { vm.back() }
                Box(Modifier.fillMaxSize().background(if (night) C.Night else C.Ground).safeDrawingPadding()) {
                    when (screen) {
                        Screen.Onboarding -> OnboardingScreen(vm)
                        Screen.Home -> HomeScreen(vm)
                        Screen.Catalogue -> CatalogueScreen(vm)
                        Screen.Session -> SessionScreen(vm)
                        Screen.Bilan -> BilanScreen(vm)
                        is Screen.Detail -> DetailScreen(vm, screen.id)
                        Screen.Sensors -> SensorsScreen(vm)
                        Screen.Calibration -> CalibrationScreen(vm)
                        Screen.RestingHr -> RestingHrScreen(vm)
                        Screen.ManualLog -> ManualLogScreen(vm)
                        is Screen.Planned -> PlannedScreen(vm, screen.index)
                        Screen.Paces -> fr.relance.app.ui.PacesScreen(vm)
                        Screen.PacePrep -> fr.relance.app.ui.PacePrepScreen(vm)
                        Screen.PaceRun -> fr.relance.app.ui.PaceRunScreen(vm)
                        Screen.PaceResult -> fr.relance.app.ui.PaceResultScreen(vm)
                    }
                }
            }
        }
    }

    /** Passage en arrière-plan (appel, autre app, écran verrouillé) : un test de calibration se met en pause. */
    override fun onStop() {
        super.onStop()
        androidx.lifecycle.ViewModelProvider(this)[AppViewModel::class.java].onBackground()
    }
}
