package fr.relance.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import fr.relance.app.core.BleParsers
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

enum class Link(val label: String) { OFF("Non connecté"), CONNECTING("Recherche…"), CONNECTED("Connecté") }

data class FoundDevice(val address: String, val name: String, val isHeartRate: Boolean, val isTreadmill: Boolean)

/**
 * Capteurs Bluetooth Low Energy, en lecture seule : ceinture cardio (profil Heart Rate) et
 * tapis ou boîtier ESP32 parlant FTMS (Treadmill Data). Rien n'est jamais envoyé au tapis.
 */
@SuppressLint("MissingPermission")
class BleManager(private val ctx: Context) {
    companion object {
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val FTMS_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
        val TREADMILL_DATA: UUID = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Service propre au boîtier Relance : distance parcourue par la bande pour un tour de rouleau. */
        val CALIB_SERVICE: UUID = UUID.fromString("6f1e0001-5c3a-4b8e-9d2f-7a1c0e5b9a01")
        val CALIB_CHAR: UUID = UUID.fromString("6f1e0002-5c3a-4b8e-9d2f-7a1c0e5b9a01")
    }

    val hr = MutableStateFlow<Int?>(null)
    val speed = MutableStateFlow<Double?>(null)
    val incline = MutableStateFlow<Double?>(null)
    val hrLink = MutableStateFlow(Link.OFF)
    val treadmillLink = MutableStateFlow(Link.OFF)
    val found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val scanning = MutableStateFlow(false)
    /** Distance par tour de rouleau du boîtier, en micromètres ; null si le tapis n'a pas ce réglage. */
    val calibrationUm = MutableStateFlow<Long?>(null)
    /** Dernier envoi d'étalonnage : null tant que rien n'est envoyé, puis true ou false. */
    val calibrationSaved = MutableStateFlow<Boolean?>(null)

    private var hrGatt: BluetoothGatt? = null
    private var treadmillGatt: BluetoothGatt? = null

    private val adapter: BluetoothAdapter?
        get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(): Boolean =
        requiredPermissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun available(): Boolean = adapter?.isEnabled == true && hasPermissions()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val device = FoundDevice(
                result.device.address,
                result.scanRecord?.deviceName ?: result.device.name ?: "Appareil sans nom",
                HR_SERVICE in uuids, FTMS_SERVICE in uuids,
            )
            if (!device.isHeartRate && !device.isTreadmill) return
            found.value = found.value.filter { it.address != device.address } + device
        }

        override fun onScanFailed(errorCode: Int) {
            scanning.value = false
        }
    }

    fun startScan() {
        if (!available() || scanning.value) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        found.value = emptyList()
        val filters = listOf(HR_SERVICE, FTMS_SERVICE).map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        try {
            scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            scanning.value = true
        } catch (e: Exception) {
            scanning.value = false
        }
    }

    fun stopScan() {
        if (!scanning.value) return
        scanning.value = false
        try {
            if (hasPermissions()) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            // Le Bluetooth a pu être coupé entre-temps : rien à arrêter.
        }
    }

    fun connectHeartRate(address: String) {
        hrGatt = connect(address, hrGatt, true)
    }

    fun connectTreadmill(address: String) {
        treadmillGatt = connect(address, treadmillGatt, false)
    }

    fun disconnectHeartRate() {
        close(hrGatt); hrGatt = null; hr.value = null; hrLink.value = Link.OFF
    }

    fun disconnectTreadmill() {
        close(treadmillGatt); treadmillGatt = null; speed.value = null; incline.value = null; treadmillLink.value = Link.OFF
        calibrationUm.value = null
    }

    fun shutdown() {
        stopScan(); disconnectHeartRate(); disconnectTreadmill()
    }

    /** Envoie une nouvelle distance par tour au boîtier, qui la garde en mémoire. */
    fun writeCalibration(um: Long): Boolean {
        val gatt = treadmillGatt ?: return false
        if (!hasPermissions()) return false
        val ch = gatt.getService(CALIB_SERVICE)?.getCharacteristic(CALIB_CHAR) ?: return false
        val bytes = fr.relance.app.core.Calibration.encode(um)
        calibrationSaved.value = null
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ch)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun close(gatt: BluetoothGatt?) {
        try {
            if (hasPermissions()) gatt?.close()
        } catch (e: Exception) {
            // Déjà fermé.
        }
    }

    private fun connect(address: String, previous: BluetoothGatt?, heartRate: Boolean): BluetoothGatt? {
        if (address.isBlank() || !available()) return previous
        close(previous)
        val device: BluetoothDevice = try {
            adapter?.getRemoteDevice(address) ?: return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        (if (heartRate) hrLink else treadmillLink).value = Link.CONNECTING
        // autoConnect : l'appareil est rattrapé dès qu'il s'allume, y compris après une coupure.
        return device.connectGatt(ctx, true, Callback(heartRate), BluetoothDevice.TRANSPORT_LE)
    }

    private inner class Callback(private val heartRate: Boolean) : BluetoothGattCallback() {
        private val link get() = if (heartRate) hrLink else treadmillLink

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (heartRate) hr.value = null else {
                    speed.value = null; incline.value = null
                }
                link.value = Link.CONNECTING
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(if (heartRate) HR_SERVICE else FTMS_SERVICE) ?: return
            val ch = service.getCharacteristic(if (heartRate) HR_MEASUREMENT else TREADMILL_DATA) ?: return
            gatt.setCharacteristicNotification(ch, true)
            val descriptor = ch.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            link.value = Link.CONNECTED
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Une opération GATT à la fois : la lecture de l'étalonnage attend la fin de l'abonnement.
            if (heartRate) return
            val ch = gatt.getService(CALIB_SERVICE)?.getCharacteristic(CALIB_CHAR) ?: return
            gatt.readCharacteristic(ch)
        }

        @Deprecated("Appelé avant Android 13")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (status == BluetoothGatt.GATT_SUCCESS) handleCalibration(characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleCalibration(characteristic.uuid, value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != CALIB_CHAR) return
            calibrationSaved.value = status == BluetoothGatt.GATT_SUCCESS
            if (status == BluetoothGatt.GATT_SUCCESS) gatt.readCharacteristic(characteristic)
        }

        private fun handleCalibration(uuid: UUID, value: ByteArray) {
            if (uuid == CALIB_CHAR) fr.relance.app.core.Calibration.decode(value)?.let { calibrationUm.value = it }
        }

        @Deprecated("Appelé avant Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handle(characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handle(characteristic.uuid, value)
        }

        private fun handle(uuid: UUID, value: ByteArray) {
            if (uuid == HR_MEASUREMENT) {
                BleParsers.heartRate(value)?.let { if (it in 25..240) hr.value = it }
            } else if (uuid == TREADMILL_DATA) {
                val t = BleParsers.treadmill(value) ?: return
                t.speedKmh?.let { speed.value = it }
                t.inclinePct?.let { incline.value = it }
            }
        }
    }
}
