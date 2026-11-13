package fr.relance.app.core

import kotlinx.serialization.Serializable

@Serializable
enum class Level(val label: String) {
    N1("Redémarrage"), N2("Marcheur"), N3("Marche / course"), N4("Reprise course")
}

@Serializable
enum class LastPractice(val label: String) {
    NEVER("Jamais vraiment"), OVER_2Y("Il y a plus de 2 ans"),
    UNDER_2Y("Il y a moins de 2 ans"), UNDER_6M("Il y a moins de 6 mois")
}

/** Programmes intégrés de la première version : ne sert plus qu'à migrer un programme en cours et dans les tests. */
@Serializable
enum class ProgramType(val id: String) {
    RUN_10("run-10"), RUN_30("run-30"), RUN_45("run-45"), RUN_60("run-60"),
    RUN_5K("run-5k"), RUN_10K("run-10k"), PERF("perf"), HEALTH("health"),
}

@Serializable
enum class ProgramStatus(val label: String) {
    ACTIVE("En cours"), SUCCESS("Réussi"), FINISHED("Terminé sans validation"), STOPPED("Interrompu")
}

@Serializable
enum class BlockKind(val label: String) {
    WARMUP("Échauffement"), WALK("Marche"), HILL("Marche en côte"), RUN("Trot"),
    FAST("Allure soutenue"), TEST("Test"), COOLDOWN("Retour au calme")
}

@Serializable
data class Block(
    val kind: BlockKind,
    val seconds: Int,
    val speedKmh: Double,
    val inclinePct: Double = 0.0,
)

@Serializable
enum class SessionKind { WALK, HILL, INTERVALS, CONTINUOUS, RECOVERY, TEST, PERF_INTERVALS, PERF_TEMPO }

@Serializable
data class PlannedSession(
    val day: Long,
    val kind: SessionKind,
    val title: String,
    val detail: String,
    val blocks: List<Block>,
    val isKey: Boolean,
    val week: Int,
    val deload: Boolean,
) {
    val minutes: Int get() = (blocks.sumOf { it.seconds } + 59) / 60
    val inclinePct: Double get() = blocks.maxOfOrNull { it.inclinePct } ?: 0.0
}

@Serializable
data class Profile(
    val age: Int = 40,
    val weightKg: Double = 75.0,
    val heightCm: Int = 170,
    val lastPractice: LastPractice = LastPractice.OVER_2Y,
    val healthFlag: Boolean = false,
    val jointIssue: Boolean = false,
    val hrMedication: Boolean = false,
    /** Jours possibles, 1 = lundi … 7 = dimanche. */
    val days: Set<Int> = setOf(1, 3, 6),
    val minutesPerSession: Int = 30,
    /** Vitesse de référence : marche la plus vive tenue en pouvant parler (km/h). */
    val vr: Double = 5.0,
    val testRpe: Int = 5,
    val restHr: Int = 65,
    val maxHrObserved: Int = 0,
    val longestRunMin: Int = 0,
    val level: Level = Level.N2,
    val treadmillMaxKmh: Double = 14.0,
    val inclinePct: Double = 3.0,
    val hrAddress: String = "",
    val hrName: String = "",
    val treadmillAddress: String = "",
    val treadmillName: String = "",
    val runnerAnimation: Boolean = true,
    /** Envoi automatique des séances vers Santé Connect. */
    val healthConnect: Boolean = false,
    val voice: Boolean = true,
)

@Serializable
data class Program(
    val id: String,
    val templateId: String = "",
    val name: String = "",
    val validation: String = "",
    val startDay: Long,
    /** Paliers adaptés au profil au démarrage du programme. */
    val steps: List<ResolvedStep> = emptyList(),
    /** Le programme garde sa version du catalogue jusqu'à la fin. */
    val snapshot: ProgramSnapshot? = null,
    /** Ancien format, avant le catalogue : sert seulement à migrer un programme en cours. */
    val type: ProgramType? = null,
    val stepIndex: Int = 0,
    val week: Int = 0,
    val sessionInWeek: Int = 0,
    val speedFactor: Double = 1.0,
    val repeatWeek: Boolean = false,
    val skipStep: Boolean = false,
    val fallbackNext: Boolean = false,
    val flatWalkNext: Boolean = false,
    val testNext: Boolean = false,
    val restUntilDay: Long = 0,
    val recentRpe: List<Int> = emptyList(),
    val painCount: Int = 0,
    val sessionsDone: Int = 0,
    val sessionsPlanned: Int = 0,
    val lastSessionDay: Long = 0,
    val lastKeyComplete: Boolean = false,
    val vrStart: Double = 0.0,
    val vrEnd: Double = 0.0,
    val status: ProgramStatus = ProgramStatus.ACTIVE,
    val endDay: Long = 0,
    val paused: Boolean = false,
    val weeksPlanned: Int = 0,
) {
    val finished: Boolean get() = stepIndex >= steps.size
}

@Serializable
enum class Pain(val label: String) { NONE("Aucune"), DISCOMFORT("Une gêne"), PAIN("Une douleur") }

@Serializable
data class Feedback(
    val rpe: Int,
    val pain: Pain = Pain.NONE,
    val completed: Boolean = true,
    val note: String = "",
    /** Vitesse tenue pendant le test, saisie à la main quand le tapis n'est pas connecté. */
    val testSpeed: Double = 0.0,
)

@Serializable
data class Sample(val t: Int, val speed: Float, val hr: Int = 0, val incline: Float = 0f)

@Serializable
data class CompletedSession(
    val id: String,
    val startMillis: Long,
    val day: Long,
    val programId: String = "",
    val programLabel: String = "",
    val week: Int = 0,
    val kind: SessionKind,
    val title: String,
    val detail: String,
    val plannedSeconds: Int,
    val durationSec: Int,
    val distanceM: Double,
    val avgSpeed: Double,
    val maxSpeed: Double,
    val avgHr: Int = 0,
    val maxHr: Int = 0,
    val zoneSeconds: List<Int> = listOf(0, 0, 0, 0, 0),
    val recovery1Min: Int = 0,
    val calories: Int = 0,
    val elevationM: Double = 0.0,
    val runBlocksPlanned: Int = 0,
    val runBlocksDone: Int = 0,
    val longestRunMin: Int = 0,
    val speedMeasured: Boolean = false,
    val rpe: Int = 0,
    val pain: Pain = Pain.NONE,
    val completed: Boolean = true,
    val note: String = "",
    val coachMessage: String = "",
    val healthConnectSynced: Boolean = false,
    /** Séance saisie à la main après coup : rien n'a été mesuré. */
    val manual: Boolean = false,
    /** État du programme juste avant cette séance : sert à l'annuler pour la refaire. */
    val programBefore: Program? = null,
    val profileBefore: CoachSnapshot? = null,
)

/** Ce qu'une séance peut changer dans le profil, pour pouvoir revenir en arrière. */
@Serializable
data class CoachSnapshot(val vr: Double, val testRpe: Int, val longestRunMin: Int, val maxHrObserved: Int) {
    companion object {
        fun of(p: Profile) = CoachSnapshot(p.vr, p.testRpe, p.longestRunMin, p.maxHrObserved)
    }

    fun restore(p: Profile) = p.copy(vr = vr, testRpe = testRpe, longestRunMin = longestRunMin, maxHrObserved = maxHrObserved)
}

@Serializable
data class AppState(
    val profile: Profile? = null,
    val program: Program? = null,
    val archived: List<Program> = emptyList(),
    val sessions: List<CompletedSession> = emptyList(),
)
