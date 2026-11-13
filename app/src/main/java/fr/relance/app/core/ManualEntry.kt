package fr.relance.app.core

import kotlin.math.roundToInt

/**
 * Séance faite sans le téléphone, saisie après coup. Rien n'a été mesuré : distance et calories sont
 * estimées d'après la séance prévue, ramenée à la durée réellement faite, sauf si l'utilisateur
 * connaît la distance (affichée par la console).
 */
object ManualEntry {
    /** Heure de début proposée : matin, midi ou soir. */
    val MOMENTS = listOf("Matin" to 8 * 60, "Midi" to 12 * 60 + 30, "Soir" to 18 * 60 + 30)

    /** Distance qu'aurait donnée la séance prévue sur cette durée, en mètres. */
    fun estimatedDistance(planned: PlannedSession, durationSec: Int): Double {
        val plannedSec = planned.blocks.sumOf { it.seconds }.coerceAtLeast(1)
        val full = planned.blocks.sumOf { it.seconds * it.speedKmh / 3.6 }
        return full * durationSec / plannedSec
    }

    fun summary(
        planned: PlannedSession, profile: Profile, program: Program?, id: String, day: Long, startMillis: Long,
        durationSec: Int, distanceM: Double?, completed: Boolean,
    ): CompletedSession {
        val plannedSec = planned.blocks.sumOf { it.seconds }.coerceAtLeast(1)
        val scale = durationSec.toDouble() / plannedSec
        var kcal = 0.0
        var climb = 0.0
        for (b in planned.blocks) {
            val sec = b.seconds * scale
            kcal += Physio.kcalPerMin(b.speedKmh, b.inclinePct, profile.weightKg) * sec / 60
            climb += b.speedKmh / 3.6 * sec * b.inclinePct / 100
        }
        val distance = distanceM ?: estimatedDistance(planned, durationSec)
        val runBlocks = planned.blocks.filter { it.kind == BlockKind.RUN || it.kind == BlockKind.FAST }
        return CompletedSession(
            id = id, startMillis = startMillis, day = day,
            programId = program?.id ?: "", programLabel = program?.name ?: "", week = planned.week,
            kind = planned.kind, title = planned.title, detail = planned.detail,
            plannedSeconds = plannedSec, durationSec = durationSec, distanceM = distance,
            avgSpeed = if (durationSec > 0) distance / durationSec * 3.6 else 0.0,
            maxSpeed = planned.blocks.maxOfOrNull { it.speedKmh } ?: 0.0,
            calories = kcal.roundToInt(), elevationM = climb,
            runBlocksPlanned = runBlocks.size,
            runBlocksDone = if (completed) runBlocks.size else 0,
            // Sans mesure, on ne crédite la plus longue course continue que si la séance a été faite en entier.
            longestRunMin = if (completed) runBlocks.maxOfOrNull { it.seconds / 60 } ?: 0 else 0,
            speedMeasured = false, manual = true,
        )
    }
}
