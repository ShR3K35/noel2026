package fr.relance.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import fr.relance.app.core.ProgramStatus
import fr.relance.app.ui.AppViewModel
import fr.relance.app.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Parcours complet de l'app, du bilan de départ à la fiche de séance, avec une capture par écran. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class UiSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/shots/$name.png")
    private fun tap(text: String) = rule.onAllNodesWithText(text).onFirst().performClick()
    private val vm get() = ViewModelProvider(rule.activity)[AppViewModel::class.java]

    @Test
    fun fromOnboardingToSessionDetail() {
        rule.onNodeWithText("D'abord, ta santé").assertIsDisplayed()
        shot("01-sante")
        tap("Continuer"); shot("02-profil")
        tap("Continuer"); shot("03-genes")
        tap("Continuer"); shot("04-test")
        tap("Continuer"); shot("05-emploi-du-temps")
        tap("Continuer"); shot("06-objectif")
        tap("Voir mon programme")
        rule.waitForIdle()
        assertEquals(Screen.Home, vm.stack.last())
        assertNotNull(vm.state.program)
        assertTrue(vm.plan.isNotEmpty())
        shot("07-aujourdhui")

        tap("Planning"); shot("08-planning")
        tap("Programmes"); shot("09-programmes")
        tap("Profil"); shot("10-profil")
        tap("Associer"); shot("11-capteurs")
        tap("‹ Retour")
        tap("Historique"); shot("12-historique-vide")
        tap("Aujourd'hui")

        // Séance : on fige l'horloge de Compose, le coureur s'anime sans fin.
        rule.mainClock.autoAdvance = false
        vm.startSession()
        rule.mainClock.advanceTimeBy(500)
        assertEquals(Screen.Session, vm.stack.last())
        shot("13-seance-echauffement")
        val e = vm.engine!!
        while (e.index == 0) e.tick(null, 128, null)
        repeat(20) { e.tick(null, 131, null) }
        vm.togglePause(); vm.togglePause()
        rule.mainClock.advanceTimeBy(1500)
        shot("14-seance-bloc-suivant")
        while (!e.finished && e.elapsed < 600) e.tick(null, 140, null)
        vm.togglePause()
        rule.mainClock.advanceTimeBy(1500)
        shot("15-seance-en-pause")
        vm.togglePause()
        while (!e.finished) e.tick(null, 120, null)
        vm.endSession()
        rule.mainClock.advanceTimeBy(500)
        assertEquals(Screen.Bilan, vm.stack.last())
        rule.mainClock.autoAdvance = true
        shot("16-bilan")
        tap("5")
        rule.onNodeWithText("Enregistrer").performClick()
        rule.waitForIdle()
        assertTrue(vm.stack.last() is Screen.Detail)
        assertEquals(1, vm.state.sessions.size)
        assertEquals(1, vm.state.program!!.sessionsDone)
        assertTrue(vm.samples(vm.state.sessions.first().id).size >= 600)
        assertTrue(vm.exportTcx(vm.state.sessions.first().id).contains("<Trackpoint>"))
        shot("17-fiche-seance")
        tap("‹ Historique")
        shot("18-historique")
        tap("Aujourd'hui"); shot("19-aujourdhui-apres-seance")

        // Un seul programme à la fois : l'arrêter le range dans les programmes effectués.
        tap("Programmes")
        rule.onNodeWithText("Arrêter").performScrollTo().performClick()
        rule.onAllNodesWithText("Arrêter").onFirst().assertIsDisplayed()
        shot("20-confirmation-arret")
        rule.onAllNodesWithText("Arrêter")[1].performClick()
        rule.waitForIdle()
        shot("20b-programmes-effectues")
        assertNull(vm.state.program)
        assertEquals(ProgramStatus.STOPPED, vm.state.archived.first().status)
        tap("Choisir un programme"); shot("21-catalogue")
    }
}
