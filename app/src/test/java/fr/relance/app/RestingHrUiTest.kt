package fr.relance.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import fr.relance.app.ble.Link
import fr.relance.app.core.Profile
import fr.relance.app.core.ProgramType
import fr.relance.app.ui.AppViewModel
import fr.relance.app.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Mesure de la FC de repos avec une ceinture simulée, depuis le bouton du profil. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class RestingHrUiTest {
    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(object : org.junit.rules.ExternalResource() {
            override fun before() {
                org.robolectric.RuntimeEnvironment.getApplication().filesDir.listFiles()?.forEach { it.deleteRecursively() }
            }
        })
        .around(rule)

    @org.junit.After
    fun cleanUp() {
        Thread.sleep(500)
        org.robolectric.RuntimeEnvironment.getApplication().filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun measureRestingHeartRate() {
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        rule.runOnIdle {
            vm.completeOnboarding(Profile(hrAddress = "11:22:33:44:55:66", hrName = "HRM Belt"), ProgramType.RUN_30, false)
            vm.tab = 4
        }
        rule.onNodeWithText("Mesurer avec ma ceinture").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Screen.RestingHr, vm.stack.last())
        rule.onRoot().captureRoboImage("build/shots/40-repos-connexion.png")

        rule.mainClock.autoAdvance = false
        rule.runOnIdle { vm.ble.hrLink.value = Link.CONNECTED; vm.ble.hr.value = 72 }
        rule.mainClock.advanceTimeBy(500)
        rule.onRoot().captureRoboImage("build/shots/41-repos-assis.png")
        rule.onNodeWithText("Je suis assis, commencer").performClick()
        // La ceinture descend de 72 à 58 bpm pendant les deux minutes.
        for (s in 1..121) {
            rule.runOnIdle { vm.ble.hr.value = if (s < 30) 72 - s / 3 else 58 + (s % 3) }
            rule.mainClock.advanceTimeBy(1000)
            if (s == 45) rule.onRoot().captureRoboImage("build/shots/42-repos-mesure.png")
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText("Enregistré dans ton profil", substring = true).assertExists()
        rule.onRoot().captureRoboImage("build/shots/43-repos-resultat.png")
        assertEquals(59, vm.state.profile!!.restHr)
    }
}
