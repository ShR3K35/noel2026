package fr.relance.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import fr.relance.app.core.Coach
import fr.relance.app.core.Level
import fr.relance.app.core.Profile
import fr.relance.app.core.ProgramType
import fr.relance.app.core.SessionEngine
import fr.relance.app.data.HcState
import fr.relance.app.data.HealthRecords
import fr.relance.app.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class HealthConnectTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val profile = Profile(vr = 5.8, level = Level.N3, weightKg = 80.0)
    private val today = LocalDate.of(2026, 11, 12).toEpochDay()

    private fun runSession(hr: Int?): Pair<fr.relance.app.core.CompletedSession, List<fr.relance.app.core.Sample>> {
        val p = Coach.newProgram(ProgramType.RUN_30, profile, today)
        val e = SessionEngine(Coach.sessionFor(p, profile, today), profile)
        while (!e.finished) e.tick(null, hr, null)
        return e.summary("s1", 1_800_000_000_000, today, p).copy(rpe = 5) to e.samples.toList()
    }

    @Test
    fun aSessionBecomesHealthConnectRecords() {
        val (s, samples) = runSession(132)
        val records = HealthRecords.build(s, samples, 80.0, ZoneId.of("Europe/Paris"))
        assertEquals(5, records.size)
        val ex = records.filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, ex.exerciseType)
        assertEquals(s.durationSec.toLong(), ex.endTime.epochSecond - ex.startTime.epochSecond)
        assertEquals("+01:00", ex.startZoneOffset.toString())
        assertTrue(ex.title!!.startsWith("Relance"))
        assertEquals(s.distanceM, records.filterIsInstance<DistanceRecord>().single().distance.inMeters, 0.01)
        val active = records.filterIsInstance<ActiveCaloriesBurnedRecord>().single().energy.inKilocalories
        assertTrue("actives $active < totales ${s.calories}", active > 0 && active < s.calories)
        assertEquals(samples.size, records.filterIsInstance<HeartRateRecord>().single().samples.size)
        val speed = records.filterIsInstance<SpeedRecord>().single().samples
        assertEquals(samples.size / 5, speed.size)
        assertEquals(samples[4].speed / 3.6, speed.first().speed.inMetersPerSecond, 0.001)
        // Identifiants stables et distincts : renvoyer la séance met à jour au lieu de dupliquer.
        val ids = records.map { it.metadata.clientRecordId }
        assertEquals(ids.toSet().size, ids.size)
        assertEquals(ids, HealthRecords.build(s, samples, 80.0, ZoneId.of("Europe/Paris")).map { it.metadata.clientRecordId })
    }

    @Test
    fun walkingWithoutStrapHasNoHeartRate() {
        val (s, samples) = runSession(null)
        val walk = s.copy(runBlocksPlanned = 0)
        val records = HealthRecords.build(walk, samples, 80.0, ZoneId.of("Europe/Paris"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, records.filterIsInstance<ExerciseSessionRecord>().single().exerciseType)
        assertTrue(records.none { it is HeartRateRecord })
    }

    @Test
    fun profileShowsHealthConnectCard() {
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        rule.runOnIdle {
            vm.completeOnboarding(profile, ProgramType.RUN_30, false)
            vm.tab = 4
        }
        rule.waitForIdle()
        // Sans Santé Connect installé (cas de Robolectric), l'app propose de l'installer ou dit qu'il manque.
        rule.onNodeWithText("Santé Connect", substring = true, useUnmergedTree = true).assertExists()
        rule.runOnIdle { vm.hcState = HcState.NO_PERMISSION }
        rule.onNodeWithText("Connecter Santé Connect").performScrollTo()
        rule.onRoot().captureRoboImage("build/shots/50-sante-connect-connecter.png")
        rule.runOnIdle { vm.hcState = HcState.READY }
        rule.onNodeWithText("Envoyer l'historique").performScrollTo()
        rule.onRoot().captureRoboImage("build/shots/51-sante-connect-pret.png")
    }
}
