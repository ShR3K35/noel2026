package fr.relance.app

import fr.relance.app.core.CalStatus
import fr.relance.app.core.EndReason
import fr.relance.app.core.Observation
import fr.relance.app.core.PaceProtocol
import fr.relance.app.core.PaceTest
import fr.relance.app.core.PaceTestState
import fr.relance.app.core.PauseReason
import fr.relance.app.core.Phase
import fr.relance.app.core.RefKind
import fr.relance.app.core.RefStatus
import fr.relance.app.core.SegKind
import fr.relance.app.core.Speech
import fr.relance.app.core.Symptom
import fr.relance.app.core.TestKind
import fr.relance.app.core.Treadmill
import fr.relance.app.core.TreadmillSpec
import fr.relance.app.core.Verdict
import fr.relance.app.core.Verdicts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Règles du protocole de calibration : classement, arrondis, déroulé des tests, limite de 20 minutes. */
class PaceTestTest {
    private val tm = TreadmillSpec(1.0, 14.0, 0.1)

    private fun test(kind: TestKind, easy: Double = 4.0, candidate: Double = 0.0, usual: Double = 0.0, t: TreadmillSpec = tm) =
        PaceTest(PaceTestState("t1", kind, 20_000, t, easy, candidate, usual)).also { assertTrue(it.start(easy, 1_000, 0)) }

    private fun ok(rpe: Int = 2) = Observation(0, speech = Speech.YES, rpe = rpe, movementOk = true)

    /** Avance seconde par seconde jusqu'à `until`, ou jusqu'à ce qu'un contrôle s'ouvre ou qu'une vitesse soit attendue. */
    private fun PaceTest.runUntil(until: Int, stopOnCheck: Boolean = true): Int {
        var x = s.now
        while (x < until && s.phase != Phase.FINISHED) {
            x++
            tick(x)
            if (stopOnCheck && openCheck() != null) return x
            if (s.phase == Phase.AWAIT_SPEED) return x
        }
        return x
    }

    private fun PaceTest.toEnd(): Int {
        while (s.phase != Phase.FINISHED && s.now < 1300) tick(s.now + 1)
        return s.now
    }

    // ---------- Classement ----------

    @Test
    fun classificationFollowsTheOrderOfRules() {
        assertEquals(Verdict.TOO_HARD, Verdicts.classify(Observation(0, speech = Speech.NO, rpe = 1, movementOk = true)))
        assertEquals(Verdict.TOO_HARD, Verdicts.classify(Observation(0, speech = Speech.YES, rpe = 5, movementOk = true)))
        // Contradiction : parole « oui » mais effort 7 → le signal défavorable l'emporte.
        assertEquals(Verdict.TOO_HARD, Verdicts.classify(Observation(0, speech = Speech.YES, rpe = 7)))
        assertEquals(Verdict.BORDERLINE, Verdicts.classify(Observation(0, speech = Speech.UNSURE, rpe = 2, movementOk = true)))
        assertEquals(Verdict.BORDERLINE, Verdicts.classify(Observation(0, speech = Speech.YES, rpe = 4, movementOk = true)))
        // Un signal défavorable connu reste prioritaire sur une donnée manquante.
        assertEquals(Verdict.TOO_HARD, Verdicts.classify(Observation(0, speech = Speech.NO)))
        assertEquals(Verdict.BORDERLINE, Verdicts.classify(Observation(0, rpe = 4)))
        assertEquals(Verdict.COMFORTABLE, Verdicts.classify(ok(3)))
        // Données manquantes ou contradictoires → indéterminé.
        assertEquals(Verdict.UNKNOWN, Verdicts.classify(Observation(0, speech = Speech.YES, rpe = 2)))
        assertEquals(Verdict.UNKNOWN, Verdicts.classify(Observation(0, speech = Speech.YES, movementOk = true)))
        assertEquals(Verdict.UNKNOWN, Verdicts.classify(Observation(0, speech = Speech.YES, rpe = 2, movementOk = false)))
        assertEquals(Verdict.UNKNOWN, Verdicts.classify(Observation(0, noAnswer = true)))
        assertEquals(Verdict.UNKNOWN, Verdicts.classify(null))
        assertTrue(Verdicts.walkEasy(ok(2)))
        assertFalse(Verdicts.walkEasy(ok(3)))
    }

    // ---------- Tapis ----------

    @Test
    fun treadmillRoundingRespectsStepsAndBounds() {
        assertEquals(4.3, Treadmill.raise(4.0, tm)!!, 1e-9)
        assertEquals(5.2, Treadmill.fit(5.24, tm), 1e-9)
        assertEquals(1.0, Treadmill.fit(0.6, tm), 1e-9)
        assertEquals(14.0, Treadmill.fit(15.0, tm), 1e-9)
        val half = TreadmillSpec(1.0, 12.0, 0.5)
        assertEquals(4.5, Treadmill.fit(4.3, half), 1e-9)
        // Pas de 0,5 km/h : aucune hausse possible sans dépasser 0,3 km/h.
        assertNull(Treadmill.raise(4.5, half))
        val quarter = TreadmillSpec(0.8, 16.0, 0.25)
        assertEquals(4.25, Treadmill.raise(4.0, quarter)!!, 1e-9)
        // Vitesse maximale : la hausse s'arrête à la borne, puis plus rien.
        assertEquals(14.0, Treadmill.raise(13.8, tm)!!, 1e-9)
        assertNull(Treadmill.raise(14.0, tm))
        assertEquals(3.7, Treadmill.lower(4.0, tm), 1e-9)
    }

    // ---------- Test de marche ----------

    /** Marcheur qui ne court pas : marche facile à l'échauffement, deux paliers confortables, le troisième limite. */
    @Test
    fun walkerWithoutRunning() {
        val t = test(TestKind.WALK_STEPS, easy = 4.0)
        var x = t.runUntil(400)
        assertNotNull("contrôle de l'échauffement dans les 30 dernières secondes", t.openCheck())
        assertTrue(x in 270..300)
        assertTrue(t.answer(ok(2), x))
        assertFalse("double validation ignorée", t.answer(ok(2), x))
        x = t.runUntil(400)
        assertEquals(Phase.AWAIT_SPEED, t.s.phase)
        assertEquals(4.0, t.s.pending!!.proposedKmh, 1e-9)
        assertTrue(t.confirmSpeed(4.0, x + 10))
        assertFalse("double confirmation ignorée", t.confirmSpeed(4.0, x + 11))
        x = t.runUntil(800); t.answer(ok(3), x)
        x = t.runUntil(900)
        assertEquals(4.3, t.s.pending!!.proposedKmh, 1e-9)
        assertFalse("pas plus vite que la hausse proposée", t.confirmSpeed(4.6, x + 5))
        t.confirmSpeed(4.3, x + 5)
        x = t.runUntil(1100); t.answer(ok(3), x)
        x = t.runUntil(1100)
        assertEquals(4.6, t.s.pending!!.proposedKmh, 1e-9)
        t.confirmSpeed(4.6, x + 5)
        x = t.runUntil(1100); t.answer(Observation(0, speech = Speech.UNSURE, rpe = 4, movementOk = true), x)
        assertEquals(SegKind.COOLDOWN, t.s.current!!.kind)
        val end = t.toEnd()
        assertTrue(end <= PaceProtocol.BUDGET_SEC)
        val r = t.result()
        assertEquals(EndReason.COMPLETED, r.endReason)
        assertEquals(CalStatus.COMPLETE, r.status)
        assertEquals(4.0, r.refs.first { it.kind == RefKind.WALK_EASY }.kmh, 1e-9)
        // Plusieurs paliers confortables : on retient celui qui précède le dernier.
        val active = r.refs.first { it.kind == RefKind.WALK_ACTIVE }
        assertEquals(4.0, active.kmh, 1e-9)
        assertEquals(RefStatus.PROVISIONAL, active.status)
        assertNull("pas de trot pour un marcheur", r.refs.firstOrNull { it.kind == RefKind.RUN_EASY })
    }

    /** Difficulté dès le premier palier : marche active absente, marche facile gardée. */
    @Test
    fun difficultyOnFirstStep() {
        val t = test(TestKind.WALK_STEPS, easy = 3.5, usual = 4.5)
        var x = t.runUntil(400); t.answer(ok(1), x)
        x = t.runUntil(400)
        assertEquals("marche active habituelle jugée accessible", 4.5, t.s.pending!!.proposedKmh, 1e-9)
        t.confirmSpeed(4.5, x + 8)
        x = t.runUntil(800); t.answer(Observation(0, speech = Speech.NO, rpe = 6, movementOk = true), x)
        assertEquals(SegKind.COOLDOWN, t.s.current!!.kind)
        t.toEnd()
        val r = t.result()
        assertNull(r.refs.firstOrNull { it.kind == RefKind.WALK_ACTIVE })
        assertEquals(3.5, r.refs.first { it.kind == RefKind.WALK_EASY }.kmh, 1e-9)
        assertEquals(CalStatus.PARTIAL, r.status)
    }

    /** Tous les paliers faciles : résultat provisoire, limite supérieure non recherchée. */
    @Test
    fun allStepsEasy() {
        val t = test(TestKind.WALK_STEPS, easy = 4.2)
        var x = t.runUntil(400); t.answer(ok(1), x)
        repeat(3) {
            x = t.runUntil(1200)
            t.confirmSpeed(t.s.pending!!.proposedKmh, x + 5)
            x = t.runUntil(1200); t.answer(ok(2), x)
        }
        t.toEnd()
        val r = t.result()
        assertEquals(4.5, r.refs.first { it.kind == RefKind.WALK_ACTIVE }.kmh, 1e-9)
        assertTrue(r.notes.any { it.contains("limite supérieure") })
        assertTrue(t.s.segments.last().endAt <= PaceProtocol.BUDGET_SEC)
    }

    /** Échauffement pas facile : retour au calme plus lent, calibration incomplète. */
    @Test
    fun warmupNotEasyEndsSlower() {
        val t = test(TestKind.WALK_STEPS, easy = 5.0)
        val x = t.runUntil(400); t.answer(ok(3), x)
        val cd = t.s.current!!
        assertEquals(SegKind.COOLDOWN, cd.kind)
        assertEquals(4.7, cd.proposedKmh, 1e-9)
        t.toEnd()
        assertTrue(t.result().refs.isEmpty())
    }

    /** Pas du tapis supérieur à 0,3 km/h : on garde la vitesse et on propose une confirmation à vitesse constante. */
    @Test
    fun coarseTreadmillStepSuggestsConfirmation() {
        val t = test(TestKind.WALK_STEPS, easy = 4.0, t = TreadmillSpec(1.0, 12.0, 0.5))
        var x = t.runUntil(400); t.answer(ok(1), x)
        x = t.runUntil(400); t.confirmSpeed(4.0, x + 3)
        x = t.runUntil(800); t.answer(ok(2), x)
        t.runUntil(x + 40, stopOnCheck = false)
        assertEquals(SegKind.COOLDOWN, t.s.current!!.kind)
        val r = t.also { it.toEnd() }.result()
        assertTrue(r.suggestions.any { it.kind == TestKind.CONFIRM_WALK && it.kmh == 4.0 })
        assertEquals(RefStatus.PROVISIONAL, r.refs.first { it.kind == RefKind.WALK_ACTIVE }.status)
    }

    // ---------- Marche-course ----------

    /** Débutant : trois répétitions confortables à la même vitesse → trot facile provisoire d'une minute. */
    @Test
    fun beginnerThreeRepetitions() {
        val t = test(TestKind.WALK_RUN, easy = 4.0)
        var x = t.runUntil(400); t.answer(ok(1), x)
        repeat(3) { i ->
            x = t.runUntil(1200)
            assertEquals(Phase.AWAIT_SPEED, t.s.phase)
            assertEquals(SegKind.REP, t.s.pending!!.kind)
            assertTrue(t.confirmSpeed(6.4, x + 4))
            x = t.runUntil(1200)
            assertNotNull(t.openCheck())
            if (i == 1) {
                // Réponse après le retour à la marche : acceptée mais signalée.
                x = t.runUntil(x + 40, stopOnCheck = false)
                assertEquals(SegKind.RECOVERY, t.s.current!!.kind)
                assertTrue(t.openCheck()!!.late)
            }
            t.answer(ok(3), x)
        }
        t.toEnd()
        val r = t.result()
        val run = r.refs.first { it.kind == RefKind.RUN_EASY }
        assertEquals(6.4, run.kmh, 1e-9)
        assertEquals(RefStatus.PROVISIONAL, run.status)
        assertEquals(60, run.observedSec)
        assertTrue(r.notes.any { it.contains("après le retour à la marche") })
        assertTrue(r.segments.last().endAt <= PaceProtocol.BUDGET_SEC)
    }

    @Test
    fun hardRepetitionEndsRunningPart() {
        val t = test(TestKind.WALK_RUN, easy = 4.0)
        var x = t.runUntil(400); t.answer(ok(1), x)
        x = t.runUntil(1200); t.confirmSpeed(6.0, x + 3)
        x = t.runUntil(1200); t.answer(Observation(0, speech = Speech.YES, rpe = 6, movementOk = true), x)
        assertEquals(SegKind.COOLDOWN, t.s.current!!.kind)
        t.toEnd()
        assertNull(t.result().refs.firstOrNull { it.kind == RefKind.RUN_EASY })
    }

    @Test
    fun differentRepSpeedsDoNotMakeAReference() {
        val t = test(TestKind.WALK_RUN, easy = 4.0)
        var x = t.runUntil(400); t.answer(ok(1), x)
        for (v in listOf(6.0, 6.3, 6.0)) {
            x = t.runUntil(1200); t.confirmSpeed(v, x + 3)
            x = t.runUntil(1200); t.answer(ok(3), x)
        }
        t.toEnd()
        assertNull(t.result().refs.firstOrNull { it.kind == RefKind.RUN_EASY })
    }

    // ---------- Confirmation continue ----------

    /** Coureur : 10 min confirmées au-dessus de 10 km/h, sans plafond universel. */
    @Test
    fun runnerConfirmsAbove10() {
        val t = test(TestKind.CONFIRM_RUN, easy = 5.0, candidate = 11.5, t = TreadmillSpec(1.0, 16.0, 0.1))
        var x = t.runUntil(400)
        assertNull("pas de contrôle pendant l'échauffement d'une confirmation", t.openCheck())
        assertEquals(11.5, t.s.pending!!.proposedKmh, 1e-9)
        t.confirmSpeed(11.5, x + 20)
        val start = t.s.current!!.startAt
        repeat(3) {
            x = t.runUntil(1200)
            assertNotNull(t.openCheck())
            t.answer(ok(3), x)
        }
        t.toEnd()
        val r = t.result()
        val run = r.refs.first { it.kind == RefKind.RUN_EASY }
        assertEquals(11.5, run.kmh, 1e-9)
        assertEquals(RefStatus.CONFIRMED, run.status)
        assertEquals(600, run.observedSec)
        assertEquals(CalStatus.COMPLETE, r.status)
        assertTrue(start + 600 + 180 <= PaceProtocol.BUDGET_SEC)
    }

    @Test
    fun borderlineDuringConfirmationEndsAttemptAndSuggestsLower() {
        val t = test(TestKind.CONFIRM_WALK, easy = 4.0, candidate = 5.5)
        var x = t.runUntil(400); t.confirmSpeed(5.5, x + 5)
        x = t.runUntil(1200); t.answer(ok(2), x)
        x = t.runUntil(1200); t.answer(Observation(0, speech = Speech.UNSURE, rpe = 3, movementOk = true), x)
        assertEquals(SegKind.COOLDOWN, t.s.current!!.kind)
        t.toEnd()
        val r = t.result()
        assertTrue(r.refs.none { it.kind == RefKind.WALK_ACTIVE })
        assertTrue(r.suggestions.any { it.kind == TestKind.CONFIRM_WALK && it.kmh == 5.2 })
    }

    @Test
    fun speedChangeDuringBlockDoesNotValidateTenMinutes() {
        val t = test(TestKind.CONFIRM_WALK, easy = 4.0, candidate = 5.5)
        var x = t.runUntil(400); t.confirmSpeed(5.5, x + 5)
        x = t.runUntil(x + 200, stopOnCheck = false)
        t.answer(ok(2), x)
        // Correction de vitesse au milieu du bloc : la continuité repart de zéro, plus le temps de la confirmer.
        x = t.runUntil(x + 200, stopOnCheck = false)
        assertTrue(t.correctSpeed(5.2, x))
        t.toEnd()
        assertTrue(t.result().refs.none { it.kind == RefKind.WALK_ACTIVE && it.status == RefStatus.CONFIRMED })
    }

    // ---------- Interruptions ----------

    /** Pause pendant le bloc : continuité perdue, aucune reprise automatique de l'effort. */
    @Test
    fun pauseDuringBlockRequiresConfirmation() {
        val t = test(TestKind.CONFIRM_WALK, easy = 4.0, candidate = 5.4)
        var x = t.runUntil(400); t.confirmSpeed(5.4, x + 5)
        x = t.runUntil(x + 250, stopOnCheck = false)
        assertTrue(t.pause(PauseReason.BACKGROUND, x))
        assertEquals(Phase.PAUSED, t.s.phase)
        assertTrue(t.s.segments.last().interrupted)
        t.tick(x + 60)
        assertEquals("le temps continue de courir pendant la pause", x + 60, t.s.now)
        assertTrue(t.resume(x + 60))
        // Le bloc ne recommence qu'après confirmation de la vitesse, s'il tient encore dans les 20 minutes.
        assertTrue(t.s.phase == Phase.AWAIT_SPEED || t.s.current?.kind == SegKind.COOLDOWN)
        t.toEnd()
        assertTrue(t.result().refs.none { it.kind == RefKind.WALK_ACTIVE })
    }

    /** Redémarrage de l'app : le test est mis en pause, jamais repris sans confirmation. */
    @Test
    fun restoreAfterProcessDeathPauses() {
        val t = test(TestKind.WALK_STEPS, easy = 4.0)
        var x = t.runUntil(400); t.answer(ok(1), x)
        x = t.runUntil(400); t.confirmSpeed(4.0, x + 4)
        t.runUntil(x + 60, stopOnCheck = false)
        val saved = t.s
        val again = PaceTest(saved)
        again.restored(saved.now + 30)
        assertEquals(Phase.PAUSED, again.s.phase)
        assertEquals(PauseReason.RESTORED, again.s.pauseReason)
        assertTrue(again.s.segments.last().interrupted)
        again.resume(saved.now + 40)
        assertEquals(Phase.AWAIT_SPEED, again.s.phase)
        // Horloge monotone revenue en arrière : le téléphone a redémarré.
        val rebooted = PaceTest(saved)
        rebooted.restored(5)
        assertEquals(Phase.FINISHED, rebooted.s.phase)
    }

    @Test
    fun symptomAbortsWithoutReferences() {
        val t = test(TestKind.WALK_STEPS, easy = 4.0)
        val x = t.runUntil(400); t.answer(ok(1), x)
        t.symptom(Symptom.CHEST_PAIN, x + 30)
        assertEquals(Phase.FINISHED, t.s.phase)
        val r = t.result()
        assertEquals(CalStatus.ABORTED, r.status)
        assertTrue(r.refs.isEmpty())
    }

    @Test
    fun missingAnswerAllowsNoProgression() {
        val t = test(TestKind.WALK_STEPS, easy = 4.0)
        var x = t.runUntil(400); t.answer(ok(1), x)
        x = t.runUntil(400); t.confirmSpeed(4.0, x + 4)
        // Aucune réponse au contrôle du palier : après le délai de grâce, retour au calme.
        t.runUntil(x + 4 + PaceProtocol.WALK_STEP_SEC + PaceProtocol.ANSWER_GRACE_SEC + 2, stopOnCheck = false)
        assertEquals(SegKind.COOLDOWN, t.s.current!!.kind)
        t.toEnd()
        assertTrue(t.result().refs.none { it.kind == RefKind.WALK_ACTIVE })
    }

    // ---------- Limite de 20 minutes ----------

    /** Réglages très lents : les paliers qui ne tiennent plus sont supprimés, jamais plus de 1 200 s. */
    @Test
    fun slowSettingsShortenTheTest() {
        val t = test(TestKind.WALK_STEPS, easy = 4.0)
        var x = t.runUntil(400); t.answer(ok(1), x)
        x = t.runUntil(1200); t.confirmSpeed(4.0, x + 170)
        x = t.runUntil(1200); t.answer(ok(2), x)
        x = t.runUntil(1200); t.confirmSpeed(4.3, x + 170)
        x = t.runUntil(1200); t.answer(ok(2), x)
        t.runUntil(x + 40, stopOnCheck = false)
        assertEquals("troisième palier supprimé faute de temps", SegKind.COOLDOWN, t.s.segments.last().kind)
        assertEquals(180, t.s.segments.last().plannedSec)
        assertTrue(t.toEnd() <= PaceProtocol.BUDGET_SEC)
        assertTrue(t.result().notes.any { it.contains("plus assez de temps") })
    }

    /** Suites d'actions aléatoires : le test ne dépasse jamais 20 minutes et finit toujours. */
    @Test
    fun neverExceedsBudget() {
        val rnd = Random(42)
        repeat(400) {
            val kind = TestKind.values()[rnd.nextInt(4)]
            val t = test(kind, easy = 3.0 + rnd.nextInt(20) / 10.0, candidate = 5.0 + rnd.nextInt(60) / 10.0, usual = if (rnd.nextBoolean()) 5.0 else 0.0)
            var x = 0
            while (t.s.phase != Phase.FINISHED && x < 2000) {
                x += 1 + rnd.nextInt(6)
                t.tick(x)
                when (rnd.nextInt(30)) {
                    0 -> t.pause(PauseReason.USER, x)
                    1 -> t.resume(x)
                    2 -> t.correctSpeed(3.0 + rnd.nextInt(80) / 10.0, x)
                    3 -> t.slowDown(x)
                    4 -> t.extendRecovery(x)
                    5 -> if (rnd.nextInt(10) == 0) t.endProgression("test", x)
                }
                if (t.s.phase == Phase.PAUSED && rnd.nextInt(4) == 0) t.resume(x)
                if (t.s.phase == Phase.AWAIT_SPEED && rnd.nextInt(3) == 0) t.confirmSpeed(t.s.pending!!.proposedKmh.takeIf { it > 0 } ?: 6.0, x)
                if (t.openCheck() != null && rnd.nextInt(3) == 0) t.answer(Observation(0, speech = Speech.values()[rnd.nextInt(3)], rpe = rnd.nextInt(11), movementOk = rnd.nextBoolean()), x)
            }
            assertEquals(Phase.FINISHED, t.s.phase)
            assertTrue(t.s.now <= PaceProtocol.BUDGET_SEC)
            t.s.segments.forEach { seg -> assertTrue("$kind ${seg.kind} ${seg.endAt}", seg.endAt in 0..PaceProtocol.BUDGET_SEC) }
            val r = t.result()
            r.refs.filter { it.status == RefStatus.CONFIRMED }.forEach { assertEquals(600, it.observedSec) }
        }
    }
}
