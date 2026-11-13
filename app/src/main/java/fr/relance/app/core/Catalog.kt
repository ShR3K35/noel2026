package fr.relance.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Catalogue de programmes, éditable par le coach depuis l'interface d'administration.
 * Le même format est lu par l'app (ici) et par le simulateur web (web/src/engine) : toute règle
 * changée d'un côté doit l'être de l'autre, les jeux de tests partagés le vérifient.
 */

/** Modèle d'allures : chaque vitesse est déduite de la vitesse de référence (VR) du profil. */
@Serializable
data class Paces(
    val warm: Double = 0.8, val recovery: Double = 0.75, val walk: Double = 0.95, val hill: Double = 0.85,
    val runOfVr: Double = 1.2, val runMin: Double = 6.0, val runMax: Double = 10.0,
    val tempoOfRun: Double = 1.12, val fastOfRun: Double = 1.25,
)

/** Types de séance utilisés par l'app elle-même : test de forme, récupération, marche à plat après une gêne. */
@Serializable
data class Specials(val test: String = "test", val recovery: String = "recovery", val flatWalk: String = "flat-walk")

@Serializable
data class ParamDef(val name: String, val label: String = "", val default: Double = 0.0, val min: Double = 0.0, val max: Double = 999.0, val unit: String = "min")

/**
 * Un bloc de séance. `minutes` vaut un nombre, un paramètre (« a ») ou un paramètre moins une constante (« minutes-6 »).
 * `pace` : warm, recovery, walk, hill, run, tempo, fast, ou vr (test). `incline` : un nombre ou « profile ».
 * Un bloc REPEAT répète ses `blocks` autant de fois que le paramètre `repeat`.
 */
@Serializable
data class BlockT(
    val kind: String, val minutes: String = "0", val pace: String = "walk", val paceFactor: Double = 1.0,
    val incline: String = "0", val repeat: String = "", val blocks: List<BlockT> = emptyList(),
)

@Serializable
data class SessionType(
    val id: String, val name: String, val kind: SessionKind, val title: String = "", val detail: String = "",
    val purpose: String = "", val needsIncline: Boolean = false,
    val params: List<ParamDef> = emptyList(), val blocks: List<BlockT> = emptyList(),
)

@Serializable
data class SessionRef(val type: String, val params: Map<String, Double> = emptyMap())

/** Un palier : la ou les séances clés d'une semaine (jouées à tour de rôle), et les niveaux concernés. */
@Serializable
data class StepT(val sessions: List<SessionRef>, val levels: List<Level> = Level.values().toList(), val note: String = "")

@Serializable
data class Goal(val kind: String = "none", val minutes: Int = 0, val km: Double = 0.0)

@Serializable
data class Requires(val minLongestRunMin: Int = 0, val allowHealthFlag: Boolean = false)

/** Pour qui ce programme est conseillé. */
@Serializable
data class RecommendRule(
    val levels: List<Level> = Level.values().toList(), val minRun: Int = 0, val maxRun: Int = 999, val healthFlag: Boolean = false,
)

/** Séances d'endurance entre les séances clés : durée qui croît de semaine en semaine, types à tour de rôle. */
@Serializable
data class EasyT(
    val types: List<String> = listOf("hill", "walk"), val fallback: String = "walk",
    val startMinutes: Int = 15, val stepMinutes: Int = 5, val maxMinutes: Int = 30,
)

@Serializable
data class DeloadT(val every: Int = 4, val testAtEnd: Boolean = true, val factor: Double = 0.7)

@Serializable
data class ProgramTemplate(
    val id: String, val name: String, val validation: String = "", val description: String = "",
    val order: Int = 0, val enabled: Boolean = true,
    val goal: Goal = Goal(), val requires: Requires = Requires(), val recommend: List<RecommendRule> = emptyList(),
    val steps: List<StepT> = emptyList(), val startAtMastered: Boolean = true,
    val easy: EasyT = EasyT(), val deload: DeloadT = DeloadT(),
    /** Séquence des rôles d'une semaine selon le nombre de séances : K clé, E endurance, R récupération. */
    val weekPatterns: Map<String, String> = mapOf("1" to "K", "2" to "KK", "3" to "KEK", "4" to "KEKR"),
    /** Mot du coach par numéro de semaine (« 1 », « 2 »…). */
    val weekNotes: Map<String, String> = emptyMap(),
)

@Serializable
data class Catalog(
    val schema: Int = 1, val version: Int = 0, val publishedAt: String = "", val notes: String = "",
    val paces: Paces = Paces(), val specials: Specials = Specials(),
    val sessionTypes: List<SessionType> = emptyList(), val programs: List<ProgramTemplate> = emptyList(),
) {
    fun program(id: String): ProgramTemplate? = programs.firstOrNull { it.id == id }
    fun type(id: String): SessionType? = sessionTypes.firstOrNull { it.id == id }
    val visiblePrograms: List<ProgramTemplate> get() = programs.filter { it.enabled }.sortedBy { it.order }
}

object Catalogs {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Catalogue livré avec l'app, utilisé hors ligne et tant qu'aucune synchronisation n'a eu lieu. */
    val builtin: Catalog by lazy {
        val text = Catalogs::class.java.classLoader!!.getResourceAsStream("catalog.json")!!.bufferedReader().use { it.readText() }
        json.decodeFromString(Catalog.serializer(), text)
    }

    /** Catalogue en usage : le dernier synchronisé, sinon celui livré avec l'app. */
    @Volatile
    var current: Catalog = builtin

    fun parse(text: String): Catalog = json.decodeFromString(Catalog.serializer(), text)
}

/** Un palier une fois adapté au profil : paramètres résolus (distance convertie en minutes, etc.). */
@Serializable
data class ResolvedStep(val sessions: List<SessionRef>, val note: String = "")

/** Ce qu'un programme emporte avec lui : il garde sa version jusqu'à la fin, même si le catalogue change. */
@Serializable
data class ProgramSnapshot(
    val template: ProgramTemplate, val sessionTypes: List<SessionType>, val paces: Paces, val specials: Specials,
    val catalogVersion: Int,
) {
    fun type(id: String): SessionType? = sessionTypes.firstOrNull { it.id == id }
}

object Templates {
    /** Minutes de course continue qu'exige une séance : sert à savoir si un palier est déjà maîtrisé. */
    private fun runDemand(ref: SessionRef, types: (String) -> SessionType?): Int {
        val t = types(ref.type) ?: return 0
        return when (t.kind) {
            SessionKind.CONTINUOUS -> (ref.params["minutes"] ?: 0.0).toInt()
            SessionKind.INTERVALS -> (ref.params["a"] ?: 0.0).toInt()
            else -> 0
        }
    }

    /** Adapte l'échelle d'un programme à un profil : niveau, distance convertie en durée, paliers déjà maîtrisés. */
    fun resolve(t: ProgramTemplate, p: Profile, paces: Paces, types: (String) -> SessionType?): List<ResolvedStep> {
        val run = Physio.speedsFor(p, 1.0, paces).run
        var steps = t.steps.filter { p.level in it.levels }.map { s ->
            ResolvedStep(s.sessions.map { r ->
                val km = r.params["km"]
                if (km != null && km > 0) r.copy(params = r.params - "km" + ("minutes" to ceil(km / run * 60))) else r
            }, s.note)
        }
        if (steps.isEmpty()) return steps
        // Au-delà de l'objectif, les paliers intermédiaires plus longs que le dernier n'ont pas de sens.
        val last = steps.last()
        val goalDemand = runDemand(last.sessions.first(), types)
        if (goalDemand > 0) {
            steps = steps.dropLast(1).filter { runDemand(it.sessions.first(), types) < goalDemand } + last
        }
        // Quelqu'un qui court déjà ne repart pas du début : on démarre au dernier palier maîtrisé.
        if (t.startAtMastered && p.longestRunMin >= 5) {
            val mastered = steps.indexOfLast { s -> runDemand(s.sessions.first(), types).let { it in 1..p.longestRunMin } }
            if (mastered > 0) steps = steps.drop(mastered)
        }
        return steps
    }

    fun weeksFor(stepCount: Int, deloadEvery: Int): Int {
        var week = 0
        var step = 0
        while (step < stepCount) {
            if (!(deloadEvery > 0 && week % deloadEvery == deloadEvery - 1 && step > 0)) step++
            week++
        }
        return week
    }

    fun lockedReason(t: ProgramTemplate, p: Profile): String? = when {
        p.healthFlag && !t.requires.allowHealthFlag -> "Avis médical conseillé avant de courir"
        p.longestRunMin < t.requires.minLongestRunMin -> "Après ${t.requires.minLongestRunMin} min de course continue"
        else -> null
    }

    fun recommended(c: Catalog, p: Profile): ProgramTemplate? {
        val visible = c.visiblePrograms
        return visible.firstOrNull { t ->
            lockedReason(t, p) == null && t.recommend.any { r ->
                p.level in r.levels && p.longestRunMin in r.minRun..r.maxRun && (!r.healthFlag || p.healthFlag) && (r.healthFlag || !p.healthFlag)
            }
        } ?: visible.firstOrNull { lockedReason(it, p) == null }
    }

    // ---------- Construction d'une séance ----------

    private fun evalMinutes(expr: String, params: Map<String, Double>): Double {
        val e = expr.replace(" ", "")
        e.toDoubleOrNull()?.let { return it }
        val m = Regex("^([A-Za-z_][A-Za-z0-9_]*)([+-])(\\d+(?:\\.\\d+)?)$").find(e)
        if (m != null) {
            val base = params[m.groupValues[1]] ?: 0.0
            val k = m.groupValues[3].toDouble()
            return if (m.groupValues[2] == "+") base + k else base - k
        }
        return params[e] ?: 0.0
    }

    private fun speedOf(pace: String, factor: Double, s: Speeds, p: Profile): Double = Physio.round1(
        when (pace) {
            "warm" -> s.warm; "recovery" -> s.recovery; "walk" -> s.walk; "hill" -> s.hill
            "run" -> s.run; "tempo" -> s.tempo; "fast" -> s.fast; "vr" -> p.vr
            else -> s.walk
        } * factor
    ).coerceAtMost(p.treadmillMaxKmh)

    private fun inclineOf(expr: String, p: Profile): Double = if (expr == "profile") p.inclinePct else expr.toDoubleOrNull() ?: 0.0

    private fun kindOf(k: String): BlockKind = try {
        BlockKind.valueOf(k)
    } catch (e: IllegalArgumentException) {
        BlockKind.WALK
    }

    /** Déplie les blocs d'un type de séance avec ses paramètres. */
    fun expand(blocks: List<BlockT>, params: Map<String, Double>, s: Speeds, p: Profile): List<Block> {
        val out = ArrayList<Block>()
        for (b in blocks) {
            if (b.kind == "REPEAT") {
                val n = (params[b.repeat] ?: evalMinutes(b.repeat, params)).roundToInt().coerceAtLeast(0)
                repeat(n) { out += expand(b.blocks, params, s, p) }
            } else {
                val minutes = max(1.0, evalMinutes(b.minutes, params))
                out += Block(kindOf(b.kind), (minutes * 60).roundToInt(), speedOf(b.pace, b.paceFactor, s, p), inclineOf(b.incline, p))
            }
        }
        return out
    }

    private fun fmt(v: Double) = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else Fmt.kmh(v)

    private fun fill(text: String, params: Map<String, Double>, s: Speeds, p: Profile): String {
        var r = text.replace("{incline}", fmt(p.inclinePct))
        for (pace in listOf("warm", "recovery", "walk", "hill", "run", "tempo", "fast")) r = r.replace("{pace:$pace}", Fmt.kmh(speedOf(pace, 1.0, s, p)))
        for ((k, v) in params) r = r.replace("{$k}", fmt(v))
        return r
    }

    /** Version courte : on réduit les répétitions, sinon le bloc principal, pour tenir dans `cap` minutes. */
    private fun shorten(type: SessionType, params: Map<String, Double>, cap: Int): Map<String, Double> {
        val repeat = type.blocks.firstOrNull { it.kind == "REPEAT" }
        val total = { ps: Map<String, Double> -> totalMinutes(type.blocks, ps) }
        if (total(params) <= cap) return params
        if (repeat != null) {
            var ps = params
            var n = (params[repeat.repeat] ?: 1.0).roundToInt()
            while (n > 1 && total(ps) > cap) {
                n--
                ps = ps + (repeat.repeat to n.toDouble())
            }
            return ps
        }
        // Pas de répétition : on raccourcit le paramètre de durée principal.
        val key = type.params.firstOrNull { it.name == "minutes" }?.name ?: return params
        var ps = params
        var v = params[key] ?: return params
        while (v > 5 && total(ps) > cap) {
            v -= 1
            ps = ps + (key to v)
        }
        return ps
    }

    private fun totalMinutes(blocks: List<BlockT>, params: Map<String, Double>): Double = blocks.sumOf { b ->
        if (b.kind == "REPEAT") (params[b.repeat] ?: 0.0) * totalMinutes(b.blocks, params)
        else max(1.0, evalMinutes(b.minutes, params))
    }

    /** Allègement d'une semaine de récupération : moins de répétitions, des durées plus courtes. */
    fun deloaded(params: Map<String, Double>, factor: Double): Map<String, Double> = params.mapValues { (k, v) ->
        when (k) {
            "reps" -> max(2.0, ceil(v * factor))
            "minutes" -> max(10.0, ((v * factor / 5).roundToInt() * 5).toDouble())
            else -> v
        }
    }

    fun build(
        type: SessionType, params0: Map<String, Double>, p: Profile, speeds: Speeds, day: Long, week: Int,
        isKey: Boolean, deload: Boolean, cap: Int,
    ): PlannedSession {
        val withDefaults = type.params.associate { it.name to it.default } + params0
        val params = if (cap > 0) shorten(type, withDefaults, cap) else withDefaults
        return PlannedSession(
            day, type.kind, fill(type.title.ifBlank { type.name }, params, speeds, p), fill(type.detail, params, speeds, p),
            expand(type.blocks, params, speeds, p), isKey, week, deload,
        )
    }
}
