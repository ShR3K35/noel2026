package fr.relance.app.core

import kotlinx.serialization.Serializable

/**
 * Constantes du protocole de calibration (version 1). Ce sont des choix de conception prudents,
 * pas des valeurs validées scientifiquement : voir docs/CALIBRATION.md.
 */
object PaceProtocol {
    const val VERSION = 1
    /** Durée maximale d'un test, à partir du premier mouvement, réglages et retour au calme compris. */
    const val BUDGET_SEC = 1200
    /** Réservé au retour au calme. */
    const val COOLDOWN_SEC = 180
    const val WARMUP_SEC = 300
    /** Marche facile stable pendant les 2 dernières minutes de l'échauffement. */
    const val WARMUP_STABLE_SEC = 120
    /** Contrôle de parole et d'effort pendant les 30 dernières secondes d'un palier. */
    const val CHECK_WINDOW_SEC = 30
    /** Attente d'une réponse après la fin prévue d'un palier, avant de la noter absente. */
    const val ANSWER_GRACE_SEC = 45
    const val WALK_STEP_SEC = 180
    const val WALK_STEPS_MAX = 3
    const val CONFIRM_BLOCK_SEC = 600
    /** Contrôles aux minutes 3, 6 et 10 du bloc. */
    val CONFIRM_CHECKS = listOf(180, 360, 600)
    const val JOG_REP_SEC = 60
    const val JOG_RECOVERY_SEC = 120
    const val JOG_REPS = 3
    const val RECOVERY_EXTENSION_SEC = 60
    /** Hausse maximale entre deux paliers ou deux tests. */
    const val MAX_RAISE_KMH = 0.3
    /** Échelle d'effort propre à l'app : 0 repos, 3 modéré, 5 difficile, 10 maximal. */
    const val WALK_EASY_MAX_RPE = 2
    const val COMFORT_MAX_RPE = 3
    const val BORDERLINE_RPE = 4
    const val HARD_RPE = 5

    const val PASSAGE_VERSION = 1
    const val PASSAGE = "Le matin, la lumière entre dans la maison. Sur la table, un livre reste ouvert près d’une tasse. " +
        "Dehors, les arbres bougent doucement et quelques oiseaux passent au-dessus du jardin."
}

@Serializable
enum class TestKind(val id: String, val label: String, val nominalSec: Int, val produces: RefKind, val running: Boolean) {
    WALK_STEPS("walk-steps", "Test de marche par paliers", 1020, RefKind.WALK_ACTIVE, false),
    CONFIRM_WALK("confirm-walk", "Confirmation de la marche active", 1080, RefKind.WALK_ACTIVE, false),
    CONFIRM_RUN("confirm-run", "Confirmation du trot continu", 1080, RefKind.RUN_EASY, true),
    WALK_RUN("walk-run", "Marche-course débutant", 900, RefKind.RUN_EASY, true),
}

@Serializable
enum class Speech(val label: String) { YES("Oui"), UNSURE("Incertain"), NO("Non") }

@Serializable
enum class Symptom(val label: String) {
    CHEST_PAIN("Douleur dans la poitrine"),
    FAINT("Malaise, vertige"),
    BREATH("Essoufflement inhabituel"),
    GAIT_PAIN("Douleur qui modifie ma démarche"),
}

@Serializable
enum class Verdict(val label: String) {
    STOP("Arrêt"), TOO_HARD("Trop difficile"), BORDERLINE("À la limite"), COMFORTABLE("Confortable"), UNKNOWN("Indéterminé"),
}

/** Réponses recueillies à un contrôle. Un champ nul n'a pas été renseigné. */
@Serializable
data class Observation(
    /** Secondes depuis le premier mouvement. */
    val atSec: Int,
    /** Contrôle concerné : seconde du segment à laquelle il est dû. */
    val check: Int = 0,
    val speech: Speech? = null,
    val rpe: Int? = null,
    val movementOk: Boolean? = null,
    /** Aucune réponse dans le délai. */
    val noAnswer: Boolean = false,
    /** Réponse donnée après le retour à la marche (répétition de trot). */
    val late: Boolean = false,
    val viaVoice: Boolean = false,
    /** Une question de confort supplémentaire a été posée après une lecture qui semblait dégradée. */
    val extraQuestion: Boolean = false,
    val acoustic: AcousticComparison? = null,
)

object Verdicts {
    /**
     * Classement d'un contrôle, dans cet ordre : un signal défavorable connu l'emporte sur une donnée manquante.
     * (Un symptôme est traité à part : il interrompt le test.)
     */
    fun classify(o: Observation?): Verdict {
        if (o == null || o.noAnswer && o.speech == null && o.rpe == null) return Verdict.UNKNOWN
        val rpe = o.rpe
        return when {
            o.speech == Speech.NO || rpe != null && rpe >= PaceProtocol.HARD_RPE -> Verdict.TOO_HARD
            o.speech == Speech.UNSURE || rpe == PaceProtocol.BORDERLINE_RPE -> Verdict.BORDERLINE
            o.speech == Speech.YES && rpe != null && rpe <= PaceProtocol.COMFORT_MAX_RPE && o.movementOk == true -> Verdict.COMFORTABLE
            else -> Verdict.UNKNOWN
        }
    }

    /** Marche facile : confortable, effort au plus 2, marche naturelle. */
    fun walkEasy(o: Observation?): Boolean =
        classify(o) == Verdict.COMFORTABLE && (o?.rpe ?: 99) <= PaceProtocol.WALK_EASY_MAX_RPE
}

@Serializable
enum class SegKind(val label: String) {
    WARMUP("Échauffement"), STEP("Palier"), BLOCK("Allure constante"), REP("Trot"),
    RECOVERY("Marche de récupération"), COOLDOWN("Retour au calme"),
}

@Serializable
data class Segment(
    val kind: SegKind,
    /** Numéro du palier ou de la répétition (1, 2, 3). */
    val n: Int = 0,
    val proposedKmh: Double,
    /** Vitesse que l'utilisateur confirme avoir réglée. */
    val declaredKmh: Double = 0.0,
    val plannedSec: Int,
    val startAt: Int = 0,
    val endAt: Int = -1,
    /** Dernier changement de vitesse ou reprise : début de la période stable. */
    val stableSince: Int = 0,
    /** Secondes du segment auxquelles un contrôle est dû. */
    val checks: List<Int> = emptyList(),
    val observations: List<Observation> = emptyList(),
    val completed: Boolean = false,
    /** Pause, correction de vitesse, ralentissement : la continuité de ce segment est perdue. */
    val interrupted: Boolean = false,
    val note: String = "",
) {
    val ended: Boolean get() = endAt >= 0
    fun obs(check: Int): Observation? = observations.lastOrNull { it.check == check }
    fun verdicts(): List<Verdict> = checks.map { Verdicts.classify(obs(it)) }
    val allComfortable: Boolean get() = checks.isNotEmpty() && verdicts().all { it == Verdict.COMFORTABLE }
    val durationSec: Int get() = if (ended) endAt - startAt else 0
}

@Serializable
enum class Phase { READY, AWAIT_SPEED, RUNNING, PAUSED, FINISHED }

@Serializable
enum class PauseReason(val label: String) {
    USER("Pause demandée"),
    BACKGROUND("L'app est passée en arrière-plan"),
    RESTORED("L'app a été relancée pendant le test"),
    AUDIO("Interruption (appel, autre app audio)"),
}

@Serializable
enum class EndReason(val label: String) {
    COMPLETED("Test terminé"), STOPPED("Arrêté à ta demande"), SYMPTOM("Interrompu : symptôme signalé"),
    BUDGET("Limite de 20 minutes atteinte"),
}

@Serializable
data class Suggestion(val kind: TestKind, val kmh: Double, val reason: String)

@Serializable
data class PaceTestState(
    val id: String,
    val kind: TestKind,
    val day: Long,
    val treadmill: TreadmillSpec,
    /** Vitesse de marche facile choisie pour démarrer (recherche guidée) ou saisie. */
    val easyKmh: Double,
    /** Vitesse candidate du bloc ou des répétitions. */
    val candidateKmh: Double = 0.0,
    /** Marche active habituelle, jugée accessible par l'utilisateur (0 = non). */
    val usualActiveKmh: Double = 0.0,
    val wallStartMillis: Long = 0,
    /** Horloge monotone (SystemClock.elapsedRealtime) au premier mouvement. */
    val monoStartMillis: Long = 0,
    val now: Int = 0,
    val phase: Phase = Phase.READY,
    val segments: List<Segment> = emptyList(),
    /** Segment qui démarrera quand l'utilisateur aura confirmé sa vitesse. */
    val pending: Segment? = null,
    val pausedAt: Int = 0,
    val pauseReason: PauseReason? = null,
    val pausedFrom: Phase? = null,
    val endReason: EndReason? = null,
    val symptom: Symptom? = null,
    /** Pourquoi la progression s'est arrêtée. */
    val stopReason: String = "",
    val notes: List<String> = emptyList(),
    /** Marche facile retenue à l'échauffement (0 = pas encore, ou échec). */
    val walkEasyKmh: Double = 0.0,
    val walkEasyStableSec: Int = 0,
    val suggestions: List<Suggestion> = emptyList(),
    val voiceUsed: Boolean = false,
    /** Lectures de départ (micro), faites avant le premier mouvement. */
    val voiceBaseline: VoiceBaseline? = null,
) {
    val current: Segment? get() = segments.lastOrNull()?.takeIf { !it.ended }
    val remaining: Int get() = PaceProtocol.BUDGET_SEC - now
}

/** Contrôle en attente de réponse. */
data class CheckRef(val segIndex: Int, val check: Int, val late: Boolean)

@Serializable
enum class CalStatus(val label: String) {
    COMPLETE("Complet"), PARTIAL("Incomplet"), STOPPED("Arrêté"), ABORTED("Interrompu par un symptôme"),
}

/** Résultat d'un test, conservé dans l'historique avec toutes ses observations. */
@Serializable
data class CalibrationRecord(
    val id: String,
    val kind: TestKind,
    val protocolVersion: Int = PaceProtocol.VERSION,
    val passageVersion: Int = PaceProtocol.PASSAGE_VERSION,
    val day: Long,
    val wallStartMillis: Long,
    val durationSec: Int,
    val status: CalStatus,
    val endReason: EndReason? = null,
    val symptom: Symptom? = null,
    /** Références produites par le test. */
    val refs: List<PaceRef> = emptyList(),
    /** Références effectivement appliquées au profil. */
    val applied: List<RefKind> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
    val notes: List<String> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val treadmill: TreadmillSpec = TreadmillSpec(),
    val voiceUsed: Boolean = false,
    val voiceBaseline: VoiceBaseline? = null,
)

/**
 * Déroulé d'un test de calibration. Sans dépendance Android : le temps (`now`, secondes depuis le premier mouvement)
 * est fourni par l'appelant, à partir d'une horloge monotone. L'app recommande, le tapis reste réglé à la main.
 */
class PaceTest(state: PaceTestState) {
    var s: PaceTestState = state
        private set

    private val p = PaceProtocol
    private val t get() = s.treadmill

    private fun fits(sec: Int, now: Int) = p.BUDGET_SEC - now - p.COOLDOWN_SEC >= sec
    private fun note(text: String) { s = s.copy(notes = s.notes + text) }
    private fun replaceCurrent(f: (Segment) -> Segment) {
        val i = s.segments.lastIndex
        if (i >= 0) s = s.copy(segments = s.segments.toMutableList().also { it[i] = f(it[i]) })
    }
    private fun easy(): Double = if (s.walkEasyKmh > 0) s.walkEasyKmh else s.easyKmh

    // ---------- Démarrage ----------

    /** Premier mouvement : l'échauffement démarre à la vitesse de marche facile confirmée. */
    fun start(declaredKmh: Double, monoMillis: Long, wallMillis: Long): Boolean {
        if (s.phase != Phase.READY) return false
        val v = Treadmill.fit(declaredKmh, t)
        val withCheck = s.kind == TestKind.WALK_STEPS || s.kind == TestKind.WALK_RUN
        s = s.copy(
            phase = Phase.RUNNING, monoStartMillis = monoMillis, wallStartMillis = wallMillis, now = 0, easyKmh = v,
            segments = listOf(
                Segment(SegKind.WARMUP, 0, v, v, p.WARMUP_SEC, 0, stableSince = 0, checks = if (withCheck) listOf(p.WARMUP_SEC) else emptyList())
            ),
        )
        return true
    }

    // ---------- Contrôles ----------

    fun openCheck(): CheckRef? {
        if (s.phase != Phase.RUNNING) return null
        val i = s.segments.lastIndex
        if (i < 0) return null
        val seg = s.segments[i]
        // Réponse à une répétition de trot recueillie après le retour à la marche.
        if ((seg.kind == SegKind.RECOVERY || seg.kind == SegKind.COOLDOWN) && i > 0) {
            val prev = s.segments[i - 1]
            if (prev.kind == SegKind.REP && !prev.interrupted) prev.checks.firstOrNull { prev.obs(it) == null }?.let { return CheckRef(i - 1, it, true) }
        }
        if (seg.ended) return null
        val elapsed = s.now - seg.startAt
        for (c in seg.checks) {
            if (seg.obs(c) != null) continue
            val open = if (seg.kind == SegKind.WARMUP) elapsed >= c - p.CHECK_WINDOW_SEC && s.now - seg.stableSince >= p.WARMUP_STABLE_SEC - p.CHECK_WINDOW_SEC
            else elapsed >= c - p.CHECK_WINDOW_SEC
            if (open) return CheckRef(i, c, false)
        }
        return null
    }

    /** Réponses à un contrôle. Une seconde réponse au même contrôle est ignorée (double validation). */
    fun answer(o: Observation, now: Int): Boolean {
        tick(now)
        val ref = openCheck() ?: return false
        val obs = o.copy(atSec = now, check = ref.check, late = ref.late)
        val segs = s.segments.toMutableList()
        segs[ref.segIndex] = segs[ref.segIndex].let { it.copy(observations = it.observations + obs) }
        s = s.copy(segments = segs)
        val seg = segs[ref.segIndex]
        val v = Verdicts.classify(obs)
        when (seg.kind) {
            SegKind.WARMUP -> if (!Verdicts.walkEasy(obs)) {
                s = s.copy(stopReason = "La marche de l'échauffement n'était pas encore facile (${v.label.lowercase()}).")
                endCurrent(now, completed = false, note = "Contrôle d'échauffement : ${v.label.lowercase()}")
                cooldown(now, slower = true)
            }
            SegKind.STEP -> if (v != Verdict.COMFORTABLE) {
                s = s.copy(stopReason = "Palier ${seg.n} : ${v.label.lowercase()}.")
                endCurrent(now, completed = false, note = v.label)
                cooldown(now)
            }
            SegKind.BLOCK -> if (v != Verdict.COMFORTABLE) {
                s = s.copy(stopReason = "Contrôle à la minute ${ref.check / 60} : ${v.label.lowercase()}.")
                endCurrent(now, completed = false, interrupted = true, note = "Tentative terminée : ${v.label.lowercase()}")
                if (v == Verdict.TOO_HARD || v == Verdict.BORDERLINE) suggestLower(seg.declaredKmh)
                cooldown(now)
            }
            SegKind.REP -> if (v != Verdict.COMFORTABLE) {
                s = s.copy(stopReason = "Répétition ${seg.n} : ${v.label.lowercase()}. Fin de la partie trot.")
                if (s.current?.kind == SegKind.REP || s.current?.kind == SegKind.RECOVERY) endCurrent(now, completed = false, note = "Fin de la partie trot")
                if (s.current == null || s.current?.kind != SegKind.COOLDOWN) cooldown(now)
            }
            else -> Unit
        }
        return true
    }

    // ---------- Horloge ----------

    fun tick(now: Int) {
        if (s.phase == Phase.READY || s.phase == Phase.FINISHED) return
        s = s.copy(now = maxOf(s.now, now).coerceAtMost(p.BUDGET_SEC))
        if (s.now >= p.BUDGET_SEC) {
            val inCooldown = s.current?.kind == SegKind.COOLDOWN
            endCurrent(p.BUDGET_SEC, completed = inCooldown)
            finish(if (inCooldown && s.endReason == null) EndReason.COMPLETED else EndReason.BUDGET)
            return
        }
        if (s.phase != Phase.RUNNING) return
        val seg = s.current ?: return
        val elapsed = s.now - seg.startAt
        val unanswered = seg.checks.filter { seg.obs(it) == null }
        when (seg.kind) {
            SegKind.WARMUP -> {
                val withCheck = seg.checks.isNotEmpty()
                val stable = s.now - seg.stableSince
                // Échauffement prolongé par une correction de vitesse : jamais au-delà du temps réservé au retour au calme.
                if (!fits(0, s.now)) {
                    note("Échauffement trop long : plus le temps de poursuivre.")
                    s = s.copy(stopReason = "Temps écoulé pendant l'échauffement.")
                    endCurrent(s.now, completed = false)
                    cooldown(s.now, slower = true)
                    return
                }
                if (elapsed < seg.plannedSec || withCheck && stable < p.WARMUP_STABLE_SEC) return
                if (unanswered.isNotEmpty()) {
                    if (elapsed < seg.plannedSec + p.ANSWER_GRACE_SEC) return
                    addNoAnswer(s.segments.lastIndex, unanswered.first())
                }
                endCurrent(s.now, completed = true)
                afterWarmup(s.now)
            }
            SegKind.STEP, SegKind.BLOCK -> {
                if (elapsed < seg.plannedSec) return
                if (unanswered.isNotEmpty()) {
                    if (elapsed < seg.plannedSec + p.ANSWER_GRACE_SEC) return
                    addNoAnswer(s.segments.lastIndex, unanswered.first())
                    s = s.copy(stopReason = "Pas de réponse au contrôle : aucune progression sans réponse.")
                    endCurrent(s.now, completed = false, note = "Sans réponse")
                    cooldown(s.now)
                    return
                }
                endCurrent(s.now, completed = true)
                if (seg.kind == SegKind.STEP) afterStep(s.now) else cooldown(s.now)
            }
            SegKind.REP -> {
                if (elapsed < seg.plannedSec) return
                endCurrent(s.now, completed = true)
                afterRep(s.now)
            }
            SegKind.RECOVERY -> {
                if (elapsed < seg.plannedSec) return
                val i = s.segments.lastIndex
                val prev = s.segments.getOrNull(i - 1)
                if (prev != null && prev.kind == SegKind.REP && prev.checks.any { prev.obs(it) == null }) {
                    addNoAnswer(i - 1, prev.checks.first { prev.obs(it) == null })
                    s = s.copy(stopReason = "Pas de réponse pour la répétition ${prev.n} : fin de la partie trot.")
                    endCurrent(s.now, completed = true)
                    cooldown(s.now)
                    return
                }
                endCurrent(s.now, completed = true)
                val n = (prev?.n ?: 0) + 1
                await(Segment(SegKind.REP, n, prev?.declaredKmh ?: s.candidateKmh, plannedSec = p.JOG_REP_SEC, checks = listOf(p.JOG_REP_SEC)), s.now)
            }
            SegKind.COOLDOWN -> if (elapsed >= seg.plannedSec) {
                endCurrent(s.now, completed = true)
                finish(EndReason.COMPLETED)
            }
        }
    }

    private fun addNoAnswer(segIndex: Int, check: Int) {
        val segs = s.segments.toMutableList()
        segs[segIndex] = segs[segIndex].let { it.copy(observations = it.observations + Observation(s.now, check, noAnswer = true)) }
        s = s.copy(segments = segs)
    }

    // ---------- Enchaînements ----------

    private fun afterWarmup(now: Int) {
        val w = s.segments.last()
        if (w.checks.isNotEmpty()) {
            val o = w.obs(w.checks.first())
            if (!Verdicts.walkEasy(o) || now - w.stableSince < p.WARMUP_STABLE_SEC) {
                s = s.copy(stopReason = "Marche facile non confirmée à l'échauffement : calibration incomplète.")
                cooldown(now, slower = true)
                return
            }
            s = s.copy(walkEasyKmh = w.declaredKmh, walkEasyStableSec = (w.endAt - w.stableSince).coerceAtLeast(0))
        }
        when (s.kind) {
            TestKind.WALK_STEPS -> {
                val first = if (s.usualActiveKmh > easy() + 0.001) Treadmill.fit(s.usualActiveKmh, t) else easy()
                await(Segment(SegKind.STEP, 1, first, plannedSec = p.WALK_STEP_SEC, checks = listOf(p.WALK_STEP_SEC)), now)
            }
            TestKind.WALK_RUN -> await(Segment(SegKind.REP, 1, s.candidateKmh, plannedSec = p.JOG_REP_SEC, checks = listOf(p.JOG_REP_SEC)), now)
            TestKind.CONFIRM_WALK, TestKind.CONFIRM_RUN ->
                await(Segment(SegKind.BLOCK, 1, s.candidateKmh, plannedSec = p.CONFIRM_BLOCK_SEC, checks = p.CONFIRM_CHECKS), now)
        }
    }

    private fun afterStep(now: Int) {
        val seg = s.segments.last()
        if (seg.n >= p.WALK_STEPS_MAX) {
            note("Les ${p.WALK_STEPS_MAX} paliers étaient confortables : la limite supérieure n'a pas été recherchée davantage.")
            s = s.copy(stopReason = "Trois paliers confortables.")
            cooldown(now)
            return
        }
        val next = Treadmill.raise(seg.declaredKmh, t)
        if (next == null) {
            val why = if (t.stepKmh > p.MAX_RAISE_KMH + 0.001) "le pas du tapis (${Fmt.kmh(t.stepKmh)} km/h) dépasse la hausse permise de 0,3 km/h"
            else "la vitesse maximale du tapis est atteinte"
            s = s.copy(
                stopReason = "Pas de palier suivant : $why.",
                suggestions = s.suggestions + Suggestion(TestKind.CONFIRM_WALK, seg.declaredKmh, "Confirmer cette allure à vitesse constante pendant 10 min."),
            )
            cooldown(now)
            return
        }
        await(Segment(SegKind.STEP, seg.n + 1, next, plannedSec = p.WALK_STEP_SEC, checks = listOf(p.WALK_STEP_SEC)), now)
    }

    private fun afterRep(now: Int) {
        val rep = s.segments.last()
        if (rep.n >= p.JOG_REPS) {
            cooldown(now)
            return
        }
        // Récupération puis répétition suivante, avant le retour au calme.
        if (!fits(p.JOG_RECOVERY_SEC + p.JOG_REP_SEC, now)) {
            note("Répétition ${rep.n + 1} supprimée : plus assez de temps. Elle ne compte pas comme réussie.")
            s = s.copy(stopReason = "Temps insuffisant pour une nouvelle répétition.")
            cooldown(now)
            return
        }
        s = s.copy(segments = s.segments + Segment(SegKind.RECOVERY, rep.n, easy(), easy(), p.JOG_RECOVERY_SEC, now, stableSince = now), phase = Phase.RUNNING)
    }

    /** Le segment suivant attend la confirmation de la vitesse réglée, s'il tient dans le temps restant. */
    private fun await(seg: Segment, now: Int) {
        if (!fits(seg.plannedSec, now)) {
            note("${seg.kind.label}${if (seg.n > 0) " ${seg.n}" else ""} non joué : plus assez de temps avant le retour au calme.")
            if (s.stopReason.isBlank()) s = s.copy(stopReason = "Temps insuffisant.")
            cooldown(now)
            return
        }
        s = s.copy(phase = Phase.AWAIT_SPEED, pending = seg)
    }

    /**
     * L'utilisateur confirme la vitesse réglée : le segment démarre. Pour un palier ou un bloc, la vitesse ne peut pas
     * dépasser celle proposée (hausse maximale de 0,3 km/h).
     */
    fun confirmSpeed(kmh: Double, now: Int): Boolean {
        tick(now)
        if (s.phase != Phase.AWAIT_SPEED) return false
        val seg = s.pending ?: return false
        val v = Treadmill.fit(kmh, t)
        if ((seg.kind == SegKind.STEP || seg.kind == SegKind.BLOCK) && v > seg.proposedKmh + 0.001) return false
        if (!fits(seg.plannedSec, now)) {
            s = s.copy(pending = null, stopReason = "Temps insuffisant pour ${seg.kind.label.lowercase()}.")
            cooldown(now)
            return true
        }
        s = s.copy(
            phase = Phase.RUNNING, pending = null,
            segments = s.segments + seg.copy(declaredKmh = v, startAt = now, stableSince = now),
        )
        return true
    }

    /** La vitesse réellement réglée diffère : on la corrige. Sur un palier, la continuité repart de zéro. */
    fun correctSpeed(kmh: Double, now: Int): Boolean {
        tick(now)
        if (s.phase != Phase.RUNNING) return false
        val seg = s.current ?: return false
        val v = Treadmill.fit(kmh, t)
        when (seg.kind) {
            SegKind.WARMUP -> replaceCurrent { it.copy(declaredKmh = v, stableSince = now, observations = emptyList()) }
            SegKind.RECOVERY, SegKind.COOLDOWN -> replaceCurrent { it.copy(declaredKmh = v) }
            SegKind.STEP, SegKind.BLOCK, SegKind.REP -> {
                if (seg.kind != SegKind.REP && v > seg.proposedKmh + 0.001) return false
                endCurrent(now, completed = false, interrupted = true, note = "Vitesse corrigée : ${Fmt.kmh(seg.declaredKmh)} → ${Fmt.kmh(v)} km/h")
                val again = seg.copy(declaredKmh = v, startAt = now, stableSince = now, endAt = -1, observations = emptyList(), interrupted = false, completed = false, note = "")
                if (fits(seg.plannedSec, now)) s = s.copy(segments = s.segments + again)
                else {
                    s = s.copy(stopReason = "Vitesse corrigée, plus assez de temps pour reprendre ${seg.kind.label.lowercase()}.")
                    cooldown(now)
                }
            }
        }
        return true
    }

    /**
     * « Ralentir » pendant un effort : fin de la progression et retour au calme. Pendant l'échauffement, la récupération
     * ou le retour au calme, renvoie false : l'écran propose alors une vitesse plus lente ([correctSpeed]).
     */
    fun slowDown(now: Int): Boolean {
        tick(now)
        val seg = s.current
        if (s.phase == Phase.AWAIT_SPEED) {
            s = s.copy(pending = null, stopReason = "Ralenti à ta demande.")
            cooldown(now)
            return true
        }
        if (s.phase != Phase.RUNNING || seg == null) return false
        return when (seg.kind) {
            SegKind.STEP, SegKind.BLOCK, SegKind.REP -> {
                endCurrent(now, completed = false, interrupted = true, note = "Ralenti à ta demande")
                s = s.copy(stopReason = "Ralenti à ta demande.")
                if (seg.kind == SegKind.BLOCK) suggestLower(seg.declaredKmh)
                cooldown(now)
                true
            }
            SegKind.RECOVERY -> {
                endCurrent(now, completed = true)
                s = s.copy(stopReason = "Fin de la partie trot à ta demande.")
                cooldown(now)
                true
            }
            else -> false
        }
    }

    /** Arrêt de la progression : marche difficile, envie de trotter, trot pas naturel… */
    fun endProgression(reason: String, now: Int): Boolean {
        tick(now)
        if (s.phase != Phase.RUNNING && s.phase != Phase.AWAIT_SPEED) return false
        val seg = s.current
        if (seg?.kind == SegKind.COOLDOWN) return false
        if (s.phase == Phase.AWAIT_SPEED) s = s.copy(pending = null)
        else if (seg != null) endCurrent(now, completed = false, interrupted = seg.kind != SegKind.RECOVERY && seg.kind != SegKind.WARMUP, note = reason)
        s = s.copy(stopReason = reason)
        cooldown(now, slower = seg?.kind == SegKind.WARMUP)
        return true
    }

    /** Récupération prolongée d'une minute, si le temps restant le permet encore (répétition suivante comprise). */
    fun extendRecovery(now: Int): Boolean {
        tick(now)
        val seg = s.current ?: return false
        if (s.phase != Phase.RUNNING || seg.kind != SegKind.RECOVERY) return false
        val left = seg.plannedSec - (now - seg.startAt)
        if (!fits(left + p.RECOVERY_EXTENSION_SEC + p.JOG_REP_SEC, now)) return false
        replaceCurrent { it.copy(plannedSec = it.plannedSec + p.RECOVERY_EXTENSION_SEC) }
        return true
    }

    // ---------- Pause, arrêt ----------

    /** Une pause rompt la continuité du segment d'effort en cours. La reprise demande toujours une confirmation. */
    fun pause(reason: PauseReason, now: Int): Boolean {
        tick(now)
        if (s.phase != Phase.RUNNING && s.phase != Phase.AWAIT_SPEED) return false
        val seg = s.current
        if (s.phase == Phase.RUNNING && seg != null && seg.kind in setOf(SegKind.STEP, SegKind.BLOCK, SegKind.REP)) {
            endCurrent(now, completed = false, interrupted = true, note = "Pause : ${reason.label.lowercase()}")
        }
        s = s.copy(pausedFrom = s.phase, phase = Phase.PAUSED, pausedAt = now, pauseReason = reason)
        return true
    }

    fun resume(now: Int): Boolean {
        if (s.phase != Phase.PAUSED) return false
        tick(now)
        if (s.phase == Phase.FINISHED) return true
        val from = s.pausedFrom
        s = s.copy(phase = Phase.RUNNING, pausedFrom = null, pauseReason = null)
        if (from == Phase.AWAIT_SPEED) {
            s.pending?.let { s = s.copy(pending = null); await(it, now) }
            return true
        }
        val cur = s.current
        if (cur == null) {
            // Segment d'effort interrompu par la pause : il recommence du début, après confirmation de la vitesse.
            val last = s.segments.lastOrNull() ?: return true
            if (last.kind in setOf(SegKind.STEP, SegKind.BLOCK, SegKind.REP)) {
                await(last.copy(declaredKmh = 0.0, startAt = 0, endAt = -1, observations = emptyList(), interrupted = false, completed = false, note = ""), now)
            } else cooldown(now)
            return true
        }
        if (cur.kind == SegKind.WARMUP) replaceCurrent { it.copy(stableSince = now, observations = emptyList()) }
        return true
    }

    /** Après une relance de l'app : jamais de reprise automatique. */
    fun restored(now: Int) {
        if (now < s.now) {
            // Horloge monotone remise à zéro (redémarrage du téléphone) : on ne peut plus mesurer le temps écoulé.
            note("Le téléphone a redémarré pendant le test.")
            endCurrent(s.now, completed = false, interrupted = true)
            finish(EndReason.STOPPED)
            return
        }
        if (s.phase == Phase.RUNNING || s.phase == Phase.AWAIT_SPEED) pause(PauseReason.RESTORED, now) else tick(now)
    }

    fun stop(now: Int) {
        tick(now)
        if (s.phase == Phase.FINISHED) return
        endCurrent(now, completed = false, interrupted = true, note = "Arrêt")
        s = s.copy(pending = null)
        finish(EndReason.STOPPED)
    }

    fun symptom(sym: Symptom, now: Int) {
        tick(now)
        endCurrent(minOf(now, p.BUDGET_SEC), completed = false, interrupted = true, note = sym.label)
        s = s.copy(symptom = sym, pending = null, endReason = EndReason.SYMPTOM, phase = Phase.FINISHED)
    }

    // ---------- Outils ----------

    private fun endCurrent(now: Int, completed: Boolean, interrupted: Boolean = false, note: String = "") {
        if (s.current == null) return
        replaceCurrent { it.copy(endAt = now, completed = completed, interrupted = interrupted || it.interrupted, note = listOf(it.note, note).filter { n -> n.isNotBlank() }.joinToString(" · ")) }
    }

    private fun suggestLower(kmh: Double) {
        val lower = Treadmill.lower(kmh, t)
        if (lower < kmh) s = s.copy(suggestions = s.suggestions + Suggestion(s.kind.takeIf { it == TestKind.CONFIRM_WALK || it == TestKind.CONFIRM_RUN } ?: TestKind.CONFIRM_WALK, lower, "Essayer une allure un peu plus basse lors d'un autre test."))
    }

    private fun cooldown(now: Int, slower: Boolean = false) {
        if (s.current != null) endCurrent(now, completed = false, interrupted = s.current!!.kind in setOf(SegKind.STEP, SegKind.BLOCK, SegKind.REP))
        val plan = minOf(p.COOLDOWN_SEC, p.BUDGET_SEC - now)
        if (plan <= 0) {
            finish(EndReason.BUDGET)
            return
        }
        val v = if (slower) Treadmill.lower(easy(), t) else easy()
        s = s.copy(
            phase = Phase.RUNNING, pending = null,
            segments = s.segments + Segment(SegKind.COOLDOWN, 0, v, v, plan, now, stableSince = now),
        )
    }

    private fun finish(reason: EndReason) {
        s = s.copy(phase = Phase.FINISHED, endReason = s.endReason ?: reason, pending = null)
    }

    // ---------- Résultat ----------

    fun result(): CalibrationRecord {
        val st = s
        val refs = ArrayList<PaceRef>()
        val notes = ArrayList(st.notes)
        val suggestions = ArrayList(st.suggestions)
        fun ref(kind: RefKind, kmh: Double, status: RefStatus, sec: Int) =
            PaceRef(kind, kmh, 0.0, st.day, st.kind.id, PaceProtocol.VERSION, RefSource.TEST, status, sec, st.id)

        if (st.endReason != EndReason.SYMPTOM) {
            if (st.walkEasyKmh > 0) refs += ref(RefKind.WALK_EASY, st.walkEasyKmh, RefStatus.PROVISIONAL, st.walkEasyStableSec)
            when (st.kind) {
                TestKind.WALK_STEPS -> {
                    val ok = st.segments.filter { it.kind == SegKind.STEP && it.completed && !it.interrupted && it.allComfortable }
                        .distinctBy { (it.declaredKmh * 100).toLong() }
                    val chosen = when {
                        ok.size >= 2 -> ok[ok.size - 2]
                        ok.size == 1 -> ok[0]
                        else -> null
                    }
                    if (chosen != null) {
                        refs += ref(RefKind.WALK_ACTIVE, chosen.declaredKmh, RefStatus.PROVISIONAL, chosen.durationSec)
                        if (ok.size >= 2) notes += "Marche active retenue : le palier confortable qui précède le dernier (${Fmt.kmh(chosen.declaredKmh)} km/h)."
                        else notes += "Un seul palier confortable : résultat provisoire."
                        if (suggestions.none { it.kind == TestKind.CONFIRM_WALK }) suggestions += Suggestion(TestKind.CONFIRM_WALK, chosen.declaredKmh, "Confirmer cette marche active pendant 10 min à vitesse constante.")
                    } else notes += "Aucun palier confortable : pas de marche active enregistrée."
                }
                TestKind.CONFIRM_WALK, TestKind.CONFIRM_RUN -> {
                    val b = st.segments.lastOrNull { it.kind == SegKind.BLOCK }
                    if (b != null && b.completed && !b.interrupted && b.allComfortable && b.durationSec >= PaceProtocol.CONFIRM_BLOCK_SEC) {
                        refs += ref(st.kind.produces, b.declaredKmh, RefStatus.CONFIRMED, b.durationSec)
                        notes += "${st.kind.produces.label} confirmée pendant ${b.durationSec / 60} min à ${Fmt.kmh(b.declaredKmh)} km/h."
                        val veryEasy = b.checks.all { (b.obs(it)?.rpe ?: 99) <= PaceProtocol.WALK_EASY_MAX_RPE }
                        if (veryEasy) Treadmill.raise(b.declaredKmh, st.treadmill)?.let {
                            suggestions += Suggestion(st.kind, it, "Allure très facile : un autre test pourra essayer ${Fmt.kmh(it)} km/h (hausse de 0,3 km/h au plus).")
                        }
                    } else notes += "Pas de confirmation : il faut 10 min complètes à la même vitesse, avec trois contrôles confortables."
                }
                TestKind.WALK_RUN -> {
                    val reps = st.segments.filter { it.kind == SegKind.REP }
                    val ok = reps.filter { it.completed && !it.interrupted && it.allComfortable }
                    val recoveriesOk = st.segments.filter { it.kind == SegKind.RECOVERY }.all { it.completed && !it.interrupted }
                    val sameSpeed = ok.map { (it.declaredKmh * 100).toLong() }.distinct().size == 1
                    if (ok.size >= PaceProtocol.JOG_REPS && sameSpeed && recoveriesOk) {
                        refs += ref(RefKind.RUN_EASY, ok[0].declaredKmh, RefStatus.PROVISIONAL, PaceProtocol.JOG_REP_SEC)
                        notes += "Trot facile provisoire : 3 répétitions d'une minute confortables à ${Fmt.kmh(ok[0].declaredKmh)} km/h. Réglage de départ pour des blocs courts, pas une capacité à courir longtemps."
                    } else if (ok.size >= PaceProtocol.JOG_REPS && !sameSpeed) notes += "Répétitions à des vitesses différentes : pas de trot facile enregistré."
                    else notes += "Moins de 3 répétitions confortables : pas de trot facile enregistré."
                    if (ok.any { r -> r.observations.any { it.late } }) notes += "Au moins une réponse a été donnée après le retour à la marche."
                }
            }
        }
        val produced = refs.any { it.kind == st.kind.produces }
        val status = when (st.endReason) {
            EndReason.SYMPTOM -> CalStatus.ABORTED
            EndReason.STOPPED -> CalStatus.STOPPED
            EndReason.COMPLETED -> if (produced && (st.kind != TestKind.WALK_STEPS || st.walkEasyKmh > 0)) CalStatus.COMPLETE else CalStatus.PARTIAL
            else -> CalStatus.PARTIAL
        }
        if (st.stopReason.isNotBlank()) notes.add(0, st.stopReason)
        return CalibrationRecord(
            id = st.id, kind = st.kind, day = st.day, wallStartMillis = st.wallStartMillis,
            durationSec = st.segments.maxOfOrNull { if (it.ended) it.endAt else st.now } ?: 0,
            status = status, endReason = st.endReason, symptom = st.symptom, refs = refs, suggestions = suggestions.distinct(),
            notes = notes.distinct(), segments = st.segments, treadmill = st.treadmill, voiceUsed = st.voiceUsed,
            voiceBaseline = st.voiceBaseline,
        )
    }
}

/** Choix du test et des vitesses de départ selon le profil. */
object PaceTests {
    fun recommend(p: Profile): TestKind = when {
        p.healthFlag -> TestKind.WALK_STEPS
        p.capacity == Capacity.RUN_10 -> TestKind.CONFIRM_RUN
        p.capacity == Capacity.WALK_RUN -> TestKind.WALK_RUN
        p.capacity == Capacity.WALK_ONLY && p.paceRefs.walkActive?.status == RefStatus.PROVISIONAL -> TestKind.CONFIRM_WALK
        else -> TestKind.WALK_STEPS
    }

    /** Vitesse candidate d'un test, ou 0 si elle doit être choisie par l'utilisateur. */
    fun candidate(p: Profile, kind: TestKind, history: List<CalibrationRecord> = emptyList()): Double {
        history.firstOrNull()?.suggestions?.firstOrNull { it.kind == kind }?.let { return it.kmh }
        return when (kind) {
            TestKind.CONFIRM_WALK -> p.paceRefs.walkActive?.kmh ?: p.usualWalkKmh
            TestKind.CONFIRM_RUN -> p.paceRefs.runEasy?.kmh ?: p.usualRunKmh
            TestKind.WALK_RUN -> p.paceRefs.runEasy?.kmh ?: p.usualRunKmh
            TestKind.WALK_STEPS -> 0.0
        }
    }

    /** Raison de ne pas proposer un test, sinon null. */
    fun lockedReason(p: Profile, kind: TestKind, history: List<CalibrationRecord> = emptyList()): String? = when {
        kind.running && p.healthFlag -> "Avis médical conseillé avant de trotter."
        kind == TestKind.CONFIRM_RUN && p.capacity != Capacity.RUN_10 -> "Pour qui court déjà 10 min à l'aise."
        kind == TestKind.CONFIRM_RUN && candidate(p, kind, history) <= 0 -> "Indique d'abord ton trot habituel."
        kind == TestKind.CONFIRM_WALK && candidate(p, kind, history) <= 0 -> "Fais d'abord le test de marche, ou indique ta marche active habituelle."
        else -> null
    }

    /** Données à demander avant un test, si elles manquent au profil. */
    fun missing(p: Profile): List<String> = buildList {
        if (p.capacity == null) add("capacity")
        if (!p.treadmillChecked) add("treadmill")
    }
}
