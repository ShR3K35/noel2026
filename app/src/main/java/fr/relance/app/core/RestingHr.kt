package fr.relance.app.core

import kotlin.math.roundToInt

/**
 * Mesure de la fréquence cardiaque de repos : 2 minutes assis, une valeur par seconde.
 * Les 30 premières secondes servent à se poser et sont écartées ; on retient la médiane du reste,
 * qui ignore les quelques battements parasites d'une ceinture mal humidifiée.
 */
object RestingHr {
    const val DURATION_S = 120
    const val SETTLE_S = 30
    /** Il faut au moins 60 mesures valides après la phase d'installation. */
    const val MIN_VALID = 60
    val PLAUSIBLE = 35..110

    sealed interface Result {
        data class Ok(val bpm: Int) : Result
        /** Trop de secondes sans signal : ceinture mal placée ou déconnectée. */
        data object NotEnoughData : Result
        /** Valeur hors de ce qu'on attend au repos : la personne n'était sans doute pas au calme. */
        data class Implausible(val bpm: Int) : Result
    }

    /** `samples` : une valeur par seconde, 0 quand la ceinture n'a rien envoyé. */
    fun compute(samples: List<Int>): Result {
        val valid = samples.drop(SETTLE_S).filter { it in 25..240 }
        if (valid.size < MIN_VALID) return Result.NotEnoughData
        val sorted = valid.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
        val bpm = median.roundToInt()
        return if (bpm in PLAUSIBLE) Result.Ok(bpm) else Result.Implausible(bpm)
    }
}
