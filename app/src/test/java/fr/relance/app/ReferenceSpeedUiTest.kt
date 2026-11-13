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
import fr.relance.app.core.Feedback
import fr.relance.app.core.Level
import fr.relance.app.core.Profile
import fr.relance.app.core.ProgramType
import fr.relance.app.core.SessionKind
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

/** Vitesse de référence : réglage à la main, puis test de marche relancé depuis le profil. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ReferenceSpeedUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun manualThenRetest() {
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        rule.runOnIdle {
            vm.completeOnboarding(Profile(vr = 5.8, level = Level.N2), ProgramType.RUN_30, false)
            vm.tab = 4
        }
        rule.onNodeWithText("Vitesse de référence (ancien calcul)").performScrollTo()
        // Réglage à la main : + puis +.
        rule.runOnIdle { vm.setReferenceSpeed(vm.state.profile!!.vr + 0.1) }
        rule.runOnIdle { vm.setReferenceSpeed(vm.state.profile!!.vr + 0.1) }
        assertEquals(6.0, vm.state.profile!!.vr, 0.001)
        rule.onNodeWithText("Refaire le test de marche (12 min)").performScrollTo()
        rule.onRoot().captureRoboImage("build/shots/80-vitesse-reference.png")

        // Test relancé : 12 min, le tapis mesure 6,4 km/h pendant le bloc de test.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Refaire le test de marche (12 min)").performClick()
        rule.mainClock.advanceTimeBy(500)
        assertEquals(Screen.Session, vm.stack.last())
        val e = vm.engine!!
        assertEquals(SessionKind.TEST, e.session.kind)
        assertEquals(12 * 60, e.totalSeconds)
        while (!e.finished) e.tick(if (e.current.kind == fr.relance.app.core.BlockKind.TEST) 6.4 else 4.5, null, null)
        vm.endSession()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText("Vitesse mesurée pendant le test : 6,4 km/h").assertExists()
        rule.onRoot().captureRoboImage("build/shots/81-test-bilan.png")
        rule.runOnIdle { vm.submitFeedback(Feedback(rpe = 5)) }
        rule.waitForIdle()
        assertEquals(6.4, vm.state.profile!!.vr, 0.001)
        // Le programme en cours n'a pas bougé.
        assertEquals(0, vm.state.program!!.sessionsDone)
        assertEquals(0, vm.state.program!!.stepIndex)
        val s = vm.state.sessions.single()
        assertEquals("", s.programId)
        assertTrue(s.coachMessage.contains("6,0 → 6,4"))
        rule.onRoot().captureRoboImage("build/shots/82-test-fiche.png")
    }
}
