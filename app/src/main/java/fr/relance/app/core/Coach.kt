package fr.relance.app.core

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

    private fun snapshotOf(t: ProgramTemplate, c: Catalog): ProgramSnapshot {
        val ids = HashSet<String>()
        t.steps.forEach { st -> st.sessions.forEach { ids += it.type } }
        ids += t.easy.types; ids += t.easy.fallback
        ids += c.specials.test; ids += c.specials.recovery; ids += c.specials.flatWalk
        return ProgramSnapshot(t, c.sessionTypes.filter { it.id in ids }, c.paces, c.specials, c.version)
    }

    fun ladderFor(t: ProgramTemplate, p: Profile, c: Catalog = Catalogs.current): List<ResolvedStep> =
        Templates.resolve(t, p, c.paces) { c.type(it) }

    fun weeksFor(t: ProgramTemplate, steps: Int): Int = Templates.weeksFor(steps, t.deload.every)

    fun newProgram(t: ProgramTemplate, p: Profile, today: Long, c: Catalog = Catalogs.current): Program {
        val steps = ladderFor(t, p, c)
        val draft = Program(
            id = "p$today-${t.id}-${System.nanoTime() % 100000}", templateId = t.id, name = t.name, validation = t.validation,
            startDay = today, steps = steps, snapshot = snapshotOf(t, c), vrStart = p.vr, weeksPlanned = weeksFor(t, steps.size),
            startLevel = p.level, startLongestRun = p.longestRunMin,
        )
        return draft.copy(sessionsPlanned = plan(draft, p, today).size)
    }

    /** Raccourci pour les programmes de la première version (tests, migration). */
    fun newProgram(type: ProgramType, p: Profile, today: Long): Program =
        newProgram(Catalogs.current.program(type.id) ?: Catalogs.builtin.program(type.id)!!, p, today)

    /** Programme créé avant le catalogue : on le reconstruit depuis le catalogue intégré, à la même position. */
    fun migrate(p: Program, profile: Profile): Program {
        if (p.snapshot != null) return p
        val t = Catalogs.builtin.program(p.type?.id ?: "run-30") ?: return p
        val steps = ladderFor(t, profile, Catalogs.builtin)
        return p.copy(
            templateId = t.id, name = t.name, validation = t.validation, steps = steps, snapshot = snapshotOf(t, Catalogs.builtin),
            stepIndex = p.stepIndex.coerceAtMost(steps.size - 1), weeksPlanned = weeksFor(t, steps.size), type = null,
        )
    }

    // ---------- Mises à jour du catalogue ----------

    /** Le coach a publié une version différente de ce programme. */
    fun hasUpdate(p: Program, c: Catalog = Catalogs.current): Boolean {
        val s = p.snapshot ?: return false
        if (p.status != ProgramStatus.ACTIVE || c.version <= s.catalogVersion) return false
        val t = c.program(p.templateId) ?: return false
        if (p.pending?.catalogVersion == c.version) return false
        return snapshotOf(t, c).copy(catalogVersion = 0) != s.copy(catalogVersion = 0)
    }

    private fun withSnapshot(p: Program, snap: ProgramSnapshot, profile: Profile): Program {
        val start = profile.copy(level = p.startLevel ?: profile.level, longestRunMin = if (p.startLevel != null) p.startLongestRun else profile.longestRunMin)
        val catalog = Catalog(paces = snap.paces, specials = snap.specials, sessionTypes = snap.sessionTypes, programs = listOf(snap.template), version = snap.catalogVersion)
        val steps = ladderFor(snap.template, start, catalog)
        return p.copy(
            snapshot = snap, pending = null, steps = steps, name = snap.template.name, validation = snap.template.validation,
            stepIndex = p.stepIndex.coerceAtMost(maxOf(0, steps.size - 1)), weeksPlanned = weeksFor(snap.template, steps.size),
        )
    }

    /** Applique tout de suite si aucune séance de la semaine n'est faite, sinon au début de la semaine suivante. */
    fun scheduleUpdate(p: Program, profile: Profile, c: Catalog = Catalogs.current): Program {
        val t = c.program(p.templateId) ?: return p
        val snap = snapshotOf(t, c)
        return if (p.sessionInWeek == 0 && !p.testNext) withSnapshot(p, snap, profile) else p.copy(pending = snap)
    }

    fun recommended(p: Profile, c: Catalog = Catalogs.current): ProgramTemplate? = Templates.recommended(c, p)

    fun lockedReason(t: ProgramTemplate, p: Profile): String? = Templates.lockedReason(t, p)

    /** Durée de la plus longue séance clé d'un programme pour ce profil, pour prévenir si elle dépasse le temps disponible. */
    fun longestSessionMinutes(t: ProgramTemplate, p: Profile, c: Catalog = Catalogs.current): Int {
        val speeds = Physio.speedsFor(p, 1.0, c.paces)
        return ladderFor(t, p, c).flatMap { it.sessions }.maxOfOrNull { ref ->
            val type = c.type(ref.type) ?: return@maxOfOrNull 0
            Templates.build(type, ref.params, p, speeds, 0, 0, true, false, 0).minutes
        } ?: 0
    }

    // ---------- Construction des séances ----------

    private fun deloadEvery(p: Program) = p.snapshot?.template?.deload?.every ?: 4

    private fun isDeload(p: Program): Boolean = deloadEvery(p).let { it > 0 && p.week % it == it - 1 } && p.stepIndex > 0

    private fun special(p: Program, id: String, profile: Profile, speeds: Speeds, day: Long, params: Map<String, Double>, cap: Int): PlannedSession? {
        val type = p.snapshot?.type(id) ?: Catalogs.current.type(id) ?: return null
        return Templates.build(type, params, profile, speeds, day, p.week, false, isDeload(p), cap)
    }

    /** La séance correspondant à la position actuelle dans le programme. `cap` > 0 : version courte. */
    fun sessionFor(p: Program, profile: Profile, day: Long, cap: Int = 0): PlannedSession {
        val snap = p.snapshot ?: migrate(p, profile).snapshot!!
        val t = snap.template
        val n = sessionsPerWeek(profile)
        val s = Physio.speedsFor(profile, p.speedFactor, snap.paces)
        val deload = isDeload(p)
        val slot = p.sessionInWeek
        val sp = snap.specials
        if (p.testNext) return special(p, sp.test, profile, s, day, emptyMap(), 0)!!
        if (p.flatWalkNext) {
            val minutes = min(if (cap > 0) cap else 25, max(15, profile.minutesPerSession))
            return special(p, sp.flatWalk, profile, s, day, mapOf("minutes" to minutes.toDouble()), 0)!!
        }
        if (deload && t.deload.testAtEnd && n >= 2 && slot == n - 1) return special(p, sp.test, profile, s, day, emptyMap(), 0)!!

        val pattern = t.weekPatterns[n.toString()] ?: t.weekPatterns[t.weekPatterns.keys.maxByOrNull { it.toIntOrNull() ?: 0 }] ?: "K"
        val role = pattern.getOrElse(slot.coerceAtMost(pattern.length - 1)) { 'K' }
        if (role == 'R') return special(p, sp.recovery, profile, s, day, emptyMap(), cap)!!
        if (role == 'E') {
            val e = t.easy
            val limit = min(max(15, profile.minutesPerSession), e.maxMinutes)
            val minutes = min(if (cap > 0) cap else limit, e.startMinutes + e.stepMinutes * p.week).coerceAtLeast(10)
            val wanted = if (e.types.isEmpty()) e.fallback else e.types[p.week % e.types.size]
            var type = snap.type(wanted) ?: snap.type(e.fallback)!!
            if (type.needsIncline && profile.inclinePct <= 0) type = snap.type(e.fallback) ?: type
            return Templates.build(type, mapOf("minutes" to minutes.toDouble()), profile, s, day, p.week, false, deload, cap)
        }
        val idx = (p.stepIndex - (if (deload) 1 else 0) - (if (p.fallbackNext) 1 else 0)).coerceIn(0, p.steps.size - 1)
        val step = p.steps[idx]
        val keyIndex = pattern.take(slot).count { it == 'K' }
        val ref = step.sessions[keyIndex % step.sessions.size]
        val type = snap.type(ref.type)!!
        val params = if (deload) Templates.deloaded(type.params.associate { it.name to it.default } + ref.params, t.deload.factor) else ref.params
        return Templates.build(type, params, profile, s, day, p.week, true, deload, cap)
    }

    /** Position suivante dans le programme, une fois la séance courante faite. */
    fun advance(p: Program, profile: Profile): Program {
        if (p.testNext) return p.copy(testNext = false)
        var q = p.copy(sessionInWeek = p.sessionInWeek + 1, fallbackNext = false, flatWalkNext = false)
        if (q.sessionInWeek >= sessionsPerWeek(profile)) {
            val last = p.steps.size - 1
            val next = when {
                isDeload(p) || p.repeatWeek -> p.stepIndex
                p.skipStep && p.stepIndex + 2 <= last -> p.stepIndex + 2
                else -> p.stepIndex + 1
            }
            q = q.copy(sessionInWeek = 0, week = p.week + 1, stepIndex = next, repeatWeek = false, skipStep = false)
            q.pending?.let { q = withSnapshot(q, it, profile) }
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
            msgs += if (q.status == ProgramStatus.SUCCESS) "Objectif atteint : ${p.validation}. Choisis la suite quand tu veux."
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
