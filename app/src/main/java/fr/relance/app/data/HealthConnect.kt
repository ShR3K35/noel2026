package fr.relance.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import fr.relance.app.core.CompletedSession
import fr.relance.app.core.Physio
import fr.relance.app.core.Sample
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

enum class HcState { UNAVAILABLE, INSTALL_REQUIRED, NO_PERMISSION, READY }

/**
 * Envoi des séances vers Santé Connect (Health Connect), d'où les lisent Google Health et les autres
 * applications de santé du téléphone. Relance écrit seulement : elle ne lit aucune donnée.
 */
class HealthConnect(private val ctx: Context) {
    companion object {
        const val PROVIDER = "com.google.android.apps.healthdata"

        val TYPES: List<KClass<out Record>> = listOf(
            ExerciseSessionRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class,
            HeartRateRecord::class, SpeedRecord::class,
        )
        val PERMISSIONS: Set<String> = TYPES.map { HealthPermission.getWritePermission(it) }.toSet()
    }

    fun sdkStatus(): Int = try {
        HealthConnectClient.getSdkStatus(ctx, PROVIDER)
    } catch (e: Exception) {
        HealthConnectClient.SDK_UNAVAILABLE
    }

    suspend fun state(): HcState = when (sdkStatus()) {
        HealthConnectClient.SDK_AVAILABLE -> if (granted().isEmpty()) HcState.NO_PERMISSION else HcState.READY
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcState.INSTALL_REQUIRED
        else -> HcState.UNAVAILABLE
    }

    suspend fun granted(): Set<String> = try {
        HealthConnectClient.getOrCreate(ctx, PROVIDER).permissionController.getGrantedPermissions().intersect(PERMISSIONS)
    } catch (e: Exception) {
        emptySet()
    }

    /** Écrit une séance ; seuls les types autorisés par l'utilisateur sont envoyés. Renvoie le nombre d'enregistrements écrits. */
    suspend fun write(session: CompletedSession, samples: List<Sample>, weightKg: Double): Int {
        val allowed = granted()
        val records = HealthRecords.build(session, samples, weightKg, ZoneId.systemDefault())
            .filter { HealthPermission.getWritePermission(it::class) in allowed }
        if (records.isEmpty()) return 0
        // Même identifiant à chaque envoi : renvoyer une séance la met à jour au lieu de la dupliquer.
        HealthConnectClient.getOrCreate(ctx, PROVIDER).insertRecords(records)
        return records.size
    }

    /** Page Play Store pour installer ou mettre à jour Santé Connect (Android 13 et avant). */
    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        setPackage("com.android.vending")
        data = Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding")
        putExtra("overlay", true)
        putExtra("callerId", ctx.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Traduction d'une séance Relance en enregistrements Santé Connect. Sans appel au téléphone : testable. */
object HealthRecords {
    private const val SPEED_EVERY_S = 5

    fun clientId(session: CompletedSession, kind: String) = "relance-${session.id}-$kind"

    fun build(session: CompletedSession, samples: List<Sample>, weightKg: Double, zone: ZoneId): List<Record> {
        val start = Instant.ofEpochMilli(session.startMillis)
        val end = start.plusSeconds(session.durationSec.toLong().coerceAtLeast(1))
        val so = zone.rules.getOffset(start)
        val eo = zone.rules.getOffset(end)
        val device = Device(type = Device.TYPE_PHONE)
        fun meta(kind: String) = Metadata.activelyRecorded(device, clientId(session, kind), 1L)
        fun at(t: Int): Instant = start.plusSeconds(t.toLong().coerceIn(0, session.durationSec.toLong()))

        val out = ArrayList<Record>()
        val running = session.runBlocksPlanned > 0
        out += ExerciseSessionRecord(
            startTime = start, startZoneOffset = so, endTime = end, endZoneOffset = eo,
            metadata = meta("session"),
            exerciseType = if (running) ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL else ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            title = "Relance · ${session.title}",
            notes = listOfNotNull(
                session.detail,
                session.programLabel.takeIf { it.isNotBlank() }?.let { "$it, semaine ${session.week + 1}" },
                "Effort ressenti ${session.rpe}/10".takeIf { session.rpe > 0 },
            ).joinToString(" · "),
        )
        if (session.distanceM >= 1) {
            out += DistanceRecord(start, so, end, eo, Length.meters(session.distanceM), meta("distance"))
        }
        // Calories actives : dépense estimée moins la dépense de repos (1 MET), pour ne pas compter deux fois le métabolisme de base.
        val active = samples.sumOf {
            (Physio.kcalPerMin(it.speed.toDouble(), it.incline.toDouble(), weightKg) - 3.5 * weightKg / 200).coerceAtLeast(0.0) / 60
        }
        if (active >= 1) {
            out += ActiveCaloriesBurnedRecord(start, so, end, eo, Energy.kilocalories(active), meta("calories"))
        }
        val hr = samples.filter { it.hr in 1..300 }.map { HeartRateRecord.Sample(at(it.t), it.hr.toLong()) }
        if (hr.isNotEmpty()) out += HeartRateRecord(start, so, end, eo, hr, meta("heart-rate"))
        val speed = samples.filter { it.t % SPEED_EVERY_S == 0 && it.speed >= 0f }
            .map { SpeedRecord.Sample(at(it.t), Velocity.metersPerSecond(it.speed / 3.6)) }
        if (speed.isNotEmpty()) out += SpeedRecord(start, so, end, eo, speed, meta("speed"))
        return out
    }
}
