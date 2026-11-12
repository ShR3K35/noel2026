package fr.relance.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import fr.relance.app.ble.Link
import fr.relance.app.core.Calibration
import fr.relance.app.core.Profile
import fr.relance.app.core.ProgramType
import fr.relance.app.ui.AppViewModel
import fr.relance.app.ui.Screen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Écran d'étalonnage avec un boîtier simulé qui lit 10 % trop vite. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class CalibrationUiTest {
    val rule = createAndroidComposeRule<MainActivity>()

    /** Efface les données enregistrées par un test précédent avant le lancement de l'activité. */
    @get:Rule
    val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(object : org.junit.rules.ExternalResource() {
            override fun before() {
                org.robolectric.RuntimeEnvironment.getApplication().filesDir.listFiles()?.forEach { it.deleteRecursively() }
            }
        })
        .around(rule)

    /** Les enregistrements partent en tâche de fond : on les laisse finir avant d'effacer les données du test. */
    @org.junit.After
    fun cleanUp() {
        Thread.sleep(500)
        org.robolectric.RuntimeEnvironment.getApplication().filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun calibrationFlow() {
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        rule.runOnIdle {
            vm.completeOnboarding(Profile(treadmillAddress = "AA:BB:CC:DD:EE:FF", treadmillName = "Relance Run100"), ProgramType.RUN_30, false)
            vm.ble.treadmillLink.value = Link.CONNECTED
            vm.ble.calibrationUm.value = 157_080
            vm.open(Screen.Sensors)
        }
        rule.onNodeWithText("Étalonner le tapis").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/30-etalonnage-vide.png")
        for ((i, v) in Calibration.STEPS.withIndex()) {
            rule.runOnIdle { vm.ble.speed.value = v * 1.1 }
            rule.mainClock.advanceTimeBy(6500)
            if (i == 0) rule.onRoot().captureRoboImage("build/shots/31-etalonnage-palier.png")
            rule.onNodeWithText("Valider ce palier").performClick()
        }
        rule.waitForIdle()
        rule.onNodeWithText("Nouveau réglage : 142,8 mm par tour.", substring = true).assertExists()
        rule.onRoot().captureRoboImage("build/shots/32-etalonnage-resultat.png")
        assertTrue(vm.stack.last() == Screen.Calibration)
    }
}
