package fr.relance.app.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Repères d'allure issus des tests de calibration (voir docs/CALIBRATION.md).
 * Ce sont des vitesses de séance associées à une durée observée : ni VMA, ni VO2max, ni seuil.
 */

@Serializable
enum class RefKind(val label: String, val short: String) {
    WALK_EASY("Marche facile", "marche facile"),
    WALK_ACTIVE("Marche active", "marche active"),
    RUN_EASY("Trot facile", "trot facile"),
}

@Serializable
enum class RefSource(val label: String) {
    TEST("Test de calibration"),
    MANUAL("Saisie manuelle"),
    /** Reprise de l'ancienne vitesse unique : jamais créée automatiquement, gardée pour l'historique des profils importés. */
    LEGACY("Ancienne vitesse de référence"),
}

@Serializable
enum class RefStatus(val label: String) { PROVISIONAL("provisoire"), CONFIRMED("confirmée") }

/**
 * Une référence. `status` CONFIRMED signifie : confirmée pendant `observedSec` secondes continues, à vitesse constante.
 * `kmh` est la vitesse déclarée comme réglée par l'utilisateur (la vitesse proposée est gardée dans le test).
 */
@Serializable
data class PaceRef(
    val kind: RefKind,
    val kmh: Double,
    val inclinePct: Double = 0.0,
    val day: Long,
    val protocol: String,
    val protocolVersion: Int,
    val source: RefSource,
    val status: RefStatus,
    val observedSec: Int,
    val testId: String = "",
) {
    fun describe(): String = buildString {
        append("${Fmt.kmh(kmh)} km/h · ")
        append(
            when (source) {
                RefSource.MANUAL -> "saisie manuelle"
                RefSource.LEGACY -> "ancienne vitesse"
                RefSource.TEST -> "${status.label}" + if (observedSec > 0) " sur ${Templates.duration(observedSec / 60.0)}" else ""
            }
        )
    }
}

/** Les trois références. Chacune peut être absente : une absence ne vaut jamais zéro. */
@Serializable
data class PaceRefs(
    val walkEasy: PaceRef? = null,
    val walkActive: PaceRef? = null,
    val runEasy: PaceRef? = null,
) {
    operator fun get(k: RefKind): PaceRef? = when (k) {
        RefKind.WALK_EASY -> walkEasy
        RefKind.WALK_ACTIVE -> walkActive
        RefKind.RUN_EASY -> runEasy
    }

    fun with(r: PaceRef): PaceRefs = when (r.kind) {
        RefKind.WALK_EASY -> copy(walkEasy = r)
        RefKind.WALK_ACTIVE -> copy(walkActive = r)
        RefKind.RUN_EASY -> copy(runEasy = r)
    }

    fun without(k: RefKind): PaceRefs = when (k) {
        RefKind.WALK_EASY -> copy(walkEasy = null)
        RefKind.WALK_ACTIVE -> copy(walkActive = null)
        RefKind.RUN_EASY -> copy(runEasy = null)
    }

    val isEmpty: Boolean get() = walkEasy == null && walkActive == null && runEasy == null

    companion object {
        /**
         * Règle de remplacement après un test dédié :
         * - une saisie manuelle ou une référence confirmée remplace toujours l'ancienne ;
         * - une référence provisoire remplace une référence absente, provisoire, manuelle ou ancienne ;
         * - une référence provisoire ne remplace une référence confirmée que si elle est plus lente (prudence).
         *   Sinon, elle reste une candidate à confirmer.
         */
        fun accepts(old: PaceRef?, new: PaceRef): Boolean = when {
            old == null -> true
            new.source == RefSource.MANUAL || new.status == RefStatus.CONFIRMED -> true
            old.status == RefStatus.CONFIRMED && old.source == RefSource.TEST -> new.kmh < old.kmh - 0.05
            else -> true
        }
    }

    /** Applique des résultats ; renvoie les nouvelles références et celles qui n'ont pas été retenues. */
    fun merge(results: List<PaceRef>): Pair<PaceRefs, List<PaceRef>> {
        var r = this
        val kept = ArrayList<PaceRef>()
        for (n in results) if (accepts(r[n.kind], n)) r = r.with(n) else kept += n
        return r to kept
    }
}

/** Réglages réels du tapis : bornes et pas de réglage de la vitesse. */
@Serializable
data class TreadmillSpec(val minKmh: Double = 1.0, val maxKmh: Double = 14.0, val stepKmh: Double = 0.1)

/** Arrondis aux vitesses réellement réglables. Calculs en centièmes de km/h, pour éviter les erreurs d'arrondi. */
object Treadmill {
    private fun c(v: Double): Long = (v * 100).roundToLong()
    private fun kmh(c: Long): Double = c / 100.0

    fun spec(p: Profile) = TreadmillSpec(p.treadmillMinKmh, p.treadmillMaxKmh, p.treadmillStepKmh)

    private fun stepC(t: TreadmillSpec): Long = c(t.stepKmh).coerceAtLeast(1)

    /** Vitesse réglable la plus proche, dans les bornes du tapis. */
    fun fit(v: Double, t: TreadmillSpec): Double {
        val s = stepC(t)
        val near = ((c(v).toDouble() / s).roundToInt().toLong()) * s
        val lo = ceilTo(c(t.minKmh), s)
        val hi = (c(t.maxKmh) / s) * s
        return kmh(near.coerceIn(lo, maxOf(lo, hi)))
    }

    /** Vitesse réglable immédiatement inférieure ou égale. */
    fun floor(v: Double, t: TreadmillSpec): Double {
        val s = stepC(t)
        val f = (floor(c(v).toDouble() / s)).toLong() * s
        val lo = ceilTo(c(t.minKmh), s)
        return kmh(f.coerceIn(lo, maxOf(lo, (c(t.maxKmh) / s) * s)))
    }

    private fun ceilTo(x: Long, s: Long) = ((x + s - 1) / s) * s

    /**
     * Hausse proposée : au plus `maxRise` km/h, en pas entiers du tapis. Null si le pas du tapis dépasse la hausse permise
     * ou si la vitesse maximale est atteinte.
     */
    fun raise(v: Double, t: TreadmillSpec, maxRise: Double = PaceProtocol.MAX_RAISE_KMH): Double? {
        val s = stepC(t)
        val steps = c(maxRise) / s
        if (steps < 1) return null
        val next = fit(v, t) + kmh(steps * s)
        val capped = floor(minOf(next, t.maxKmh), t)
        return capped.takeIf { it > fit(v, t) + 0.001 }
    }

    /** Baisse proposée (même pas). */
    fun lower(v: Double, t: TreadmillSpec, drop: Double = PaceProtocol.MAX_RAISE_KMH): Double {
        val s = stepC(t)
        val steps = maxOf(1, c(drop) / s)
        return fit(v - kmh(steps * s), t)
    }

    fun options(t: TreadmillSpec, from: Double, to: Double): List<Double> {
        val s = stepC(t)
        val out = ArrayList<Double>()
        var x = c(fit(from, t))
        val end = c(fit(to, t))
        while (x <= end && out.size < 400) {
            out += kmh(x); x += s
        }
        return out
    }

    fun same(a: Double, b: Double) = abs(a - b) < 0.005
}

/** Capacité actuelle déclarée par l'utilisateur, pour orienter vers le bon test. */
@Serializable
enum class Capacity(val label: String) {
    WALK_ONLY("Je marche seulement"),
    WALK_RUN("J'alterne marche et trot"),
    RUN_10("Je cours 10 min sans m'arrêter, à l'aise"),
}

/** Les références utilisées pour construire une séance : gardées avec la séance faite. */
@Serializable
data class PaceBasis(
    val walkEasy: PaceRef? = null,
    val walkActive: PaceRef? = null,
    val runEasy: PaceRef? = null,
    /** Ancienne vitesse unique, utilisée pour les allures sans référence (repli explicite). */
    val legacyVr: Double = 0.0,
) {
    fun lines(): List<String> {
        val fallback = "pas de référence : repli sur l'ancien calcul depuis la vitesse de référence (${Fmt.kmh(legacyVr)} km/h)"
        fun l(kind: RefKind, r: PaceRef?) = "${kind.label} : " + (r?.describe() ?: fallback)
        return listOf(
            l(RefKind.WALK_EASY, walkEasy), l(RefKind.WALK_ACTIVE, walkActive), l(RefKind.RUN_EASY, runEasy),
            "Côte, allure soutenue et rapide : réglages du programme (côte depuis la vitesse de référence, soutenue et rapide depuis le trot).",
        )
    }

    companion object {
        fun of(p: Profile) = PaceBasis(p.paceRefs.walkEasy, p.paceRefs.walkActive, p.paceRefs.runEasy, p.vr)
    }
}
