package fr.relance.app.core

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Déroulé d'une séance, seconde par seconde. Sans dépendance Android : l'écran appelle [tick]
 * une fois par seconde avec ce que les capteurs ont mesuré.
 */
class SessionEngine(val session: PlannedSession, private val profile: Profile) {
    var blocks: List<Block> = session.blocks
        private set
    var index = 0
        private set
    var blockElapsed = 0
        private set
    var elapsed = 0
        private set
    var distanceM = 0.0
        private set
    var paused = false
    var finished = false
        private set
    var speedMeasured = false
        private set
    var lastHr = 0
        private set
    var lastSpeed = 0.0
        private set
    val samples = ArrayList<Sample>()

    private val testSpeeds = ArrayList<Double>()
    private var runStreak = 0
    private var bestRunStreak = 0
    private var runBlocksDone = 0
    private val postHr = ArrayList<Int>()

    val current: Block get() = blocks[index.coerceAtMost(blocks.size - 1)]
    val next: Block? get() = blocks.getOrNull(index + 1)
    val remainingInBlock: Int get() = max(0, current.seconds - blockElapsed)
    val totalSeconds: Int get() = blocks.sumOf { it.seconds }

    /** Vitesse qui anime le coureur : celle du tapis s'il est connecté, sinon la consigne. */
    fun displaySpeed(measured: Double?): Double = when {
        finished || paused -> 0.0
        measured != null -> measured
        else -> current.speedKmh
    }

    /** Avance d'une seconde. Renvoie true si on vient de changer de bloc. */
    fun tick(measuredSpeed: Double?, hr: Int?, measuredIncline: Double?): Boolean {
        if (finished || paused) return false
        val block = current
        val speed = measuredSpeed ?: block.speedKmh
        if (measuredSpeed != null) speedMeasured = true
        val incline = measuredIncline ?: block.inclinePct
        lastSpeed = speed
        lastHr = hr ?: 0
        distanceM += speed / 3.6
        elapsed++
        blockElapsed++
        samples += Sample(elapsed, speed.toFloat(), hr ?: 0, incline.toFloat())
        if (block.kind == BlockKind.TEST && blockElapsed > 120 && measuredSpeed != null) testSpeeds += measuredSpeed
        if (block.kind == BlockKind.RUN || block.kind == BlockKind.FAST) {
            runStreak++
            bestRunStreak = max(bestRunStreak, runStreak)
        } else runStreak = 0

        if (blockElapsed >= block.seconds) {
            if (block.kind == BlockKind.RUN || block.kind == BlockKind.FAST) runBlocksDone++
            if (index >= blocks.size - 1) {
                finished = true
            } else {
                index++
                blockElapsed = 0
            }
            return true
        }
        return false
    }

    /** Arrêt avant la fin, à la demande de l'utilisateur. */
    fun stop() {
        finished = true
    }

    /** « Trop dur » : la suite de la séance est allégée de 7 %. */
    fun easeOff() {
        blocks = blocks.mapIndexed { i, b ->
            if (i >= index && b.kind != BlockKind.WARMUP && b.kind != BlockKind.COOLDOWN)
                b.copy(speedKmh = Physio.round1(b.speedKmh * 0.93)) else b
        }
    }

    /** Fréquence cardiaque relevée après la fin, pour la récupération à une minute. */
    fun recordPostHr(hr: Int) {
        if (hr > 0) postHr += hr
    }

    /** Vitesse moyenne tenue sur la fin du bloc de test, 0 si elle n'a pas été mesurée. */
    fun measuredTestSpeed(): Double = if (testSpeeds.size < 30) 0.0 else Physio.round1(testSpeeds.average())

    fun summary(id: String, startMillis: Long, day: Long, program: Program?): CompletedSession {
        val hrs = samples.filter { it.hr > 0 }.map { it.hr }
        val zones = IntArray(5)
        var kcal = 0.0
        var climb = 0.0
        for (s in samples) {
            val z = Physio.zoneOf(s.hr, profile)
            if (z > 0) zones[z - 1]++
            kcal += Physio.kcalPerMin(s.speed.toDouble(), s.incline.toDouble(), profile.weightKg) / 60
            climb += s.speed / 3.6 * s.incline / 100
        }
        val endHr = samples.takeLast(10).filter { it.hr > 0 }.map { it.hr }.maxOrNull() ?: 0
        val recovery = if (endHr > 0 && postHr.size >= 55) max(0, endHr - postHr.last()) else 0
        return CompletedSession(
            id = id, startMillis = startMillis, day = day,
            programId = program?.id ?: "", programLabel = program?.type?.label ?: "", week = session.week,
            kind = session.kind, title = session.title, detail = session.detail,
            plannedSeconds = session.blocks.sumOf { it.seconds },
            durationSec = elapsed, distanceM = distanceM,
            avgSpeed = if (elapsed > 0) distanceM / elapsed * 3.6 else 0.0,
            maxSpeed = samples.maxOfOrNull { it.speed.toDouble() } ?: 0.0,
            avgHr = if (hrs.isEmpty()) 0 else hrs.average().roundToInt(),
            maxHr = hrs.maxOrNull() ?: 0,
            zoneSeconds = zones.toList(), recovery1Min = recovery,
            calories = kcal.roundToInt(), elevationM = climb,
            runBlocksPlanned = session.blocks.count { it.kind == BlockKind.RUN || it.kind == BlockKind.FAST },
            runBlocksDone = runBlocksDone, longestRunMin = bestRunStreak / 60,
            speedMeasured = speedMeasured,
        )
    }
}

/** Cumuls et records affichés dans l'historique. */
object Stats {
    data class Week(val startDay: Long, val minutes: Int, val sessions: Int)
    data class Totals(val sessions: Int, val seconds: Int, val distanceM: Double, val calories: Int)
    data class Records(val longestRunMin: Int, val longestDistanceM: Double, val longestSessionSec: Int)

    fun monday(day: Long): Long = day - (Coach.dayOfWeek(day) - 1)

    fun lastWeeks(sessions: List<CompletedSession>, today: Long, count: Int = 4): List<Week> {
        val thisMonday = monday(today)
        return (count - 1 downTo 0).map { back ->
            val start = thisMonday - 7L * back
            val inWeek = sessions.filter { it.day in start until start + 7 }
            Week(start, inWeek.sumOf { it.durationSec } / 60, inWeek.size)
        }
    }

    fun totals(sessions: List<CompletedSession>) = Totals(
        sessions.size, sessions.sumOf { it.durationSec }, sessions.sumOf { it.distanceM }, sessions.sumOf { it.calories },
    )

    fun records(sessions: List<CompletedSession>) = Records(
        sessions.maxOfOrNull { it.longestRunMin } ?: 0,
        sessions.maxOfOrNull { it.distanceM } ?: 0.0,
        sessions.maxOfOrNull { it.durationSec } ?: 0,
    )
}
