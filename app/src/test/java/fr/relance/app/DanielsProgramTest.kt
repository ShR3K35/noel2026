package fr.relance.app

import fr.relance.app.core.BlockKind
import fr.relance.app.core.Catalogs
import fr.relance.app.core.Coach
import fr.relance.app.core.Feedback
import fr.relance.app.core.Level
import fr.relance.app.core.Profile
import fr.relance.app.core.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** « Courir 30 min · méthode Daniels » : lignes droites de 20 s, paliers tenus jusqu'au bout, 48 h entre deux trots. */
class DanielsProgramTest {
    private val e = ImportedProgramTest.load("courir-30-min-daniels.json")
    private val c = ImportedProgramTest.merged(e)
    private val t = c.program("courir-30-min-daniels")!!
    private val monday = LocalDate.of(2026, 11, 16).toEpochDay()
    private val spaced = Profile(age = 45, level = Level.N2, vr = 5.4, days = setOf(1, 3, 6))

    @Test
    fun shortBlocksAreKeptToTheSecond() {
        assertNull(Catalogs.check(c))
        assertEquals(20, Templates.blockSeconds(0.33))
        assertEquals(10, Templates.blockSeconds(0.05))
        // Une durée nulle ou négative garde l'ancien comportement : 1 min.
        assertEquals(60, Templates.blockSeconds(0.0))
        assertEquals(60, Templates.blockSeconds(-1.0))
        assertEquals(75, Templates.blockSeconds(1.25))
    }

    @Test
    fun stridesCloseTheLastEasyRuns() {
        val p = Coach.newProgram(t, spaced, monday, c)
        val plan = Coach.plan(p, spaced, monday)
        assertEquals(60, plan.size) // 10 paliers × 4 séances clés, à 2 clés par semaine : 20 semaines de 3 séances
        val withStrides = plan.filter { s -> s.blocks.any { it.kind == BlockKind.FAST && it.seconds == 20 } }
        assertEquals(8, withStrides.size) // une sortie facile par semaine, sur les 4 derniers paliers (8 semaines)
        val s = withStrides.first()
        assertEquals("10 × (1 min de trot / 1 min de marche), puis 4 lignes droites de 20 s", s.detail)
        assertEquals(4, s.blocks.count { it.kind == BlockKind.FAST })
        assertTrue(s.blocks.first { it.kind == BlockKind.FAST }.speedKmh > s.blocks.first { it.kind == BlockKind.RUN }.speedKmh)
    }

    @Test
    fun noSkippingInThisProgram() {
        var p = Coach.newProgram(t, spaced, monday, c)
        assertFalse(p.snapshot!!.template.allowSkip)
        var prof = spaced
        repeat(2) { i ->
            val s = Coach.sessionFor(p, prof, monday + i)
            val r = Coach.applyFeedback(p, prof, s, Feedback(rpe = 2), monday + i)
            p = r.program; prof = r.profile
            if (i == 1) assertTrue(r.message.contains("chaque palier est tenu jusqu'au bout"))
        }
        assertFalse(p.skipStep)
        // Ailleurs, la règle reste : deux séances faciles font sauter un palier.
        var q = Coach.newProgram(Catalogs.builtin.program("run-30")!!, spaced, monday)
        repeat(2) { i -> q = Coach.applyFeedback(q, spaced, Coach.sessionFor(q, spaced, monday + i), Feedback(rpe = 2), monday + i).program }
        assertTrue(q.skipStep)
    }

    /** Jours qui se suivent : la sortie facile avec trot devient de la marche, jamais deux trots à moins de 48 h. */
    @Test
    fun noTwoRunsWithin48Hours() {
        for (days in listOf(setOf(1, 2, 3), setOf(2, 4, 6, 7), setOf(5, 6, 7), setOf(1, 3, 6))) {
            val prof = spaced.copy(days = days)
            for (start in monday until monday + 7) {
                val plan = Coach.plan(Coach.newProgram(t, prof, start, c), prof, start)
                val runs = plan.filter { s -> s.blocks.any { it.kind == BlockKind.RUN || it.kind == BlockKind.FAST } && !s.isKey }
                for (s in runs) assertFalse("$days, sortie facile le ${s.day}", Coach.adjacentTrainingDay(prof, s.day))
            }
        }
        // Jours espacés : la sortie facile garde son trot.
        val plan = Coach.plan(Coach.newProgram(t, spaced, monday, c), spaced, monday)
        assertTrue(plan.any { !it.isKey && it.blocks.any { b -> b.kind == BlockKind.RUN } })
        // Jours consécutifs : remplacée par la marche ou la côte du programme.
        val tight = spaced.copy(days = setOf(1, 2, 3))
        val tp = Coach.plan(Coach.newProgram(t, tight, monday, c), tight, monday)
        assertTrue(tp.filter { !it.isKey }.none { s -> s.blocks.any { it.kind == BlockKind.RUN } })
    }
}
