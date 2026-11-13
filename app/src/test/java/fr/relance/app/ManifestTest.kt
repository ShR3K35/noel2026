package fr.relance.app

import android.content.pm.PackageManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Permissions indispensables, lues dans le manifeste fusionné de l'app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestTest {
    @Test
    fun theAppMayReachTheProgramServer() {
        val ctx = RuntimeEnvironment.getApplication()
        val requested = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
        // Sans INTERNET, Android refuse toute requête : la mise à jour des programmes échouerait toujours.
        assertTrue(requested.toString(), "android.permission.INTERNET" in requested)
        assertTrue("android.permission.ACCESS_NETWORK_STATE" in requested)
        assertTrue("android.permission.BLUETOOTH_CONNECT" in requested)
        // Micro des tests de calibration (voix analysée sur l'appareil).
        assertTrue("android.permission.RECORD_AUDIO" in requested)
    }
}
