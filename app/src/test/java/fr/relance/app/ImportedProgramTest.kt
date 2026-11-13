package fr.relance.app

import fr.relance.app.core.Catalog
import fr.relance.app.core.Catalogs
import fr.relance.app.core.Coach
import fr.relance.app.core.Level
import fr.relance.app.core.Profile
import fr.relance.app.core.ProgramTemplate
import fr.relance.app.core.SessionKind
import fr.relance.app.core.SessionType
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Le programme « Courir 30 min · tapis, 25 semaines » (contract/examples), tel que l'app le jouera. */
class ImportedProgramTest {
    @Serializable
    data class Envelope(val program: ProgramTemplate, val sessionTypes: List<SessionType> = emptyList())

    companion object {
        fun load(name: String): Envelope =
            Catalogs.json.decodeFromString(Envelope.serializer(), File("../contract/examples/$name").readText())

        /** Catalogue intégré + programme importé, comme après un import dans l'interface coach. */
        fun merged(e: Envelope): Catalog = Catalogs.builtin.let { c ->
            c.copy(programs = c.programs + e.program, sessionTypes = c.sessionTypes + e.sessionTypes.filter { t -> c.type(t.id) == null })
        }
    }

    private val today = LocalDate.of(2026, 11, 10).toEpochDay() // un mardi
    private val e = load("courir-30-min-tapis.json")
    private val c = merged(e)
    private val t = c.program("courir-30-min-tapis")!!
    private val n2 = Profile(age = 40, level = Level.N2, vr = 5.4, days = setOf(2, 4, 6))

    @Test fun theDocumentIsFollowedWeekByWeek() {
        assertNull(Catalogs.check(c))
        assertEquals(listOf(Level.N2), t.levels)
        val p = Coach.newProgram(t, n2, today, c)
        val plan = Coach.plan(p, n2, today)
        assertEquals(25, p.weeksPlanned)
        assertEquals(25, plan.map { it.week }.distinct().size)
        assertEquals(75, plan.size)
        // Semaine 1 : mardi et samedi en course / marche, jeudi en marche active.
        val w1 = plan.filter { it.week == 0 }
        assertEquals(listOf(2, 4, 6), w1.map { Coach.dayOfWeek(it.day) })
        assertEquals("6 × (1 min de course / 3 min de marche)", w1[0].detail)
        assertEquals(36, w1[0].minutes) // 7 min d'échauffement + 24 + 5 de retour au calme
        assertEquals(listOf(4.0, 5.5, 4.3), w1[0].blocks.take(3).map { it.speedKmh })
        assertEquals("Marche active", w1[1].title)
        assertEquals(25, w1[1].minutes)
        assertEquals(w1[0].detail, w1[2].detail)
        // Durées à la seconde.
        val w2 = plan.first { it.week == 1 }
        assertEquals("6 × (1 min 15 s de course / 2 min 45 s de marche)", w2.detail)
        assertEquals(75, w2.blocks[1].seconds)
        // Semaine 3 : on répète la 2, la marche du jeudi passe à 30 min.
        assertEquals(w2.detail, plan.first { it.week == 2 }.detail)
        assertEquals(30, plan.filter { it.week == 2 }[1].minutes)
        assertEquals("18 min de course, 5 min de marche, puis 6 min de course", plan.first { it.week == 19 }.detail)
        val last = plan.filter { it.week == 24 }
        assertEquals("30 min de course continue", last[0].detail)
        assertEquals(40, last[1].minutes)
        assertTrue(plan.none { it.deload || it.kind == SessionKind.TEST })
        assertEquals("Phase 2 : développer l'endurance.", p.snapshot!!.template.weekNotes["9"])
    }

    @Test fun oneSessionAWeekDoublesTheLength() {
        val one = n2.copy(days = setOf(6))
        assertEquals(50, Coach.newProgram(t, one, today, c).weeksPlanned)
    }

    @Test fun anotherLevelIsOfferedTheClosestVariant() {
        // Programme proposé seulement en N2 : un N1 ou un N3 démarre la variante N2.
        assertEquals(Level.N2, Coach.defaultLevel(t, n2.copy(level = Level.N1)))
        val p = Coach.newProgram(t, n2.copy(level = Level.N3), today, c)
        assertEquals(Level.N2, p.startLevel)
        assertEquals(25, p.steps.size)
    }

    @Test fun builtInProgramsCanBeStartedAtAnyLevel() {
        val run30 = Catalogs.builtin.program("run-30")!!
        val asN3 = Coach.newProgram(run30, n2, today, level = Level.N3)
        val asN1 = Coach.newProgram(run30, n2, today, level = Level.N1)
        assertEquals(Level.N3, asN3.startLevel)
        assertTrue(asN1.steps.size > asN3.steps.size)
        assertEquals("intervals", asN3.steps.first().sessions.first().type)
        assertEquals("walk", asN1.steps.first().sessions.first().type)
    }
}
