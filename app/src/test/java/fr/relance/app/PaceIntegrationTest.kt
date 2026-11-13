package fr.relance.app

import fr.relance.app.core.AppState
import fr.relance.app.core.BlockKind
import fr.relance.app.core.Catalogs
import fr.relance.app.core.Coach
import fr.relance.app.core.Distances
import fr.relance.app.core.Level
import fr.relance.app.core.PaceBasis
import fr.relance.app.core.PaceRef
import fr.relance.app.core.PaceRefs
import fr.relance.app.core.Physio
import fr.relance.app.core.Profile
import fr.relance.app.core.RefKind
import fr.relance.app.core.RefSource
import fr.relance.app.core.RefStatus
import fr.relance.app.core.SessionKind
import fr.relance.app.core.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Les références de calibration dans le calcul des séances : replis, tapis, vitesses fixes, distances, migration. */
class PaceIntegrationTest {
    private val today = LocalDate.of(2026, 11, 13).toEpochDay()

    private fun ref(kind: RefKind, kmh: Double, status: RefStatus = RefStatus.PROVISIONAL, sec: Int = 180, source: RefSource = RefSource.TEST) =
        PaceRef(kind, kmh, 0.0, today, "walk-steps", 1, source, status, sec, "t1")

    private val legacy = Profile(age = 45, level = Level.N2, vr = 5.0, days = setOf(1, 3, 6))

    @Test
    fun referencesDriveTheSessionPaces() {
        val p = legacy.copy(paceRefs = PaceRefs(ref(RefKind.WALK_EASY, 3.6), ref(RefKind.WALK_ACTIVE, 5.1), ref(RefKind.RUN_EASY, 7.4)))
        val s = Physio.speedsFor(p)
        assertEquals(3.6, s.warm, 1e-9)
        assertEquals(3.6, s.recovery, 1e-9)
        assertEquals(5.1, s.walk, 1e-9)
        assertEquals(7.4, s.run, 1e-9)
        // Côte : mécanisme existant (vitesse de référence), identifié comme tel.
        assertEquals(Physio.round1(5.0 * 0.85), s.hill, 1e-9)
        val c = Catalogs.builtin
        val intervals = Templates.build(c.type("intervals")!!, mapOf("a" to 1.0, "b" to 2.0, "reps" to 3.0), p, s, today, 0, true, false, 0)
        assertEquals(3.6, intervals.blocks.first { it.kind == BlockKind.WARMUP }.speedKmh, 1e-9)
        assertEquals(7.4, intervals.blocks.first { it.kind == BlockKind.RUN }.speedKmh, 1e-9)
        assertEquals(3.6, intervals.blocks.first { it.kind == BlockKind.COOLDOWN }.speedKmh, 1e-9)
        assertEquals(7.4, intervals.basis!!.runEasy!!.kmh, 1e-9)
        assertTrue(intervals.warnings.isEmpty())
    }

    /** Sans référence : repli explicite sur l'ancien calcul, identique à avant (jeux partagés avec le simulateur web). */
    @Test
    fun legacyProfileKeepsOldPacesExplicitly() {
        val s = Physio.speedsFor(legacy)
        assertEquals(4.0, s.warm, 1e-9)
        assertEquals(4.8, s.walk, 1e-9)
        assertEquals(6.0, s.run, 1e-9)
        val basis = PaceBasis.of(legacy)
        assertNull(basis.runEasy)
        assertTrue(basis.lines().all { "repli" in it || "réglages du programme" in it })
    }

    /** Marcheur : marche calibrée, mais le trot n'est jamais déduit de la marche par un multiplicateur. */
    @Test
    fun walkerRunIsNotDerivedFromWalk() {
        val walker = legacy.copy(vr = 4.4, paceRefs = PaceRefs(ref(RefKind.WALK_EASY, 3.2), ref(RefKind.WALK_ACTIVE, 4.6)))
        val s = Physio.speedsFor(walker)
        assertEquals(4.6, s.walk, 1e-9)
        // Pas de trot facile : repli sur l'ancien calcul depuis la vitesse de référence, signalé comme tel.
        assertEquals(6.0, s.run, 1e-9)
        assertNull(PaceBasis.of(walker).runEasy)
        assertTrue(PaceBasis.of(walker).lines()[2].contains("repli"))
        // Marche active absente mais marche facile connue : la marche n'est jamais plus lente que la marche facile.
        val easyOnly = legacy.copy(vr = 3.5, paceRefs = PaceRefs(walkEasy = ref(RefKind.WALK_EASY, 3.8)))
        assertEquals(3.8, Physio.speedsFor(easyOnly).walk, 1e-9)
    }

    /** Les vitesses fixes d'un programme importé gardent leur sens, quelles que soient les références. */
    @Test
    fun fixedSpeedsAreUnchanged() {
        val c = ImportedProgramTest.merged(ImportedProgramTest.load("courir-30-min-tapis.json"))
        val t = c.program("courir-30-min-tapis")!!
        val calibrated = legacy.copy(paceRefs = PaceRefs(ref(RefKind.WALK_EASY, 3.2), ref(RefKind.WALK_ACTIVE, 4.6), ref(RefKind.RUN_EASY, 7.9)))
        val a = Coach.plan(Coach.newProgram(t, legacy, today, c), legacy, today).first()
        val b = Coach.plan(Coach.newProgram(t, calibrated, today, c), calibrated, today).first()
        assertEquals(a.blocks.map { it.speedKmh }, b.blocks.map { it.speedKmh })
        assertEquals(listOf(4.0, 5.5, 4.3), b.blocks.take(3).map { it.speedKmh })
    }

    /** Pas et bornes du tapis, et conflits expliqués. */
    @Test
    fun treadmillBoundsAndConflicts() {
        val slow = legacy.copy(treadmillMinKmh = 2.0, paceRefs = PaceRefs(walkEasy = ref(RefKind.WALK_EASY, 1.5)))
        val c = Catalogs.builtin
        val walk = Templates.build(c.type("walk")!!, mapOf("minutes" to 20.0), slow, Physio.speedsFor(slow), today, 0, false, false, 0)
        assertEquals(2.0, walk.blocks.first().speedKmh, 1e-9)
        assertTrue(walk.warnings.single().contains("sous la vitesse minimale du tapis"))
        val coarse = legacy.copy(treadmillStepKmh = 0.5, paceRefs = PaceRefs(ref(RefKind.WALK_EASY, 3.6), ref(RefKind.WALK_ACTIVE, 5.1), ref(RefKind.RUN_EASY, 7.4)))
        val w2 = Templates.build(c.type("walk")!!, mapOf("minutes" to 20.0), coarse, Physio.speedsFor(coarse), today, 0, false, false, 0)
        assertEquals(listOf(3.5, 5.0, 3.5), w2.blocks.map { it.speedKmh })
    }

    /** Sortie longue à objectif de distance : durée = 60 × km / vitesse retenue, recalculée avec les références. */
    @Test
    fun distanceGoalUsesRetainedSpeed() {
        assertEquals(40.0, Distances.minutes(5.0, 7.5), 1e-9)
        assertEquals(50.0, Distances.minutes(5.0, 6.0), 1e-9)
        assertEquals(43.0, Distances.minutes(5.0, 7.0), 1e-9)
        val t = Catalogs.builtin.program("run-5k")!!
        val runner = legacy.copy(level = Level.N4, vr = 6.4, longestRunMin = 40, paceRefs = PaceRefs(runEasy = ref(RefKind.RUN_EASY, 7.5, RefStatus.CONFIRMED, 600)))
        val p0 = Coach.newProgram(t, runner, today)
        val last = p0.copy(stepIndex = p0.steps.size - 1)
        assertEquals(5.0, last.steps.last().sessions.first().params["km"])
        val s = Coach.sessionFor(last, runner, today)
        assertEquals(SessionKind.CONTINUOUS, s.kind)
        assertEquals(40 * 60, s.blocks.first { it.kind == BlockKind.RUN }.seconds)
        // Nouvelle référence plus lente : la même distance prend plus de temps, sans redémarrer le programme.
        val slower = runner.copy(paceRefs = PaceRefs(runEasy = ref(RefKind.RUN_EASY, 6.6, RefStatus.CONFIRMED, 600)))
        assertEquals(46 * 60, Coach.sessionFor(last, slower, today).blocks.first { it.kind == BlockKind.RUN }.seconds)
        // Trot plus rapide que le tapis : vitesse plafonnée, durée calculée sur la vitesse plafonnée, conflit expliqué.
        val capped = runner.copy(treadmillMaxKmh = 10.0, paceRefs = PaceRefs(runEasy = ref(RefKind.RUN_EASY, 12.0, RefStatus.CONFIRMED, 600)))
        val sc = Coach.sessionFor(last, capped, today)
        assertEquals(10.0, sc.blocks.first { it.kind == BlockKind.RUN }.speedKmh, 1e-9)
        assertEquals(30 * 60, sc.blocks.first { it.kind == BlockKind.RUN }.seconds)
        assertTrue(sc.warnings.any { "maximale du tapis" in it })
    }

    /** Programme démarré avant cette version (paliers sans distance) : l'objectif en km est retrouvé dans sa version. */
    @Test
    fun oldProgramRecoversItsDistanceGoal() {
        val t = Catalogs.builtin.program("run-10k")!!
        val runner = legacy.copy(level = Level.N4, vr = 6.4, longestRunMin = 40)
        val fresh = Coach.newProgram(t, runner, today)
        val old = fresh.copy(steps = fresh.steps.map { st -> st.copy(sessions = st.sessions.map { it.copy(params = it.params - "km") }) }, stepIndex = fresh.steps.size - 1)
        assertFalse(old.steps.last().sessions.first().params.containsKey("km"))
        val restored = Coach.restoreDistances(old, runner)
        assertEquals(10.0, restored.steps.last().sessions.first().params["km"])
        assertEquals(old.stepIndex, restored.stepIndex)
        val calibrated = runner.copy(paceRefs = PaceRefs(runEasy = ref(RefKind.RUN_EASY, 8.0, RefStatus.CONFIRMED, 600)))
        assertEquals(75 * 60, Coach.sessionFor(restored, calibrated, today).blocks.first { it.kind == BlockKind.RUN }.seconds)
        // Rien à faire pour un programme sans objectif de distance.
        val walkProgram = Coach.newProgram(Catalogs.builtin.program("run-30")!!, runner, today)
        assertEquals(walkProgram, Coach.restoreDistances(walkProgram, runner))
    }

    /** Ancien profil (fichier d'une version précédente) : aucune référence, aucun champ obligatoire nouveau. */
    @Test
    fun oldStateFileStillLoads() {
        val old = """{"profile":{"age":52,"vr":5.3,"level":"N2","treadmillMaxKmh":14.0},"sessions":[]}"""
        val s = Catalogs.json.decodeFromString(AppState.serializer(), old)
        val p = s.profile!!
        assertTrue(p.paceRefs.isEmpty)
        assertEquals(5.3, p.vr, 1e-9)
        assertEquals(1.0, p.treadmillMinKmh, 1e-9)
        assertFalse(p.treadmillChecked)
        assertTrue(s.calibrations.isEmpty())
        assertNull(s.paceTest)
        // L'ancienne vitesse n'est pas transformée en trois références confirmées.
        assertNull(PaceBasis.of(p).walkActive)
    }

    @Test
    fun mergeRulesAreConservative() {
        val confirmed = ref(RefKind.WALK_ACTIVE, 5.4, RefStatus.CONFIRMED, 600)
        val base = PaceRefs(walkActive = confirmed)
        // Provisoire plus rapide : ne remplace pas une référence confirmée, reste une candidate.
        val (r1, kept) = base.merge(listOf(ref(RefKind.WALK_ACTIVE, 5.7)))
        assertEquals(confirmed, r1.walkActive)
        assertEquals(1, kept.size)
        // Provisoire plus lente : remplace (prudence).
        assertEquals(5.1, base.merge(listOf(ref(RefKind.WALK_ACTIVE, 5.1))).first.walkActive!!.kmh, 1e-9)
        // Saisie manuelle : toujours retenue, et identifiable.
        val manual = base.merge(listOf(ref(RefKind.WALK_ACTIVE, 6.0, source = RefSource.MANUAL))).first.walkActive!!
        assertEquals(RefSource.MANUAL, manual.source)
    }
}
