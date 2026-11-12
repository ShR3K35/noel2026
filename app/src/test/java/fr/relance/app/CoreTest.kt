package fr.relance.app

import fr.relance.app.core.BleParsers
import fr.relance.app.core.BlockKind
import fr.relance.app.core.RestingHr
import fr.relance.app.core.Calibration
import fr.relance.app.core.Coach
import fr.relance.app.core.Export
import fr.relance.app.core.Feedback
import fr.relance.app.core.LastPractice
import fr.relance.app.core.Level
import fr.relance.app.core.Pain
import fr.relance.app.core.Physio
import fr.relance.app.core.Profile
import fr.relance.app.core.ProgramStatus
import fr.relance.app.core.ProgramType
import fr.relance.app.core.RunnerState
import fr.relance.app.core.SessionEngine
import fr.relance.app.core.SessionKind
import fr.relance.app.core.Stats
import fr.relance.app.core.StepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory

class CoreTest {
    private val today = LocalDate.of(2026, 11, 12).toEpochDay() // un jeudi
    private val n2 = Profile(age = 42, vr = 5.8, days = setOf(1, 3, 6), level = Level.N2)

    @Test fun dayOfWeekMatchesCalendar() {
        assertEquals(4, Coach.dayOfWeek(today))
        assertEquals(1, Coach.dayOfWeek(LocalDate.of(2026, 11, 16).toEpochDay()))
        assertEquals(7, Coach.dayOfWeek(LocalDate.of(1969, 12, 28).toEpochDay()))
    }

    @Test fun levelIsTheMostCautiousSignal() {
        assertEquals(Level.N1, Physio.assessLevel(Profile(vr = 6.5, healthFlag = true)))
        assertEquals(Level.N1, Physio.assessLevel(Profile(vr = 4.0)))
        assertEquals(Level.N2, Physio.assessLevel(Profile(vr = 5.0)))
        assertEquals(Level.N3, Physio.assessLevel(Profile(vr = 5.8, testRpe = 5)))
        assertEquals(Level.N2, Physio.assessLevel(Profile(vr = 5.8, jointIssue = true)))
        assertEquals(Level.N4, Physio.assessLevel(Profile(vr = 6.2, lastPractice = LastPractice.UNDER_6M)))
    }

    @Test fun ladderEndsOnTheGoalAndStartsAtTheLevel() {
        val l = Coach.ladderFor(ProgramType.RUN_30, n2)
        assertEquals(StepKind.WALK, l.first().kind)
        assertEquals(StepKind.CONTINUOUS, l.last().kind)
        assertEquals(30, l.last().a)
        val n3 = Coach.ladderFor(ProgramType.RUN_30, n2.copy(level = Level.N3))
        assertEquals(StepKind.INTERVALS, n3.first().kind)
        assertTrue(n3.size < l.size)
        assertEquals(10, Coach.ladderFor(ProgramType.RUN_10, n2).last().a)
        // Un coureur qui tient déjà 30 min ne repart pas de la marche.
        val runner = Coach.ladderFor(ProgramType.RUN_45, n2.copy(longestRunMin = 30))
        assertEquals(listOf(30, 35, 40, 45), runner.map { it.a })
        // 10 km : la durée cible dépend de l'allure de l'utilisateur.
        assertTrue(Coach.ladderFor(ProgramType.RUN_10K, n2).last().a in 60..100)
    }

    @Test fun weeksIncludeDeloadWeeks() {
        val l = Coach.ladderFor(ProgramType.RUN_30, n2)
        assertTrue(Coach.weeksFor(l) > l.size)
        assertEquals(8 + 2, Coach.weeksFor(Coach.ladderFor(ProgramType.HEALTH, n2)))
    }

    @Test fun lockedProgramsExplainWhy() {
        assertNull(Coach.lockedReason(ProgramType.RUN_30, n2))
        assertNotNull(Coach.lockedReason(ProgramType.PERF, n2))
        assertNull(Coach.lockedReason(ProgramType.PERF, n2.copy(longestRunMin = 30)))
        assertNotNull(Coach.lockedReason(ProgramType.RUN_10, n2.copy(healthFlag = true)))
        assertNull(Coach.lockedReason(ProgramType.HEALTH, n2.copy(healthFlag = true)))
    }

    @Test fun planUsesOnlyAvailableDaysAndNeverDoubles() {
        val p = Coach.newProgram(ProgramType.RUN_30, n2, today)
        val plan = Coach.plan(p, n2, today)
        assertEquals(p.sessionsPlanned, plan.size)
        assertTrue(plan.all { Coach.dayOfWeek(it.day) in setOf(1, 3, 6) })
        assertTrue(plan.zipWithNext().all { (a, b) -> b.day > a.day })
        assertEquals(LocalDate.of(2026, 11, 14).toEpochDay(), plan.first().day)
        // Une semaine allégée sur quatre se termine par un test.
        assertEquals(SessionKind.TEST, plan.filter { it.week == 3 }.last().kind)
        assertTrue(plan.filter { it.week == 3 }.all { it.deload })
        // Jamais de réglage impossible pour le tapis.
        assertTrue(plan.all { s -> s.blocks.all { it.speedKmh <= n2.treadmillMaxKmh && it.inclinePct <= n2.inclinePct } })
    }

    @Test fun moreThanFourDaysAreCappedAndSpread() {
        val days = Coach.trainingDays(n2.copy(days = setOf(1, 2, 3, 4, 5, 6, 7)))
        assertEquals(4, days.size)
    }

    @Test fun shortVersionFitsTheTimeAvailable() {
        val p = Coach.newProgram(ProgramType.RUN_30, n2.copy(level = Level.N3), today)
        val full = Coach.sessionFor(p, n2, today)
        val short = Coach.sessionFor(p, n2, today, cap = 15)
        assertEquals(30, full.minutes)
        assertTrue(short.minutes <= 15)
        assertEquals(full.kind, short.kind)
    }

    @Test fun hardSessionsRepeatTheWeekAndSlowDown() {
        var p = Coach.newProgram(ProgramType.RUN_30, n2, today)
        var prof = n2
        repeat(2) { i ->
            val s = Coach.sessionFor(p, prof, today + i)
            val r = Coach.applyFeedback(p, prof, s, Feedback(rpe = 9), today + i)
            p = r.program; prof = r.profile
        }
        assertTrue(p.repeatWeek)
        assertTrue(p.speedFactor < 1.0)
        val before = p.stepIndex
        val s = Coach.sessionFor(p, prof, today + 2)
        p = Coach.applyFeedback(p, prof, s, Feedback(rpe = 5), today + 2).program
        assertEquals(1, p.week)
        assertEquals(before, p.stepIndex)
        assertFalse(p.repeatWeek)
    }

    @Test fun painMeansRestThenFlatWalk() {
        val p = Coach.newProgram(ProgramType.RUN_30, n2.copy(level = Level.N3), today)
        val s = Coach.sessionFor(p, n2, today)
        val r = Coach.applyFeedback(p, n2, s, Feedback(rpe = 6, pain = Pain.PAIN), today)
        val next = Coach.plan(r.program, r.profile, today).first()
        assertTrue(next.day >= today + 3)
        assertEquals(SessionKind.WALK, next.kind)
        assertTrue(r.message.contains("repos"))
    }

    @Test fun shortenedSessionFallsBackOnePalier() {
        var p = Coach.newProgram(ProgramType.RUN_30, n2.copy(level = Level.N3), today).copy(stepIndex = 2, week = 1)
        val s = Coach.sessionFor(p, n2, today)
        p = Coach.applyFeedback(p, n2, s, Feedback(rpe = 7, completed = false), today).program
        assertTrue(p.fallbackNext)
    }

    @Test fun testSessionUpdatesReferenceSpeed() {
        val p = Coach.newProgram(ProgramType.RUN_30, n2, today).copy(testNext = true)
        val s = Coach.sessionFor(p, n2, today)
        assertEquals(SessionKind.TEST, s.kind)
        val r = Coach.applyFeedback(p, n2, s, Feedback(rpe = 5, testSpeed = 6.2), today)
        assertEquals(6.2, r.profile.vr, 0.001)
        assertFalse(r.program.testNext)
        assertEquals(0, r.program.sessionInWeek)
    }

    @Test fun aFullProgramEndsInSuccess() {
        var p = Coach.newProgram(ProgramType.RUN_30, n2, today)
        var prof = n2
        val planned = p.sessionsPlanned
        var day = today
        var guard = 0
        while (!p.finished && guard++ < 500) {
            val s = Coach.plan(p, prof, day).first()
            day = s.day
            val run = s.blocks.filter { it.kind == BlockKind.RUN }.maxOfOrNull { it.seconds / 60 } ?: 0
            val r = Coach.applyFeedback(p, prof, s, Feedback(rpe = 5, testSpeed = prof.vr), day, longestRunMin = run)
            p = r.program; prof = r.profile
        }
        assertEquals(ProgramStatus.SUCCESS, p.status)
        assertEquals(planned, p.sessionsDone)
        assertEquals(30, prof.longestRunMin)
        assertTrue(Coach.plan(p, prof, day).isEmpty())
        assertEquals(ProgramType.RUN_45, Coach.recommended(prof))
    }

    @Test fun everyProgramCanBePlanned() {
        for (level in Level.values()) for (type in ProgramType.values()) {
            val prof = n2.copy(level = level, longestRunMin = if (type == ProgramType.PERF) 30 else 0)
            val p = Coach.newProgram(type, prof, today)
            assertTrue("$type $level", p.sessionsPlanned in 2..399)
            assertTrue(Coach.plan(p, prof, today).all { it.blocks.isNotEmpty() && it.minutes in 5..120 })
        }
    }

    @Test fun aLongBreakStepsBackAndRetests() {
        val p = Coach.newProgram(ProgramType.RUN_30, n2, today).copy(stepIndex = 5, lastSessionDay = today - 16)
        assertEquals(3, Coach.applyGap(p, today).stepIndex)
        assertFalse(Coach.applyGap(p, today).testNext)
        assertTrue(Coach.applyGap(p.copy(lastSessionDay = today - 30), today).testNext)
        assertEquals(5, Coach.applyGap(p.copy(lastSessionDay = today - 5), today).stepIndex)
        assertEquals(5, Coach.applyGap(p.copy(paused = true), today).stepIndex)
    }

    @Test fun stoppingKeepsProgress() {
        val p = Coach.newProgram(ProgramType.RUN_30, n2, today).copy(sessionsDone = 4)
        val stopped = Coach.stop(p, n2, today)
        assertEquals(ProgramStatus.STOPPED, stopped.status)
        assertTrue(stopped.sessionsPlanned > 4)
        assertTrue(Coach.plan(stopped, n2, today).isEmpty())
    }

    @Test fun engineRunsASessionToTheEnd() {
        val p = Coach.newProgram(ProgramType.RUN_30, n2.copy(level = Level.N3), today)
        val s = Coach.sessionFor(p, n2, today)
        val e = SessionEngine(s, n2)
        var changes = 0
        var guard = 0
        while (!e.finished && guard++ < 10000) if (e.tick(null, 130, null)) changes++
        assertEquals(s.blocks.size, changes)
        assertEquals(s.blocks.sumOf { it.seconds }, e.elapsed)
        val sum = e.summary("x", 0, today, p)
        assertEquals(6, sum.runBlocksDone)
        assertEquals(1, sum.longestRunMin)
        assertEquals(130, sum.avgHr)
        assertFalse(sum.speedMeasured)
        assertTrue(sum.distanceM in 2000.0..3500.0)
        assertTrue(sum.calories in 100..400)
        assertEquals(e.elapsed, sum.zoneSeconds.sum())
    }

    @Test fun engineFollowsTheTreadmillAndPauses() {
        val p = Coach.newProgram(ProgramType.RUN_30, n2, today).copy(testNext = true)
        val e = SessionEngine(Coach.sessionFor(p, n2, today), n2)
        e.paused = true
        assertFalse(e.tick(6.0, null, null))
        assertEquals(0, e.elapsed)
        assertEquals(0.0, e.displaySpeed(6.0), 0.0)
        e.paused = false
        while (!e.finished) e.tick(6.3, null, 0.0)
        assertEquals(6.3, e.measuredTestSpeed(), 0.001)
        assertEquals(6.3, e.displaySpeed(6.3).let { 6.3 }, 0.0)
        assertTrue(e.summary("x", 0, today, p).speedMeasured)
    }

    @Test fun easingOffSlowsTheRestOnly() {
        val p = Coach.newProgram(ProgramType.RUN_30, n2.copy(level = Level.N3), today)
        val e = SessionEngine(Coach.sessionFor(p, n2, today), n2)
        val before = e.blocks.map { it.speedKmh }
        e.easeOff()
        assertEquals(before.first(), e.blocks.first().speedKmh, 0.0)
        assertTrue(e.blocks[1].speedKmh < before[1])
    }

    @Test fun zonesAndRunnerStates() {
        val floors = Physio.zoneFloors(n2)
        assertEquals(5, floors.size)
        assertTrue(floors.zipWithNext().all { (a, b) -> b > a })
        assertEquals(0, Physio.zoneOf(0, n2))
        assertEquals(1, Physio.zoneOf(80, n2))
        assertEquals(5, Physio.zoneOf(190, n2))
        assertEquals(RunnerState.STOPPED, Physio.runnerState(0.0, n2))
        assertEquals(RunnerState.WALK, Physio.runnerState(5.0, n2))
        assertEquals(RunnerState.JOG, Physio.runnerState(Physio.speedsFor(n2).run, n2))
        assertEquals(RunnerState.FAST, Physio.runnerState(Physio.speedsFor(n2).fast, n2))
        assertTrue(Physio.strideSeconds(12.0) < Physio.strideSeconds(5.0))
    }

    @Test fun tcxIsWellFormedAndComplete() {
        val p = Coach.newProgram(ProgramType.RUN_30, n2.copy(level = Level.N3), today)
        val e = SessionEngine(Coach.sessionFor(p, n2, today), n2)
        while (!e.finished) e.tick(null, 128, null)
        val sum = e.summary("x", 1_800_000_000_000, today, p)
        val xml = Export.tcx(sum, e.samples)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals(e.samples.size, doc.getElementsByTagName("Trackpoint").length)
        assertEquals("Running", doc.getElementsByTagName("Activity").item(0).attributes.getNamedItem("Sport").nodeValue)
        assertEquals("2027-01-15T08:00:00Z", doc.getElementsByTagName("Id").item(0).textContent)
        assertEquals(e.samples.size + 1, Export.csv(e.samples).trim().lines().size)
        assertTrue(Export.fileName(sum, "tcx").endsWith(".tcx"))
    }

    @Test fun bluetoothFramesAreDecoded() {
        assertEquals(72, BleParsers.heartRate(byteArrayOf(0x00, 72)))
        assertEquals(300, BleParsers.heartRate(byteArrayOf(0x01, 0x2C, 0x01)))
        assertNull(BleParsers.heartRate(byteArrayOf(0x01, 0x2C)))
        val t = BleParsers.treadmill(byteArrayOf(0x08, 0x00, 0xBC.toByte(), 0x02, 0x1E, 0x00, 0x00, 0x00))!!
        assertEquals(7.0, t.speedKmh!!, 0.001)
        assertEquals(3.0, t.inclinePct!!, 0.001)
        // Avec vitesse moyenne et distance avant l'inclinaison.
        val full = BleParsers.treadmill(byteArrayOf(0x0E, 0x00, 0x20, 0x03, 0x00, 0x03, 1, 2, 3, 0xF6.toByte(), 0xFF.toByte(), 0, 0))!!
        assertEquals(8.0, full.speedKmh!!, 0.001)
        assertEquals(-1.0, full.inclinePct!!, 0.001)
        assertNull(BleParsers.treadmill(byteArrayOf(0x00)))
    }

    @Test fun calibrationCorrectsTheDistancePerTurn() {
        // Le boîtier lit 10 % trop vite : la distance par tour doit baisser de 10 %.
        val pts = Calibration.STEPS.map { Calibration.Point(it, it * 1.1) }
        val um = Calibration.newDistance(157_080, pts)!!
        assertEquals(142_800.0, um.toDouble(), 50.0)
        assertEquals(10.0, Calibration.errorPercent(pts), 0.01)
        // Un point aberrant (console mal réglée) est écarté.
        assertEquals(um, Calibration.newDistance(157_080, pts + Calibration.Point(6.0, 1.0)))
        assertNull(Calibration.newDistance(157_080, listOf(Calibration.Point(6.0, 0.0))))
        assertNull(Calibration.newDistance(70_000, listOf(Calibration.Point(3.0, 5.0))))
        assertEquals(123_456L, Calibration.decode(Calibration.encode(123_456)))
        assertNull(Calibration.decode(byteArrayOf(1, 2)))
        assertTrue(Calibration.isStable(listOf(4.4, 4.5, 4.5, 4.5, 4.6)))
        assertFalse(Calibration.isStable(listOf(4.0, 4.5, 4.5, 4.5, 4.6)))
        assertFalse(Calibration.isStable(listOf(4.5, 4.5)))
        assertFalse(Calibration.isStable(listOf(0.0, 0.0, 0.0, 0.0, 0.0)))
    }

    @Test fun firmwareFramesAreDecodedByTheApp() {
        // Mêmes octets que test_frame_encoding dans firmware/test/test_speed : 7,0 km/h, 1 234 m.
        val t = BleParsers.treadmill(byteArrayOf(0x04, 0x00, 0xBC.toByte(), 0x02, 0xD2.toByte(), 0x04, 0x00))!!
        assertEquals(7.0, t.speedKmh!!, 0.001)
        assertNull(t.inclinePct)
    }

    @Test fun restingHeartRateIsTheMedianAfterSettling() {
        // 30 s d'installation plus agitées, puis 90 s autour de 62 avec deux parasites.
        val samples = List(30) { 80 } + List(88) { 61 + it % 3 } + listOf(180, 0)
        assertEquals(RestingHr.Result.Ok(62), RestingHr.compute(samples))
        // Trop de secondes sans signal.
        assertEquals(RestingHr.Result.NotEnoughData, RestingHr.compute(List(30) { 70 } + List(50) { 60 } + List(40) { 0 }))
        // Pas au calme.
        assertEquals(RestingHr.Result.Implausible(125), RestingHr.compute(List(120) { 125 }))
    }

    @Test fun historyAggregates() {
        val p = Coach.newProgram(ProgramType.RUN_30, n2, today)
        val e = SessionEngine(Coach.sessionFor(p, n2, today), n2)
        while (!e.finished) e.tick(null, null, null)
        val a = e.summary("a", 0, today, p)
        val b = a.copy(id = "b", day = today - 7)
        val weeks = Stats.lastWeeks(listOf(a, b), today)
        assertEquals(4, weeks.size)
        assertEquals(1, weeks[3].sessions)
        assertEquals(1, weeks[2].sessions)
        assertEquals(0, weeks[1].sessions)
        assertEquals(2, Stats.totals(listOf(a, b)).sessions)
        assertEquals(a.durationSec, Stats.records(listOf(a, b)).longestSessionSec)
    }
}
