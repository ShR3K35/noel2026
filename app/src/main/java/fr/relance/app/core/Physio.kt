package fr.relance.app.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Vitesses de travail déduites de la vitesse de référence (VR). */
data class Speeds(
    val warm: Double, val recovery: Double, val walk: Double, val hill: Double,
    val run: Double, val tempo: Double, val fast: Double,
)

enum class RunnerState(val label: String) {
    STOPPED("À l'arrêt · il souffle"),
    WALK("Marche · il chante ou discute"),
    JOG("Trot · il peut encore parler"),
    FAST("Course rapide · il transpire"),
}

object Physio {
    fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0

    fun bmi(p: Profile): Double {
        val m = p.heightCm / 100.0
        return if (m <= 0) 0.0 else p.weightKg / (m * m)
    }

    /** Niveau de départ : le plus prudent des signaux l'emporte. */
    fun assessLevel(p: Profile): Level = when {
        p.healthFlag || bmi(p) >= 35 || p.vr < 4.5 -> Level.N1
        p.lastPractice == LastPractice.UNDER_6M && p.vr >= 6.0 && !p.jointIssue -> Level.N4
        p.vr >= 5.5 && p.testRpe <= 6 && !p.jointIssue && p.lastPractice != LastPractice.NEVER -> Level.N3
        else -> Level.N2
    }

    fun speedsFor(p: Profile, factor: Double = 1.0): Speeds {
        val cap = p.treadmillMaxKmh
        fun c(v: Double) = round1(min(v, cap))
        val run = min(max(p.vr * 1.2, 6.0), 10.0) * factor
        return Speeds(
            warm = c(p.vr * 0.8),
            recovery = c(p.vr * 0.75),
            walk = c(p.vr * 0.95 * factor),
            hill = c(p.vr * 0.85 * factor),
            run = c(run),
            tempo = c(run * 1.12),
            fast = c(run * 1.25),
        )
    }

    fun maxHr(p: Profile): Int = max(p.maxHrObserved, (208 - 0.7 * p.age).roundToInt())

    /** Bornes basses des zones 1 à 5, par la réserve cardiaque (Karvonen). */
    fun zoneFloors(p: Profile): List<Int> {
        val mx = maxHr(p)
        val reserve = max(mx - p.restHr, 1)
        return listOf(0.5, 0.6, 0.7, 0.8, 0.9).map { (p.restHr + reserve * it).roundToInt() }
    }

    /** Zone 1 à 5, ou 0 si pas de mesure. Sous la zone 1, on reste en zone 1. */
    fun zoneOf(hr: Int, p: Profile): Int {
        if (hr <= 0) return 0
        val floors = zoneFloors(p)
        var z = 1
        for (i in floors.indices) if (hr >= floors[i]) z = i + 1
        return z
    }

    /** Dépense estimée (équations métaboliques ACSM), en kcal par minute. */
    fun kcalPerMin(speedKmh: Double, inclinePct: Double, weightKg: Double): Double {
        if (speedKmh < 0.5) return 0.0
        val s = speedKmh * 1000 / 60
        val g = inclinePct / 100
        val vo2 = if (speedKmh > 7.0) 0.2 * s + 0.9 * s * g + 3.5 else 0.1 * s + 1.8 * s * g + 3.5
        return vo2 * weightKg / 200
    }

    /** Ce que fait le coureur animé : seuils calés sur les allures de l'utilisateur. */
    fun runnerState(speedKmh: Double, p: Profile): RunnerState {
        val s = speedsFor(p)
        val walkMax = (p.vr + s.run) / 2
        val fastMin = s.run * 1.1
        return when {
            speedKmh < 0.5 -> RunnerState.STOPPED
            speedKmh <= walkMax -> RunnerState.WALK
            speedKmh < fastMin -> RunnerState.JOG
            else -> RunnerState.FAST
        }
    }

    /** Durée d'un cycle de foulée (deux pas), en secondes. */
    fun strideSeconds(speedKmh: Double): Double = min(1.4, max(0.55, 1.5 - 0.075 * speedKmh))
}

object Fmt {
    fun kmh(v: Double): String = String.format(java.util.Locale.FRANCE, "%.1f", v)
    fun km(m: Double): String = String.format(java.util.Locale.FRANCE, "%.2f", m / 1000)
    fun clock(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
    fun pace(avgKmh: Double): String {
        if (avgKmh < 0.5) return "—"
        val s = (3600 / avgKmh).roundToInt()
        return "%d'%02d\"".format(s / 60, s % 60)
    }
    fun duration(sec: Int): String {
        val m = sec / 60
        return if (m >= 60) "%d h %02d".format(m / 60, m % 60) else "$m min"
    }
}
