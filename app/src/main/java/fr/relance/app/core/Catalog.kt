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
 * `pace` : warm, recovery, walk, hill, run, tempo, fast, vr (test), ou kmh (vitesse fixe `kmh`, en km/h).
 * `incline` : un nombre ou « profile ».
 * Un bloc REPEAT répète ses `blocks` autant de fois que le paramètre `repeat`.
 */
@Serializable
data class BlockT(
    val kind: String, val minutes: String = "0", val pace: String = "walk", val paceFactor: Double = 1.0,
    val incline: String = "0", val repeat: String = "", val blocks: List<BlockT> = emptyList(),
    /** Vitesse fixe, quand `pace` vaut « kmh » (plafonnée à la vitesse maximale du tapis). */
    val kmh: Double = 0.0,
)

@Serializable
data class SessionType(
    val id: String, val name: String, val kind: SessionKind, val title: String = "", val detail: String = "",
    val purpose: String = "", val needsIncline: Boolean = false,
    val params: List<ParamDef> = emptyList(), val blocks: List<BlockT> = emptyList(),
)

@Serializable
data class SessionRef(val type: String, val params: Map<String, Double> = emptyMap())

/**
 * Un palier : la ou les séances clés d'une semaine (jouées à tour de rôle), les niveaux concernés (ancien format),
 * et éventuellement la séance d'endurance propre à ce palier.
 */
@Serializable
data class StepT(
    val sessions: List<SessionRef>, val levels: List<Level> = Level.values().toList(), val note: String = "",
    val easy: SessionRef? = null,
)

/** Variante d'un programme pour un niveau : ses paliers, et les réglages qui diffèrent du programme. */
@Serializable
data class VariantT(
    val steps: List<StepT> = emptyList(), val description: String = "",
    val keysPerStep: Int? = null, val startAtMastered: Boolean? = null, val easy: EasyT? = null, val deload: DeloadT? = null,
    val weekPatterns: Map<String, String>? = null, val weekNotes: Map<String, String>? = null,
)

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
    /** Séances clés à réussir pour valider un palier : moins de jours par semaine, programme plus long. */
    val keysPerStep: Int = 2,
    /** Séquence des rôles d'une semaine selon le nombre de séances : K clé, E endurance, R récupération. */
    val weekPatterns: Map<String, String> = mapOf("1" to "K", "2" to "KK", "3" to "KEK", "4" to "KEKR"),
    /** Mot du coach par numéro de semaine (« 1 », « 2 »…). */
    val weekNotes: Map<String, String> = emptyMap(),
    /** Variantes par niveau. Un niveau absent n'a pas accès au programme. Vide : ancien format (`steps` filtrés par niveau). */
    val variants: Map<Level, VariantT> = emptyMap(),
) {
    /** Niveaux pour lesquels le programme existe. */
    val levels: List<Level>
        get() = if (variants.isNotEmpty()) Level.values().filter { it in variants }
        else Level.values().filter { l -> steps.any { l in it.levels } }

    /** Le programme tel qu'il s'applique à un niveau : paliers et réglages de sa variante. Null si ce niveau n'existe pas. */
    fun forLevel(level: Level): ProgramTemplate? {
        if (variants.isEmpty()) {
            val own = steps.filter { level in it.levels }
            return if (own.isEmpty()) null else copy(steps = own.map { it.copy(levels = listOf(level)) })
        }
        val v = variants[level] ?: return null
        return copy(
            steps = v.steps.map { it.copy(levels = listOf(level)) },
            description = v.description.ifBlank { description },
            keysPerStep = v.keysPerStep ?: keysPerStep, startAtMastered = v.startAtMastered ?: startAtMastered,
            easy = v.easy ?: easy, deload = v.deload ?: deload,
            weekPatterns = v.weekPatterns ?: weekPatterns, weekNotes = v.weekNotes ?: weekNotes,
            variants = emptyMap(),
        )
    }

    /** Niveau proposé par défaut : celui du sportif s'il existe, sinon le plus proche (le plus facile en cas d'égalité). */
    fun closestLevel(level: Level): Level? = levels.minWithOrNull(compareBy<Level> { kotlin.math.abs(it.ordinal - level.ordinal) }.thenBy { it.ordinal })
}

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

    /** Raison de refuser un catalogue téléchargé (incomplet, ou trop récent pour cette version de l'app), sinon null. */
    fun check(c: Catalog): String? {
        if (c.schema > 1) return "Ces programmes demandent une version plus récente de l'app."
        if (c.visiblePrograms.isEmpty()) return "Le catalogue reçu ne contient aucun programme."
        val types = c.sessionTypes.map { it.id }.toSet()
        listOf(c.specials.test, c.specials.recovery, c.specials.flatWalk).firstOrNull { it !in types }?.let { return "Type de séance manquant : $it." }
        for (p in c.programs) {
            if (p.levels.isEmpty()) return "Programme vide : ${p.name}."
            for (level in p.levels) {
                val t = p.forLevel(level) ?: continue
                if (t.steps.isEmpty()) return "Programme ${p.name} : aucun palier pour le niveau ${level.name}."
                val refs = t.steps.flatMap { st -> st.sessions.map { it.type } + listOfNotNull(st.easy?.type) } + t.easy.types + t.easy.fallback
                refs.firstOrNull { it !in types }?.let { return "Programme ${p.name} : type de séance inconnu « $it »." }
            }
        }
        return null
    }
}

/** Objectifs de distance : durée en minutes = 60 × distance (km) / vitesse retenue (km/h), arrondie à la minute supérieure. */
object Distances {
    fun minutes(km: Double, kmh: Double): Double = if (kmh <= 0) 0.0 else ceil(60.0 * km / kmh - 1e-9)

    /**
     * Recalcule la durée des séances à objectif de distance avec la vitesse de trot effectivement retenue
     * (références, pas et bornes du tapis). Les autres séances ne changent pas.
     */
    fun apply(params: Map<String, Double>, runKmh: Double): Map<String, Double> {
        val km = params["km"] ?: return params
        if (km <= 0) return params
        return params + ("minutes" to minutes(km, runKmh))
    }
}

/** Un palier une fois adapté au profil : paramètres résolus (distance convertie en minutes, etc.). */
@Serializable
data class ResolvedStep(val sessions: List<SessionRef>, val note: String = "", val easy: SessionRef? = null)

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
    fun resolve(t0: ProgramTemplate, p: Profile, paces: Paces, types: (String) -> SessionType?): List<ResolvedStep> {
        val t = t0.forLevel(p.level) ?: return emptyList()
        val run = Physio.speedsFor(p, 1.0, paces).run
        var steps = t.steps.map { s ->
            ResolvedStep(s.sessions.map { r ->
                val km = r.params["km"]
                if (km != null && km > 0) r.copy(params = r.params + ("minutes" to Distances.minutes(km, run))) else r
            }, s.note, s.easy)
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

    /** Séquence des rôles (K, E, R) d'une semaine à `n` séances. */
    fun patternFor(t: ProgramTemplate, n: Int): String =
        t.weekPatterns[n.toString()] ?: t.weekPatterns[t.weekPatterns.keys.maxByOrNull { it.toIntOrNull() ?: 0 }] ?: "K"

    fun keysPerWeek(t: ProgramTemplate, n: Int): Int = patternFor(t, n).count { it == 'K' }

    /**
     * Durée en semaines : un palier se valide après `keysPerStep` séances clés, semaines allégées comprises
     * (pas de progression pendant une semaine allégée).
     */
    fun weeksFor(stepCount: Int, deloadEvery: Int, keysPerWeek: Int = 1, keysPerStep: Int = 1): Int {
        val kpw = max(1, keysPerWeek)
        val kps = max(1, keysPerStep)
        var week = 0
        var step = 0
        var keys = 0
        while (step < stepCount && week < 2000) {
            val deload = deloadEvery > 0 && week % deloadEvery == deloadEvery - 1 && step > 0
            if (!deload) repeat(kpw) {
                if (step < stepCount && ++keys >= kps) {
                    step++
                    keys = 0
                }
            }
            week++
        }
        return week
    }

    fun lockedReason(t: ProgramTemplate, p: Profile): String? = when {
        t.levels.isEmpty() -> "Programme sans niveau disponible"
        p.healthFlag && !t.requires.allowHealthFlag -> "Avis médical conseillé avant de courir"
        p.longestRunMin < t.requires.minLongestRunMin -> "Après ${t.requires.minLongestRunMin} min de course continue"
        else -> null
    }

    fun recommended(c: Catalog, p: Profile): ProgramTemplate? {
        val visible = c.visiblePrograms
        return visible.firstOrNull { t ->
            lockedReason(t, p) == null && p.level in t.levels && t.recommend.any { r ->
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

    private fun rawSpeedOf(pace: String, factor: Double, s: Speeds, p: Profile, kmh: Double = 0.0): Double = Physio.round1(
        when (pace) {
            "warm" -> s.warm; "recovery" -> s.recovery; "walk" -> s.walk; "hill" -> s.hill
            "run" -> s.run; "tempo" -> s.tempo; "fast" -> s.fast; "vr" -> p.vr; "kmh" -> kmh
            else -> s.walk
        } * factor
    )

    /** Vitesse réellement réglable : pas et bornes du tapis. Une vitesse fixe (`kmh`) garde sa valeur, bornée de même. */
    private fun speedOf(pace: String, factor: Double, s: Speeds, p: Profile, kmh: Double = 0.0): Double =
        Treadmill.fit(rawSpeedOf(pace, factor, s, p, kmh), Treadmill.spec(p))

    private val paceNames = mapOf(
        "warm" to "marche facile", "recovery" to "marche de récupération", "walk" to "marche", "hill" to "marche en côte",
        "run" to "trot", "tempo" to "allure soutenue", "fast" to "allure rapide", "vr" to "test", "kmh" to "vitesse fixée",
    )

    /** Explique une vitesse prévue hors des bornes du tapis. */
    private fun conflict(pace: String, raw: Double, fitted: Double, p: Profile): String? = when {
        raw < p.treadmillMinKmh - 0.001 -> "${(paceNames[pace] ?: pace).replaceFirstChar { it.uppercase() }} prévue à ${Fmt.kmh(raw)} km/h, sous la vitesse minimale du tapis (${Fmt.kmh(p.treadmillMinKmh)} km/h) : la consigne passe à ${Fmt.kmh(fitted)} km/h. Si c'est trop rapide, marche à côté de la bande ou arrête le bloc."
        raw > p.treadmillMaxKmh + 0.001 -> "${(paceNames[pace] ?: pace).replaceFirstChar { it.uppercase() }} prévue à ${Fmt.kmh(raw)} km/h, au-dessus de la vitesse maximale du tapis (${Fmt.kmh(p.treadmillMaxKmh)} km/h) : la consigne est plafonnée, l'effort sera plus facile que prévu et une distance prendra plus de temps."
        else -> null
    }

    private fun inclineOf(expr: String, p: Profile): Double = if (expr == "profile") p.inclinePct else expr.toDoubleOrNull() ?: 0.0

    private fun kindOf(k: String): BlockKind = try {
        BlockKind.valueOf(k)
    } catch (e: IllegalArgumentException) {
        BlockKind.WALK
    }

    /** Déplie les blocs d'un type de séance avec ses paramètres. */
    fun expand(blocks: List<BlockT>, params: Map<String, Double>, s: Speeds, p: Profile, warnings: MutableSet<String>? = null): List<Block> {
        val out = ArrayList<Block>()
        for (b in blocks) {
            if (b.kind == "REPEAT") {
                val n = (params[b.repeat] ?: evalMinutes(b.repeat, params)).roundToInt().coerceAtLeast(0)
                repeat(n) { out += expand(b.blocks, params, s, p, warnings) }
            } else {
                val minutes = max(1.0, evalMinutes(b.minutes, params))
                val raw = rawSpeedOf(b.pace, b.paceFactor, s, p, b.kmh)
                val v = speedOf(b.pace, b.paceFactor, s, p, b.kmh)
                if (warnings != null) conflict(b.pace, raw, v, p)?.let { warnings += it }
                out += Block(kindOf(b.kind), (minutes * 60).roundToInt(), v, inclineOf(b.incline, p))
            }
        }
        return out
    }

    private fun fmt(v: Double) = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else Fmt.kmh(v)

    private fun fill(text: String, params: Map<String, Double>, s: Speeds, p: Profile): String {
        var r = text.replace("{incline}", fmt(p.inclinePct))
        for (pace in listOf("warm", "recovery", "walk", "hill", "run", "tempo", "fast")) r = r.replace("{pace:$pace}", Fmt.kmh(speedOf(pace, 1.0, s, p)))
        for ((k, v) in params) r = r.replace("{$k:min}", duration(v)).replace("{$k}", fmt(v))
        return r
    }

    /** Durée lisible : « 3 min », « 1 min 15 s », « 45 s ». */
    fun duration(minutes: Double): String {
        val sec = (minutes * 60).roundToInt()
        val m = sec / 60
        val s = sec % 60
        return when {
            s == 0 -> "$m min"
            m == 0 -> "$s s"
            else -> "$m min $s s"
        }
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
        val warnings = LinkedHashSet<String>()
        val blocks = expand(type.blocks, params, speeds, p, warnings)
        return PlannedSession(
            day, type.kind, fill(type.title.ifBlank { type.name }, params, speeds, p), fill(type.detail, params, speeds, p),
            blocks, isKey, week, deload, PaceBasis.of(p), warnings.toList(),
        )
    }
}
