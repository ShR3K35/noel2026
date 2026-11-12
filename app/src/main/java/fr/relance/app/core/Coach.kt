package fr.relance.app.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Résultat d'un bilan de séance : nouvel état et ce que le coach en dit. */
data class FeedbackResult(val program: Program, val profile: Profile, val message: String)

/**
 * Le moteur de coaching. Un programme est une échelle de paliers ; chaque semaine joue un palier,
 * une semaine sur quatre est allégée, et les retours de séance déplacent l'utilisateur sur l'échelle.
 */
object Coach {
    /** Paliers marche / course vers une course continue longue. */
    private val INTERVALS = listOf(
        Step(StepKind.INTERVALS, 1, 3, 6), Step(StepKind.INTERVALS, 2, 2, 6), Step(StepKind.INTERVALS, 3, 2, 5),
        Step(StepKind.INTERVALS, 5, 2, 4), Step(StepKind.INTERVALS, 8, 2, 3),
    )

    /** Version courte, pour l'objectif « courir 10 min ». */
    private val INTERVALS_SHORT = listOf(
        Step(StepKind.INTERVALS, 1, 3, 6), Step(StepKind.INTERVALS, 2, 2, 6),
        Step(StepKind.INTERVALS, 4, 2, 4), Step(StepKind.INTERVALS, 6, 2, 3),
    )
    private val CONTINUOUS = listOf(15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 70, 80, 90, 100)

    // ---------- Emploi du temps ----------

    /** Jours retenus pour s'entraîner : 4 au maximum en reprise, répartis dans la semaine. */
    fun trainingDays(p: Profile): List<Int> {
        val d = p.days.filter { it in 1..7 }.sorted().ifEmpty { listOf(1, 3, 6) }
        if (d.size <= 4) return d
        return (0 until 4).map { d[min(d.size - 1, (it * d.size / 4.0).roundToInt())] }.distinct()
    }

    fun sessionsPerWeek(p: Profile): Int = trainingDays(p).size

    /** Jour ISO (1 = lundi) d'un jour compté depuis le 1er janvier 1970. */
    fun dayOfWeek(epochDay: Long): Int = Math.floorMod(epochDay + 3, 7L).toInt() + 1

    // ---------- Catalogue ----------

    fun targetMinutes(type: ProgramType, p: Profile): Int {
        val run = Physio.speedsFor(p).run
        return when (type) {
            ProgramType.RUN_10 -> 10
            ProgramType.RUN_30 -> 30
            ProgramType.RUN_45 -> 45
            ProgramType.RUN_60 -> 60
            ProgramType.RUN_5K -> ceil(5 / run * 60).toInt()
            ProgramType.RUN_10K -> ceil(10 / run * 60).toInt()
            else -> 0
        }
    }

    /** Raison pour laquelle un programme n'est pas encore accessible, ou null s'il l'est. */
    fun lockedReason(type: ProgramType, p: Profile): String? = when {
        type == ProgramType.HEALTH -> null
        p.healthFlag -> "Avis médical conseillé avant de courir"
        type == ProgramType.PERF && p.longestRunMin < 30 -> "Après 30 min de course continue"
        else -> null
    }

    fun recommended(p: Profile): ProgramType = when {
        p.healthFlag -> ProgramType.HEALTH
        p.longestRunMin >= 60 -> ProgramType.PERF
        p.longestRunMin >= 45 -> ProgramType.RUN_60
        p.longestRunMin >= 30 -> ProgramType.RUN_45
        p.longestRunMin >= 10 -> ProgramType.RUN_30
        p.level == Level.N1 -> ProgramType.RUN_10
        else -> ProgramType.RUN_30
    }

    fun ladderFor(type: ProgramType, p: Profile): List<Step> {
        if (type == ProgramType.HEALTH) {
            val base = if (p.level == Level.N1) 15 else 20
            return listOf(0, 5, 5, 10, 10, 15, 15, 20).map { Step(StepKind.WALK, base + it) }
        }
        if (type == ProgramType.PERF) return (0 until 8).map { Step(StepKind.PERF, it) }

        val target = targetMinutes(type, p)
        val conts = if (target <= 10) listOf(10) else CONTINUOUS.filter { it < target } + target
        if (p.longestRunMin >= 10) {
            val start = min(p.longestRunMin, target) / 5 * 5
            return (listOf(max(10, start)) + conts.filter { it > p.longestRunMin })
                .map { Step(StepKind.CONTINUOUS, it) }
        }
        val short = target <= 10
        val walks = when (p.level) {
            Level.N1 -> if (short) listOf(15, 20) else listOf(15, 20, 25)
            Level.N2 -> if (short) listOf(20) else listOf(20, 25)
            else -> emptyList()
        }.map { Step(StepKind.WALK, it) }
        val all = if (short) INTERVALS_SHORT else INTERVALS
        // Un ancien coureur saute les premiers paliers de marche / course.
        val intervals = if (p.level == Level.N4) all.drop(all.size - 2) else all
        return walks + intervals + conts.map { Step(StepKind.CONTINUOUS, it) }
    }

    /** Durée en semaines, semaines allégées comprises. */
    fun weeksFor(ladder: List<Step>): Int {
        var week = 0
        var step = 0
        while (step < ladder.size) {
            if (!(week % 4 == 3 && step > 0)) step++
            week++
        }
        return week
    }

    /** Durée de la plus longue séance du programme, pour prévenir si elle dépasse le temps disponible. */
    fun longestSessionMinutes(ladder: List<Step>): Int = ladder.maxOfOrNull {
        when (it.kind) {
            StepKind.WALK -> it.a
            StepKind.INTERVALS -> 6 + it.reps * (it.a + it.b)
            StepKind.CONTINUOUS -> it.a + 10
            StepKind.PERF -> 45
        }
    } ?: 0

    fun newProgram(type: ProgramType, p: Profile, today: Long): Program {
        val ladder = ladderFor(type, p)
        val draft = Program(
            id = "p$today-${type.name}-${System.nanoTime() % 100000}",
            type = type, startDay = today, ladder = ladder, vrStart = p.vr,
            weeksPlanned = weeksFor(ladder),
        )
        return draft.copy(sessionsPlanned = plan(draft, p, today).size)
    }

    // ---------- Construction des séances ----------

    private fun isDeload(p: Program): Boolean = p.week % 4 == 3 && p.stepIndex > 0

    private fun reduced(s: Step): Step = when (s.kind) {
        StepKind.INTERVALS -> s.copy(reps = max(2, ceil(s.reps * 0.7).toInt()))
        StepKind.PERF -> s
        else -> s.copy(a = max(10, (s.a * 0.7 / 5).roundToInt() * 5))
    }

    private fun m(minutes: Int) = minutes * 60

    private fun walkSession(day: Long, p: Program, minutes: Int, s: Speeds, title: String, key: Boolean, deload: Boolean): PlannedSession {
        val body = max(4, minutes - 6)
        return PlannedSession(
            day, SessionKind.WALK, title, "$minutes min, marche à ${Fmt.kmh(s.walk)} km/h",
            listOf(
                Block(BlockKind.WARMUP, m(3), s.warm), Block(BlockKind.WALK, m(body), s.walk),
                Block(BlockKind.COOLDOWN, m(3), s.warm),
            ),
            key, p.week, deload,
        )
    }

    private fun hillSession(day: Long, p: Program, minutes: Int, s: Speeds, incline: Double, deload: Boolean): PlannedSession {
        val body = max(4, minutes - 6)
        val pct = Fmt.kmh(incline).removeSuffix(",0")
        return PlannedSession(
            day, SessionKind.HILL, "Marche en côte $pct %",
            "$minutes min · inclinaison $pct % à régler avant le départ",
            listOf(
                Block(BlockKind.WARMUP, m(3), Physio.round1(s.warm * 0.9), incline),
                Block(BlockKind.HILL, m(body), s.hill, incline),
                Block(BlockKind.COOLDOWN, m(3), Physio.round1(s.warm * 0.9), incline),
            ),
            false, p.week, deload,
        )
    }

    private fun stepSession(day: Long, p: Program, step: Step, s: Speeds, cap: Int, deload: Boolean, slot: Int): PlannedSession {
        when (step.kind) {
            StepKind.WALK -> {
                val minutes = if (cap > 0) min(cap, step.a) else step.a
                return walkSession(day, p, minutes, s, "Marche active", true, deload)
            }
            StepKind.INTERVALS -> {
                val reps = if (cap > 0) max(1, min(step.reps, (cap - 6) / (step.a + step.b))) else step.reps
                val blocks = mutableListOf(Block(BlockKind.WARMUP, m(3), s.warm))
                repeat(reps) {
                    blocks += Block(BlockKind.RUN, m(step.a), s.run)
                    blocks += Block(BlockKind.WALK, m(step.b), s.warm)
                }
                blocks += Block(BlockKind.COOLDOWN, m(3), s.warm)
                return PlannedSession(
                    day, SessionKind.INTERVALS, "Marche / course",
                    "$reps × (${step.a} min de trot / ${step.b} min de marche)", blocks, true, p.week, deload,
                )
            }
            StepKind.CONTINUOUS -> {
                val edge = if (cap > 0) 3 else 5
                val run = if (cap > 0) max(5, min(step.a, cap - 2 * edge)) else step.a
                return PlannedSession(
                    day, SessionKind.CONTINUOUS, "Course continue",
                    "$run min de course à ${Fmt.kmh(s.run)} km/h",
                    listOf(
                        Block(BlockKind.WARMUP, m(edge), s.walk), Block(BlockKind.RUN, m(run), s.run),
                        Block(BlockKind.COOLDOWN, m(edge), s.warm),
                    ),
                    true, p.week, deload,
                )
            }
            StepKind.PERF -> {
                val k = step.a
                val blocks = mutableListOf(Block(BlockKind.WARMUP, m(4), s.walk), Block(BlockKind.RUN, m(5), s.run))
                if (slot == 0) {
                    val reps = if (deload) 3 else if (cap > 0) 3 else 4 + k / 2
                    repeat(reps) {
                        blocks += Block(BlockKind.FAST, m(2), s.fast)
                        blocks += Block(BlockKind.WALK, m(2), s.warm)
                    }
                    blocks += Block(BlockKind.COOLDOWN, m(4), s.warm)
                    return PlannedSession(
                        day, SessionKind.PERF_INTERVALS, "Fractionné",
                        "$reps × (2 min à ${Fmt.kmh(s.fast)} km/h / 2 min de marche)", blocks, true, p.week, deload,
                    )
                }
                val tempo = if (deload || cap > 0) 8 else 10 + (k * 1.5).roundToInt()
                blocks += Block(BlockKind.FAST, m(tempo), s.tempo)
                blocks += Block(BlockKind.COOLDOWN, m(5), s.warm)
                return PlannedSession(
                    day, SessionKind.PERF_TEMPO, "Allure soutenue",
                    "$tempo min à ${Fmt.kmh(s.tempo)} km/h", blocks, true, p.week, deload,
                )
            }
        }
    }

    private fun testSession(day: Long, p: Program, profile: Profile, s: Speeds, deload: Boolean) = PlannedSession(
        day, SessionKind.TEST, "Test de forme",
        "6 min à l'allure de marche la plus vive où tu peux encore parler",
        listOf(
            Block(BlockKind.WARMUP, m(3), s.warm), Block(BlockKind.TEST, m(6), Physio.round1(profile.vr)),
            Block(BlockKind.COOLDOWN, m(3), s.warm),
        ),
        false, p.week, deload,
    )

    /** La séance correspondant à la position actuelle dans le programme. `cap` > 0 : version courte. */
    fun sessionFor(p: Program, profile: Profile, day: Long, cap: Int = 0): PlannedSession {
        val n = sessionsPerWeek(profile)
        val s = Physio.speedsFor(profile, p.speedFactor)
        val deload = isDeload(p)
        val slot = p.sessionInWeek
        if (p.testNext) return testSession(day, p, profile, s, deload)
        if (p.flatWalkNext) {
            val minutes = min(if (cap > 0) cap else 25, max(15, profile.minutesPerSession))
            return walkSession(day, p, minutes, s, "Marche à plat", false, deload)
        }
        if (deload && n >= 2 && slot == n - 1) return testSession(day, p, profile, s, deload)

        val role = when (n) {
            1, 2 -> 'K'
            3 -> "KEK"[slot.coerceIn(0, 2)]
            else -> "KEKR"[slot.coerceIn(0, 3)]
        }
        if (role == 'R') {
            return PlannedSession(
                day, SessionKind.RECOVERY, "Récupération", "15 min de marche lente, à plat",
                listOf(Block(BlockKind.WALK, m(15), s.recovery)), false, p.week, deload,
            )
        }
        if (role == 'E') {
            if (p.type == ProgramType.PERF) {
                return stepSession(day, p, Step(StepKind.CONTINUOUS, if (cap > 0) cap else 30), s, cap, deload, slot)
                    .copy(title = "Endurance", isKey = false)
            }
            val limit = min(max(15, profile.minutesPerSession), 30)
            val minutes = min(if (cap > 0) cap else limit, 15 + 5 * p.week)
            val hill = profile.inclinePct > 0 && (p.type == ProgramType.HEALTH || p.week % 2 == 0)
            return if (hill) hillSession(day, p, minutes, s, profile.inclinePct, deload)
            else walkSession(day, p, minutes, s, "Marche active", false, deload)
        }
        val idx = (p.stepIndex - (if (deload) 1 else 0) - (if (p.fallbackNext) 1 else 0)).coerceIn(0, p.ladder.size - 1)
        val step = if (deload) reduced(p.ladder[idx]) else p.ladder[idx]
        return stepSession(day, p, step, s, cap, deload, slot)
    }

    /** Position suivante dans le programme, une fois la séance courante faite. */
    fun advance(p: Program, profile: Profile): Program {
        if (p.testNext) return p.copy(testNext = false)
        var q = p.copy(sessionInWeek = p.sessionInWeek + 1, fallbackNext = false, flatWalkNext = false)
        if (q.sessionInWeek >= sessionsPerWeek(profile)) {
            val last = p.ladder.size - 1
            val next = when {
                isDeload(p) || p.repeatWeek -> p.stepIndex
                p.skipStep && p.stepIndex + 2 <= last -> p.stepIndex + 2
                else -> p.stepIndex + 1
            }
            q = q.copy(sessionInWeek = 0, week = p.week + 1, stepIndex = next, repeatWeek = false, skipStep = false)
        }
        return q
    }

    /** Toutes les séances à venir, datées selon les jours disponibles. Une séance manquée glisse, jamais doublée. */
    fun plan(p: Program, profile: Profile, today: Long): List<PlannedSession> {
        if (p.paused || p.finished || p.status != ProgramStatus.ACTIVE) return emptyList()
        val days = trainingDays(profile)
        var cursor = max(today, p.restUntilDay)
        if (p.lastSessionDay == today) cursor = max(cursor, today + 1)
        var q = p
        val out = ArrayList<PlannedSession>()
        while (!q.finished && out.size < 400) {
            while (dayOfWeek(cursor) !in days) cursor++
            out += sessionFor(q, profile, cursor)
            q = advance(q, profile)
            cursor++
        }
        return out
    }

    // ---------- Adaptation ----------

    fun applyFeedback(
        p: Program, profile: Profile, session: PlannedSession, fb: Feedback, today: Long,
        measuredTestSpeed: Double = 0.0, longestRunMin: Int = 0,
    ): FeedbackResult {
        var prof = profile
        var q = p
        val msgs = ArrayList<String>()

        if (session.kind == SessionKind.TEST) {
            val v = if (measuredTestSpeed >= 3.0) measuredTestSpeed else fb.testSpeed
            if (v >= 3.0) {
                val old = prof.vr
                prof = prof.copy(vr = Physio.round1(v), testRpe = fb.rpe)
                msgs += when {
                    prof.vr > old + 0.05 -> "Vitesse de référence : ${Fmt.kmh(old)} → ${Fmt.kmh(prof.vr)} km/h. Le travail paie, tes allures sont recalculées."
                    prof.vr < old - 0.05 -> "Vitesse de référence ajustée à ${Fmt.kmh(prof.vr)} km/h. Tes allures sont recalculées, sans jugement : on part d'où tu es."
                    else -> "Vitesse de référence stable à ${Fmt.kmh(prof.vr)} km/h."
                }
            }
        } else {
            val recent = (q.recentRpe + fb.rpe).takeLast(2)
            q = when {
                recent.size == 2 && recent.all { it >= 8 } -> {
                    msgs += "Deux séances dures de suite : on rejoue cette semaine, un peu moins vite. Ce n'est pas un recul, c'est le bon dosage."
                    q.copy(repeatWeek = true, speedFactor = max(0.8, q.speedFactor * 0.95), recentRpe = emptyList())
                }
                recent.size == 2 && recent.all { it <= 4 } && !q.repeatWeek -> {
                    msgs += "Deux séances faciles de suite : tu sauteras un palier la semaine prochaine."
                    q.copy(skipStep = true, recentRpe = emptyList())
                }
                else -> q.copy(recentRpe = recent)
            }
        }
        if (!fb.completed) {
            msgs += "Séance écourtée : la prochaine reprend au palier précédent."
        }
        when (fb.pain) {
            Pain.NONE -> q = q.copy(painCount = 0)
            Pain.DISCOMFORT -> {
                q = q.copy(painCount = q.painCount + 1)
                msgs += "Gêne signalée : la prochaine séance sera une marche à plat."
            }
            Pain.PAIN -> {
                q = q.copy(painCount = q.painCount + 1, restUntilDay = today + 3)
                msgs += "Douleur signalée : 48 h de repos, puis reprise par une marche à plat."
            }
        }
        if (fb.pain != Pain.NONE && q.painCount >= 2) msgs += "C'est la deuxième fois de suite : parles-en à un médecin ou un kiné avant de continuer."
        if (session.isKey) q = q.copy(lastKeyComplete = fb.completed)
        if (fb.completed && longestRunMin > prof.longestRunMin) prof = prof.copy(longestRunMin = longestRunMin)

        // Les consignes pour la séance suivante se posent après l'avancée, qui efface celles de la séance faite.
        q = advance(q, prof).copy(
            sessionsDone = q.sessionsDone + 1, lastSessionDay = today,
            fallbackNext = !fb.completed, flatWalkNext = fb.pain != Pain.NONE,
        )
        if (q.finished) {
            q = q.copy(
                status = if (q.lastKeyComplete) ProgramStatus.SUCCESS else ProgramStatus.FINISHED,
                endDay = today, vrEnd = prof.vr, sessionsPlanned = q.sessionsDone,
            )
            msgs += if (q.status == ProgramStatus.SUCCESS) "Objectif atteint : ${p.type.validation}. Choisis la suite quand tu veux."
            else "Programme terminé. La dernière séance n'a pas été faite en entier : tu peux le relancer pour valider."
        }
        if (msgs.isEmpty()) {
            msgs += when {
                fb.rpe >= 8 -> "Séance exigeante. Si la prochaine l'est autant, on ajustera."
                fb.rpe <= 3 -> "Séance facile, et c'est très bien : la base se construit là."
                else -> "Séance tenue. C'est la régularité qui construit la forme."
            }
        }
        return FeedbackResult(q, prof, msgs.joinToString(" "))
    }

    /** Retour après une coupure : deux semaines d'arrêt font reculer de deux paliers, quatre ajoutent un test. */
    fun applyGap(p: Program, today: Long): Program {
        if (p.paused || p.lastSessionDay <= 0 || p.status != ProgramStatus.ACTIVE) return p
        val gap = today - p.lastSessionDay
        if (gap < 14) return p
        return p.copy(
            stepIndex = max(0, p.stepIndex - 2), sessionInWeek = 0, testNext = gap >= 28,
            lastSessionDay = today - 1, recentRpe = emptyList(),
        )
    }

    /** Clôt un programme arrêté avant la fin. */
    fun stop(p: Program, profile: Profile, today: Long): Program = p.copy(
        status = ProgramStatus.STOPPED, endDay = today, vrEnd = profile.vr,
        sessionsPlanned = p.sessionsDone + plan(p.copy(paused = false), profile, today).size, paused = false,
    )
}
