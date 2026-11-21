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
import fr.relance.app.core.AutoSpeed
import fr.relance.app.core.BleParsers
import fr.relance.app.core.FtmsControl
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

enum class Link(val label: String) { OFF("Non connecté"), CONNECTING("Recherche…"), CONNECTED("Connecté") }

data class FoundDevice(val address: String, val name: String, val isHeartRate: Boolean, val isTreadmill: Boolean)

/**
 * Capteurs Bluetooth Low Energy : ceinture cardio (profil Heart Rate) et tapis ou boîtier ESP32 parlant
 * FTMS (Treadmill Data). Si le tapis l'accepte et que l'option est activée, la consigne de vitesse de chaque
 * bloc lui est envoyée (FTMS Control Point) ; le tapis n'est jamais démarré à distance.
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
        /** Appuis directs sur + ou − de la console (test des optocoupleurs) : un octet signé. */
        val NUDGE_CHAR: UUID = UUID.fromString("6f1e0003-5c3a-4b8e-9d2f-7a1c0e5b9a01")
        val CONTROL_POINT: UUID = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")
        val MACHINE_STATUS: UUID = UUID.fromString("00002ada-0000-1000-8000-00805f9b34fb")
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
    /** Le tapis connecté accepte une consigne de vitesse (FTMS Control Point présent). */
    val canControlSpeed = MutableStateFlow(false)
    /** Le boîtier accepte des appuis directs sur + et − (test des optocoupleurs). */
    val canNudge = MutableStateFlow(false)
    /** Message du réglage automatique de la vitesse, à afficher pendant la séance. */
    val speedNotice = MutableStateFlow<String?>(null)

    private var hrGatt: BluetoothGatt? = null
    private var treadmillGatt: BluetoothGatt? = null

    // Le tapis n'accepte qu'une opération GATT à la fois : abonnements, lecture et écritures passent par une file.
    // Le verrou protège aussi le réglage automatique, appelé depuis l'interface et depuis les rappels Bluetooth.
    private val lock = Any()
    private val ops = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var opBusy = false
    private val autoSpeed = AutoSpeed()

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
        controlLost()
    }

    fun shutdown() {
        stopScan(); disconnectHeartRate(); disconnectTreadmill()
    }

    /** Envoie une nouvelle distance par tour au boîtier, qui la garde en mémoire. */
    fun writeCalibration(um: Long): Boolean {
        val gatt = treadmillGatt ?: return false
        if (!hasPermissions()) return false
        if (gatt.getService(CALIB_SERVICE)?.getCharacteristic(CALIB_CHAR) == null) return false
        val bytes = fr.relance.app.core.Calibration.encode(um)
        calibrationSaved.value = null
        enqueue { g ->
            val ch = g.getService(CALIB_SERVICE)?.getCharacteristic(CALIB_CHAR)
            val started = ch != null && write(g, ch, bytes)
            if (!started) calibrationSaved.value = false
            started
        }
        return true
    }

    /** Un appui sur + (n > 0) ou − (n < 0) de la console, par les optocoupleurs du boîtier. */
    fun nudge(n: Int): Boolean {
        val gatt = treadmillGatt ?: return false
        if (n == 0 || !hasPermissions()) return false
        if (gatt.getService(CALIB_SERVICE)?.getCharacteristic(NUDGE_CHAR) == null) return false
        val bytes = byteArrayOf(n.coerceIn(-10, 10).toByte())
        enqueue { g ->
            val ch = g.getService(CALIB_SERVICE)?.getCharacteristic(NUDGE_CHAR)
            ch != null && write(g, ch, bytes)
        }
        return true
    }

    /**
     * Consigne de vitesse du bloc en cours, pour le réglage automatique ; null pour ne rien régler.
     * Peut être appelée à chaque seconde : seul un changement de bloc ou de consigne envoie quelque chose.
     */
    fun setAutoSpeed(kmh: Double?, block: Int) {
        val frames = synchronized(lock) { autoSpeed.target(kmh, block) }
        sendControl(frames)
    }

    private fun sendControl(frames: List<ByteArray>) {
        synchronized(lock) { speedNotice.value = autoSpeed.notice }
        frames.forEach { bytes ->
            enqueue { g ->
                val ch = g.getService(FTMS_SERVICE)?.getCharacteristic(CONTROL_POINT)
                ch != null && write(g, ch, bytes)
            }
        }
    }

    private fun controlLost() {
        synchronized(lock) {
            autoSpeed.disconnected()
            ops.clear()
            opBusy = false
        }
        canControlSpeed.value = false
        canNudge.value = false
    }

    private fun enqueue(op: (BluetoothGatt) -> Boolean) {
        synchronized(lock) { ops.addLast(op) }
        pump()
    }

    /** Lance la prochaine opération en attente si aucune n'est en cours. */
    private fun pump() {
        while (true) {
            val (gatt, op) = synchronized(lock) {
                if (opBusy || ops.isEmpty()) return
                val g = treadmillGatt
                if (g == null || !hasPermissions()) {
                    ops.clear()
                    return
                }
                opBusy = true
                g to ops.removeFirst()
            }
            val started = try {
                op(gatt)
            } catch (e: Exception) {
                false
            }
            if (started) return
            // Opération impossible (caractéristique absente, liaison coupée) : on passe à la suivante.
            synchronized(lock) { opBusy = false }
        }
    }

    /** Fin de l'opération en cours, signalée par un rappel Bluetooth. */
    private fun completeOp() {
        synchronized(lock) { opBusy = false }
        pump()
    }

    private fun write(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        } else {
            @Suppress("DEPRECATION")
            ch.value = bytes
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(ch)
        }

    /** Active les notifications (ou les indications) d'une caractéristique. */
    private fun subscribe(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, indicate: Boolean): Boolean {
        gatt.setCharacteristicNotification(ch, true)
        val descriptor = ch.getDescriptor(CCCD) ?: return false
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, value) == 0
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
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
                    controlLost()
                }
                link.value = Link.CONNECTING
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!heartRate) {
                treadmillServicesDiscovered(gatt)
                return
            }
            val service = gatt.getService(HR_SERVICE) ?: return
            val ch = service.getCharacteristic(HR_MEASUREMENT) ?: return
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

        /** Tapis : abonnements, lecture de l'étalonnage puis consigne en attente, une opération à la fois. */
        private fun treadmillServicesDiscovered(gatt: BluetoothGatt) {
            val ftms = gatt.getService(FTMS_SERVICE) ?: return
            if (ftms.getCharacteristic(TREADMILL_DATA) == null) return
            val control = ftms.getCharacteristic(CONTROL_POINT)
            synchronized(lock) {
                ops.clear()
                opBusy = false
            }
            enqueue { g -> g.getService(FTMS_SERVICE)?.getCharacteristic(TREADMILL_DATA)?.let { subscribe(g, it, false) } ?: false }
            if (control != null) {
                enqueue { g -> g.getService(FTMS_SERVICE)?.getCharacteristic(CONTROL_POINT)?.let { subscribe(g, it, true) } ?: false }
                if (ftms.getCharacteristic(MACHINE_STATUS) != null)
                    enqueue { g -> g.getService(FTMS_SERVICE)?.getCharacteristic(MACHINE_STATUS)?.let { subscribe(g, it, false) } ?: false }
            }
            if (gatt.getService(CALIB_SERVICE)?.getCharacteristic(CALIB_CHAR) != null)
                enqueue { g -> g.getService(CALIB_SERVICE)?.getCharacteristic(CALIB_CHAR)?.let { g.readCharacteristic(it) } ?: false }
            link.value = Link.CONNECTED
            canControlSpeed.value = control != null
            canNudge.value = gatt.getService(CALIB_SERVICE)?.getCharacteristic(NUDGE_CHAR) != null
            if (control != null) sendControl(synchronized(lock) { autoSpeed.connected() })
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!heartRate) completeOp()
        }

        @Deprecated("Appelé avant Android 13")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            val value = characteristic.value
            if (status == BluetoothGatt.GATT_SUCCESS && value != null) handleCalibration(characteristic.uuid, value)
            if (!heartRate) completeOp()
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleCalibration(characteristic.uuid, value)
            if (!heartRate) completeOp()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (heartRate) return
            if (characteristic.uuid == CALIB_CHAR) {
                calibrationSaved.value = status == BluetoothGatt.GATT_SUCCESS
                // Relecture de la valeur gardée par le boîtier.
                if (status == BluetoothGatt.GATT_SUCCESS)
                    enqueue { g -> g.getService(CALIB_SERVICE)?.getCharacteristic(CALIB_CHAR)?.let { g.readCharacteristic(it) } ?: false }
            }
            completeOp()
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
                t.speedKmh?.let { kmh ->
                    speed.value = kmh
                    sendControl(synchronized(lock) { autoSpeed.measured(kmh) })
                }
                t.inclinePct?.let { incline.value = it }
            } else if (uuid == CONTROL_POINT) {
                val r = FtmsControl.response(value) ?: return
                sendControl(synchronized(lock) { autoSpeed.response(r) })
            } else if (uuid == MACHINE_STATUS) {
                val s = FtmsControl.status(value) ?: return
                sendControl(synchronized(lock) { autoSpeed.status(s) })
            }
        }
    }
}
