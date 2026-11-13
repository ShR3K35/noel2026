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
import com.github.takahirom.roborazzi.captureScreenRoboImage
import fr.relance.app.core.Coach
import fr.relance.app.core.Feedback
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

/** Planning détaillé, séance faite en avance, puis annulée depuis l'historique et refaite. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class PlanningUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun planDetailStartEarlyCancelAndRedo() {
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        // Un seul jour d'entraînement, dans trois jours : aujourd'hui est un jour de repos.
        val trainingDay = (Coach.dayOfWeek(vm.today()) + 2) % 7 + 1
        rule.runOnIdle {
            vm.completeOnboarding(Profile(vr = 5.8, days = setOf(trainingDay)), ProgramType.RUN_30, false)
            vm.tab = 1
        }
        rule.waitForIdle()
        val before = vm.plan
        assertEquals(vm.today() + 3, before.first().day)
        rule.onRoot().captureRoboImage("build/shots/70-planning.png")

        // Une semaine prévisionnelle s'ouvre et ses séances se consultent.
        rule.onNodeWithText("SEMAINE 6", substring = true).performScrollTo().performClick()
        rule.onAllNodesWithText("Marche / course", substring = true).onFirst().performScrollTo()
        rule.onRoot().captureRoboImage("build/shots/71-planning-semaine-ouverte.png")
        rule.runOnIdle { vm.open(Screen.Planned(5)) }
        rule.waitForIdle()
        rule.onNodeWithText("BLOC PAR BLOC", substring = true).assertExists()
        rule.onNodeWithText("fais d'abord celles-là", substring = true).performScrollTo()
        rule.onRoot().captureRoboImage("build/shots/72-seance-prevue-lointaine.png")
        rule.runOnIdle { vm.back() }

        // La prochaine séance, prévue dans trois jours, se fait dès aujourd'hui.
        rule.runOnIdle { vm.open(Screen.Planned(0)) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/73-seance-prevue-prochaine.png")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("La faire maintenant").performClick()
        rule.mainClock.advanceTimeBy(500)
        assertEquals(Screen.Session, vm.stack.last())
        val e = vm.engine!!
        while (!e.finished) e.tick(null, null, null)
        vm.endSession()
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { vm.submitFeedback(Feedback(rpe = 5)) }
        rule.waitForIdle()
        val after = vm.plan
        assertEquals(before[1].title, after.first().title)
        assertEquals(before.first().day, after.first().day)
        assertEquals(before.size - 1, after.size)

        // Annulée depuis l'historique : elle redevient la prochaine séance, et Aujourd'hui l'affiche.
        rule.onNodeWithText("Annuler cette séance").performScrollTo().performClick()
        captureScreenRoboImage("build/shots/74-annuler-confirmation.png")
        rule.onNodeWithText("Annuler la séance").performClick()
        rule.waitForIdle()
        assertTrue(vm.state.sessions.isEmpty())
        assertEquals(0, vm.state.program!!.sessionsDone)
        assertEquals(before.map { it.title to it.day }, vm.plan.map { it.title to it.day })
        assertEquals(0, vm.tab)
        rule.onNodeWithText("est de nouveau la prochaine séance", substring = true).assertExists()
        rule.onNodeWithText(before.first().detail, substring = true).assertExists()
        rule.onRoot().captureRoboImage("build/shots/75-aujourdhui-apres-annulation.png")

        // Et on peut la refaire.
        rule.onNodeWithText("Je l'ai faite sans le téléphone").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Screen.ManualLog, vm.stack.last())
    }
}
