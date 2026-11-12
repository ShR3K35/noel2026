package fr.relance.app.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Étalonnage du boîtier : on compare la vitesse affichée par la console à celle mesurée par le boîtier,
 * et on corrige la distance parcourue par la bande à chaque tour de rouleau.
 */
object Calibration {
    /** Bornes du boîtier : rouleau de 20 à 120 mm de diamètre (voir firmware/include/speed.h). */
    const val MIN_UM = 60_000L
    const val MAX_UM = 380_000L

    /** Paliers proposés : des vitesses de marche suffisent, le comptage est proportionnel. */
    val STEPS = listOf(3.0, 4.5, 6.0)

    data class Point(val consoleKmh: Double, val measuredKmh: Double) {
        val ratio: Double get() = if (measuredKmh > 0) consoleKmh / measuredKmh else 0.0
        val plausible: Boolean get() = ratio in 0.5..2.0
    }

    /** Nouvelle distance par tour, ou null si aucun point n'est exploitable ou si le résultat sort des bornes. */
    fun newDistance(oldUm: Long, points: List<Point>): Long? {
        val ok = points.filter { it.plausible }
        if (ok.isEmpty()) return null
        val um = (oldUm * ok.map { it.ratio }.average()).roundToLong()
        return if (um in MIN_UM..MAX_UM) um else null
    }

    /** Écart moyen, en %, entre la console et le boîtier avant correction. */
    fun errorPercent(points: List<Point>): Double {
        val ok = points.filter { it.plausible }
        if (ok.isEmpty()) return 0.0
        return ok.map { (it.measuredKmh - it.consoleKmh) / it.consoleKmh * 100 }.average()
    }

    /** Une mesure est stable quand les dernières secondes restent dans ±0,1 km/h. */
    fun isStable(recent: List<Double>, seconds: Int = 5): Boolean {
        if (recent.size < seconds) return false
        val last = recent.takeLast(seconds)
        return last.all { it > 0.5 } && last.max() - last.min() <= 0.2 && abs(last.last() - last.average()) <= 0.1
    }

    fun encode(um: Long): ByteArray = ByteArray(4) { i -> ((um shr (8 * i)) and 0xFF).toByte() }

    fun decode(b: ByteArray): Long? {
        if (b.size != 4) return null
        var v = 0L
        for (i in 0 until 4) v = v or ((b[i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    /** Diamètre de rouleau équivalent, en mm, pour l'affichage. */
    fun diameterMm(um: Long): Double = um / 1000.0 / Math.PI
}
