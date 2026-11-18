package fr.relance.app

import fr.relance.app.core.AutoSpeed
import fr.relance.app.core.FtmsControl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Réglage automatique de la vitesse par FTMS, avec le boîtier Relance simulé par ses réponses. */
class SpeedControlTest {
    private val request = byteArrayOf(0x00)
    private fun set(kmh: Double) = FtmsControl.setTargetSpeed(kmh)
    private fun ok(op: Int) = FtmsControl.Response(op, FtmsControl.RESULT_SUCCESS)
    private fun fail(op: Int, result: Int) = FtmsControl.Response(op, result)

    private fun assertFrames(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertArrayEquals(e, a) }
    }

    private fun connectedAndGranted(): AutoSpeed {
        val a = AutoSpeed()
        a.connected()
        a.target(5.0, 0)
        a.response(ok(FtmsControl.OP_REQUEST_CONTROL))
        a.response(ok(FtmsControl.OP_SET_TARGET_SPEED))
        return a
    }

    @Test
    fun frames() {
        // 6,50 km/h = 650 = 0x028A
        assertArrayEquals(byteArrayOf(0x02, 0x8A.toByte(), 0x02), FtmsControl.setTargetSpeed(6.5))
        assertArrayEquals(byteArrayOf(0x02, 0x78, 0x05), FtmsControl.setTargetSpeed(14.0))
        assertEquals(FtmsControl.Response(0x02, 0x04), FtmsControl.response(byteArrayOf(0x80.toByte(), 0x02, 0x04)))
        assertNull(FtmsControl.response(byteArrayOf(0x02, 0x04)))
        assertEquals(FtmsControl.Status.TargetSpeed(6.5), FtmsControl.status(byteArrayOf(0x05, 0x8A.toByte(), 0x02)))
        assertEquals(FtmsControl.Status.Stopped, FtmsControl.status(byteArrayOf(0x02, 0x01)))
        assertNull(FtmsControl.status(byteArrayOf(0x05, 0x01)))
    }

    @Test
    fun firstTargetAsksForControl() {
        val a = AutoSpeed()
        a.connected()
        assertFrames(listOf(request, set(5.0)), a.target(5.0, 0))
        assertEquals(5.0, a.desired!!, 0.001)
    }

    @Test
    fun nothingSentBeforeConnection() {
        val a = AutoSpeed()
        assertTrue(a.target(5.0, 0).isEmpty())
        // La consigne part dès la connexion.
        assertFrames(listOf(request, set(5.0)), a.connected())
    }

    @Test
    fun onceGrantedOnlyTheTargetIsSent() {
        val a = connectedAndGranted()
        assertFrames(listOf(set(8.5)), a.target(8.5, 1))
    }

    @Test
    fun sameBlockIsSentOnce() {
        val a = connectedAndGranted()
        assertTrue(a.target(5.0, 0).isEmpty())
        assertTrue(a.target(5.0, 0).isEmpty())
        // Bloc suivant à la même vitesse : renvoyée (la console a pu être changée entre-temps).
        assertFrames(listOf(set(5.0)), a.target(5.0, 1))
    }

    @Test
    fun targetIsRoundedToTenths() {
        val a = connectedAndGranted()
        assertFrames(listOf(set(6.3)), a.target(6.27, 1))
    }

    @Test
    fun waitsForBeltWhenStopped() {
        val a = connectedAndGranted()
        a.target(6.0, 1)
        a.response(fail(FtmsControl.OP_SET_TARGET_SPEED, FtmsControl.RESULT_OPERATION_FAILED))
        assertTrue(a.notice!!.contains("Démarre le tapis"))
        assertTrue(a.measured(0.0).isEmpty())
        assertFrames(listOf(set(6.0)), a.measured(1.0))
        // Une seule relance.
        assertTrue(a.measured(1.2).isEmpty())
        a.response(ok(FtmsControl.OP_SET_TARGET_SPEED))
        assertNull(a.notice)
    }

    @Test
    fun manualChangeIsRespectedUntilNextBlock() {
        val a = connectedAndGranted()
        a.target(6.0, 1)
        a.status(FtmsControl.Status.TargetSpeed(6.0))  // écho de la consigne acceptée : rien à faire
        assertEquals(6.0, a.desired!!, 0.001)
        a.status(FtmsControl.Status.TargetSpeed(7.5))  // changée sur la console
        assertNull(a.desired)
        assertTrue(a.notice!!.contains("console"))
        assertTrue(a.target(6.0, 1).isEmpty())
        assertTrue(a.measured(7.5).isEmpty())
        assertFrames(listOf(set(4.5)), a.target(4.5, 2))
        assertNull(a.notice)
    }

    @Test
    fun stoppedBeltGetsTargetAgainWhenRestarted() {
        val a = connectedAndGranted()
        a.target(6.0, 1)
        a.status(FtmsControl.Status.Stopped)
        assertFrames(listOf(set(6.0)), a.measured(1.0))
    }

    @Test
    fun pauseSendsNothingAndResumeSendsAgain() {
        val a = connectedAndGranted()
        a.target(6.0, 1)
        assertTrue(a.target(null, 1).isEmpty())
        assertNull(a.desired)
        assertFrames(listOf(set(6.0)), a.target(6.0, 1))
    }

    @Test
    fun reconnectionAsksForControlAgain() {
        val a = connectedAndGranted()
        a.target(7.0, 1)
        a.disconnected()
        assertTrue(a.target(7.5, 2).isEmpty())
        assertFrames(listOf(request, set(7.5)), a.connected())
    }

    @Test
    fun lostControlIsRequestedOnceMore() {
        val a = connectedAndGranted()
        a.target(7.0, 1)
        assertFrames(listOf(request, set(7.0)), a.response(fail(FtmsControl.OP_SET_TARGET_SPEED, FtmsControl.RESULT_CONTROL_NOT_PERMITTED)))
        assertTrue(a.response(fail(FtmsControl.OP_SET_TARGET_SPEED, FtmsControl.RESULT_CONTROL_NOT_PERMITTED)).isEmpty())
        assertTrue(a.notice!!.contains("refuse"))
    }

    @Test
    fun unsupportedTreadmillIsLeftAlone() {
        val a = AutoSpeed()
        a.connected()
        a.target(5.0, 0)
        a.response(fail(FtmsControl.OP_REQUEST_CONTROL, FtmsControl.RESULT_NOT_SUPPORTED))
        a.response(fail(FtmsControl.OP_SET_TARGET_SPEED, FtmsControl.RESULT_NOT_SUPPORTED))
        assertFalse(a.supported)
        assertTrue(a.target(6.0, 1).isEmpty())
        assertTrue(a.notice!!.contains("ne se règle pas"))
    }

    @Test
    fun outOfRangeTargetIsDropped() {
        val a = connectedAndGranted()
        a.target(16.0, 1)
        a.response(fail(FtmsControl.OP_SET_TARGET_SPEED, FtmsControl.RESULT_INVALID_PARAMETER))
        assertNull(a.desired)
        assertTrue(a.measured(5.0).isEmpty())
    }
}
