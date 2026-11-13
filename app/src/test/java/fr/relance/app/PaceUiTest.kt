package fr.relance.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import fr.relance.app.core.BlockKind
import fr.relance.app.core.Capacity
import fr.relance.app.core.Level
import fr.relance.app.core.PaceProtocol
import fr.relance.app.core.PauseReason
import fr.relance.app.core.Phase
import fr.relance.app.core.Profile
import fr.relance.app.core.ProgramType
import fr.relance.app.core.RefKind
import fr.relance.app.core.RefSource
import fr.relance.app.core.RefStatus
import fr.relance.app.core.SegKind
import fr.relance.app.core.TestKind
import fr.relance.app.ui.AppViewModel
import fr.relance.app.ui.Screen
import fr.relance.app.voice.MicState
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Test de marche par paliers, depuis le profil, entièrement aux boutons (micro refusé), jusqu'aux séances recalculées. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class PaceUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val vm get() = ViewModelProvider(rule.activity)[AppViewModel::class.java]
    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/shots/$name.png")

    private fun node(text: String, index: Int = 0): SemanticsNodeInteraction = rule.onAllNodesWithText(text)[index]

    private fun tap(text: String, index: Int = 0) {
        val n = node(text, index)
        try {
            n.performScrollTo()
        } catch (e: AssertionError) {
            // Bouton hors de la zone défilante (barre d'actions).
        }
        n.performClick()
        rule.waitForIdle()
    }

    private fun advance(sec: Int) {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(sec.toLong()))
        rule.runOnIdle { vm.pace.tick() }
        rule.waitForIdle()
    }

    private fun answer(speech: String, rpe: String, movement: String) {
        tap("J'ai lu le texte")
        tap(speech, 0)
        tap(rpe)
        // « Oui » et « Non » apparaissent deux fois : parole, puis déplacement.
        tap(movement, 1)
    }

    @Test
    fun walkTestByTouch() {
        rule.runOnIdle {
            vm.completeOnboarding(Profile(vr = 5.0, level = Level.N2), ProgramType.RUN_30, false)
            vm.tab = 4
        }
        val before = vm.plan.first().blocks.first { it.kind == BlockKind.WARMUP }.speedKmh
        assertEquals(4.0, before, 1e-9)
        tap("Mes allures et tests")
        assertEquals(Screen.Paces, vm.stack.last())
        shot("90-mes-allures")
        tap("Faire un test de calibration")
        tap("En forme")
        tap(Capacity.WALK_ONLY.label)
        tap("Ce sont bien les réglages de mon tapis")
        shot("91-preparation")
        tap("Continuer")
        rule.onNodeWithText("conseillé").assertExists()
        shot("92-choix-du-test")
        tap("Continuer")
        tap("Le tapis est à plat (pente 0 %)")
        tap("Continuer")
        // Micro refusé : tout le parcours reste possible aux boutons.
        rule.runOnIdle { vm.pace.permissionDenied() }
        rule.waitForIdle()
        rule.onNodeWithText(MicState.NO_PERMISSION.label).assertExists()
        shot("93-micro-refuse")
        tap("Continuer sans micro")
        tap("Je marche à 3,5 km/h")
        assertEquals(Screen.PaceRun, vm.stack.last())
        shot("94-echauffement")

        // Échauffement : contrôle dans les 30 dernières secondes.
        advance(275)
        rule.onNodeWithText(PaceProtocol.PASSAGE).assertExists()
        shot("95-controle-lecture")
        tap("J'ai lu le texte")
        shot("96-controle-questions")
        tap("Oui", 0); tap("2"); tap("Oui", 1)
        advance(30)
        assertEquals(Phase.AWAIT_SPEED, vm.pace.engine!!.s.phase)
        shot("97-reglage-vitesse")
        tap("C'est réglé : 3,5 km/h")

        // Palier 1 confortable, palier 2 trop difficile.
        advance(155)
        answer("Oui", "3", "Oui")
        advance(30)
        rule.onNodeWithText("C'est réglé : 3,8 km/h").assertExists()
        tap("C'est réglé : 3,8 km/h")
        advance(155)
        answer("Non", "6", "Oui")
        assertEquals(SegKind.COOLDOWN, vm.pace.engine!!.s.current!!.kind)
        shot("98-retour-au-calme")
        advance(185)
        rule.waitForIdle()
        assertEquals(Screen.PaceResult, vm.stack.last())
        val r = vm.paceRecord!!
        assertEquals(3.5, r.refs.first { it.kind == RefKind.WALK_EASY }.kmh, 1e-9)
        assertEquals(3.5, r.refs.first { it.kind == RefKind.WALK_ACTIVE }.kmh, 1e-9)
        assertTrue(r.segments.last().endAt <= PaceProtocol.BUDGET_SEC)
        shot("99-resultat")

        tap("Enregistrer et utiliser ces repères")
        assertEquals(Screen.Paces, vm.stack.last())
        val p = vm.state.profile!!
        assertEquals(RefSource.TEST, p.paceRefs.walkEasy!!.source)
        assertEquals(RefStatus.PROVISIONAL, p.paceRefs.walkActive!!.status)
        assertNull(p.paceRefs.runEasy)
        assertEquals(1, vm.state.calibrations.size)
        assertNull(vm.state.paceTest)
        // Les séances à venir utilisent la marche facile mesurée ; l'historique ne change pas.
        assertEquals(3.5, vm.plan.first().blocks.first { it.kind == BlockKind.WARMUP }.speedKmh, 1e-9)
        assertEquals(3.5, vm.plan.first().basis!!.walkEasy!!.kmh, 1e-9)
        shot("100-mes-allures-apres")
    }
}

/** Interruptions : arrière-plan et relance de l'app (classe à part : une JVM neuve, sans l'état du test précédent). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class PaceInterruptUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val vm get() = ViewModelProvider(rule.activity)[AppViewModel::class.java]
    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/shots/$name.png")

    private fun node(text: String, index: Int = 0): SemanticsNodeInteraction = rule.onAllNodesWithText(text)[index]

    private fun tap(text: String, index: Int = 0) {
        val n = node(text, index)
        try {
            n.performScrollTo()
        } catch (e: AssertionError) {
            // Bouton hors de la zone défilante (barre d'actions).
        }
        n.performClick()
        rule.waitForIdle()
    }

    private fun advance(sec: Int) {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(sec.toLong()))
        rule.runOnIdle { vm.pace.tick() }
        rule.waitForIdle()
    }

    private fun answer(speech: String, rpe: String, movement: String) {
        tap("J'ai lu le texte")
        tap(speech, 0)
        tap(rpe)
        // « Oui » et « Non » apparaissent deux fois : parole, puis déplacement.
        tap(movement, 1)
    }

    /** Passage en arrière-plan, puis relance de l'app : jamais de reprise automatique de l'effort. */
    @Test
    fun interruptionsPauseTheTest() {
        rule.runOnIdle {
            vm.completeOnboarding(Profile(vr = 5.0, level = Level.N2, capacity = Capacity.WALK_ONLY, treadmillChecked = true), ProgramType.RUN_30, false)
            vm.beginPaceTest(TestKind.CONFIRM_WALK, 3.8, 5.0, 0.0)
        }
        rule.waitForIdle()
        advance(300)
        tap("C'est réglé : 5,0 km/h")
        advance(60)
        rule.runOnIdle { vm.onBackground() }
        rule.waitForIdle()
        val s = vm.pace.engine!!.s
        assertEquals(Phase.PAUSED, s.phase)
        assertEquals(PauseReason.BACKGROUND, s.pauseReason)
        assertTrue(s.segments.last().interrupted)
        rule.onNodeWithText("Test en pause").assertExists()
        shot("101-pause")
        // L'état sauvegardé est relu comme après un redémarrage : toujours en pause.
        val saved = vm.state.paceTest
        assertNotNull(saved)
        rule.runOnIdle { vm.pace.restore(saved!!.copy(phase = Phase.RUNNING, pauseReason = null)) }
        rule.waitForIdle()
        assertEquals(PauseReason.RESTORED, vm.pace.engine!!.s.pauseReason)
        rule.onNodeWithText("L'app a été relancée pendant le test", substring = true).assertExists()
        tap("Reprendre")
        assertEquals(Phase.AWAIT_SPEED, vm.pace.engine!!.s.phase)
        tap("Arrêter")
        advance(1)
        assertEquals(Screen.PaceResult, vm.stack.last())
        tap("Enregistrer dans l'historique")
        assertTrue(vm.state.profile!!.paceRefs.isEmpty)
        assertEquals(1, vm.state.calibrations.size)
    }
}
