package fr.relance.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import fr.relance.app.core.Level
import fr.relance.app.core.Profile
import fr.relance.app.core.ProgramType
import fr.relance.app.ui.AppViewModel
import fr.relance.app.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Déclarer une séance faite sans le téléphone, depuis l'écran Aujourd'hui. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ManualLogUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun logASessionDoneWithoutThePhone() {
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        // Tous les jours disponibles : il y a une séance aujourd'hui, quel que soit le jour du test.
        rule.runOnIdle {
            vm.completeOnboarding(Profile(vr = 5.8, level = Level.N3, days = (1..7).toSet()), ProgramType.RUN_30, false)
        }
        rule.onNodeWithText("Je l'ai faite sans le téléphone").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Screen.ManualLog, vm.stack.last())
        rule.onRoot().captureRoboImage("build/shots/60-saisie-manuelle.png")
        rule.onNodeWithText("Je la connais").performScrollTo().performClick()
        rule.onAllNodesWithText("5").onFirst().performScrollTo().performClick()
        rule.onRoot().captureRoboImage("build/shots/61-saisie-manuelle-remplie.png")
        rule.onNodeWithText("Enregistrer la séance").performClick()
        rule.waitForIdle()
        assertTrue(vm.stack.last() is Screen.Detail)
        val s = vm.state.sessions.single()
        assertTrue(s.manual)
        assertEquals(5, s.rpe)
        assertTrue(s.distanceM > 1000)
        assertEquals(1, vm.state.program!!.sessionsDone)
        rule.onRoot().captureRoboImage("build/shots/62-saisie-manuelle-fiche.png")
    }
}
