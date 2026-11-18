package fr.relance.app.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** Trames du FTMS Control Point (0x2AD9) et du Fitness Machine Status (0x2ADA). */
object FtmsControl {
    const val OP_REQUEST_CONTROL = 0x00
    const val OP_SET_TARGET_SPEED = 0x02
    const val OP_RESPONSE = 0x80

    const val RESULT_SUCCESS = 0x01
    const val RESULT_NOT_SUPPORTED = 0x02
    const val RESULT_INVALID_PARAMETER = 0x03
    const val RESULT_OPERATION_FAILED = 0x04
    const val RESULT_CONTROL_NOT_PERMITTED = 0x05

    const val STATUS_STOPPED_OR_PAUSED = 0x02
    const val STATUS_STOPPED_BY_SAFETY_KEY = 0x03
    const val STATUS_TARGET_SPEED_CHANGED = 0x05

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    fun requestControl(): ByteArray = byteArrayOf(OP_REQUEST_CONTROL.toByte())

    /** Consigne de vitesse, en unités de 0,01 km/h. */
    fun setTargetSpeed(kmh: Double): ByteArray {
        val v = (kmh * 100).roundToInt().coerceIn(0, 0xFFFF)
        return byteArrayOf(OP_SET_TARGET_SPEED.toByte(), (v and 0xFF).toByte(), (v shr 8).toByte())
    }

    data class Response(val op: Int, val result: Int)

    fun response(b: ByteArray): Response? =
        if (b.size >= 3 && u8(b, 0) == OP_RESPONSE) Response(u8(b, 1), u8(b, 2)) else null

    sealed interface Status {
        data class TargetSpeed(val kmh: Double) : Status
        data object Stopped : Status
    }

    fun status(b: ByteArray): Status? {
        if (b.isEmpty()) return null
        return when (u8(b, 0)) {
            STATUS_TARGET_SPEED_CHANGED -> if (b.size >= 3) Status.TargetSpeed((u8(b, 1) or (u8(b, 2) shl 8)) / 100.0) else null
            STATUS_STOPPED_OR_PAUSED, STATUS_STOPPED_BY_SAFETY_KEY -> Status.Stopped
            else -> null
        }
    }
}

/**
 * Réglage automatique de la vitesse du tapis pendant une séance guidée, par le standard FTMS
 * (boîtier Relance équipé des optocoupleurs, ou tapis connecté qui accepte une consigne).
 *
 * Sans dépendance Android : chaque méthode renvoie les trames à écrire sur le Control Point, dans l'ordre.
 * L'appelant garantit qu'un seul fil d'exécution l'utilise à la fois.
 *
 * Règles :
 * - la consigne n'est envoyée qu'une fois par bloc ; si la personne change la vitesse sur la console,
 *   l'app la laisse faire jusqu'au bloc suivant ;
 * - le tapis n'est jamais démarré à distance : si la bande est arrêtée, la consigne part dès qu'elle tourne.
 */
class AutoSpeed {
    /** Consigne à tenir, null si l'app ne règle rien en ce moment. */
    var desired: Double? = null
        private set

    /** Message à afficher pendant la séance, null si tout va bien. */
    var notice: String? = null
        private set

    /** false si le tapis a répondu qu'il ne se règle pas à distance. */
    var supported = true
        private set

    private var requestKey: Pair<Int, Double?>? = null
    private var connected = false
    private var granted = false
    private var waitingForBelt = false
    private var permissionRetries = 0

    /**
     * Consigne du bloc en cours (null : ne rien régler, par exemple bloc de test ou séance en pause).
     * Appelée à chaque seconde : seul un changement de bloc ou de consigne déclenche un envoi.
     */
    fun target(kmh: Double?, block: Int): List<ByteArray> {
        val v = kmh?.let { (it * 10).roundToInt() / 10.0 }
        val key = block to v
        if (key == requestKey) return emptyList()
        requestKey = key
        desired = v
        waitingForBelt = false
        permissionRetries = 0
        if (v == null) {
            if (supported) notice = null
            return emptyList()
        }
        if (!supported) return emptyList()
        notice = null
        return send()
    }

    /** Liaison (re)établie avec un appareil qui a un Control Point. */
    fun connected(): List<ByteArray> {
        connected = true
        granted = false
        waitingForBelt = false
        permissionRetries = 0
        supported = true
        return send()
    }

    fun disconnected() {
        connected = false
        granted = false
    }

    /** Vitesse mesurée par le tapis : relance la consigne quand la bande se met à tourner. */
    fun measured(kmh: Double): List<ByteArray> {
        if (!waitingForBelt || kmh < BELT_RUNNING_KMH || desired == null) return emptyList()
        waitingForBelt = false
        return send()
    }

    fun response(r: FtmsControl.Response): List<ByteArray> {
        when (r.op) {
            FtmsControl.OP_REQUEST_CONTROL -> granted = r.result == FtmsControl.RESULT_SUCCESS
            FtmsControl.OP_SET_TARGET_SPEED -> when (r.result) {
                FtmsControl.RESULT_SUCCESS -> {
                    permissionRetries = 0
                    notice = null
                }
                FtmsControl.RESULT_OPERATION_FAILED -> {
                    // Le boîtier refuse tant que la bande est arrêtée : on attend qu'elle tourne.
                    waitingForBelt = true
                    notice = "Démarre le tapis sur la console : la vitesse se réglera ensuite."
                }
                FtmsControl.RESULT_CONTROL_NOT_PERMITTED -> {
                    granted = false
                    if (permissionRetries < 1) {
                        permissionRetries++
                        return send()
                    }
                    notice = "Le tapis refuse la commande : règle la vitesse sur la console."
                }
                FtmsControl.RESULT_INVALID_PARAMETER -> {
                    desired = null
                    notice = "Vitesse hors des limites du tapis : règle-la sur la console."
                }
                FtmsControl.RESULT_NOT_SUPPORTED -> {
                    supported = false
                    desired = null
                    notice = "Ce tapis ne se règle pas à distance : règle la vitesse sur la console."
                }
            }
        }
        return emptyList()
    }

    fun status(s: FtmsControl.Status): List<ByteArray> {
        when (s) {
            is FtmsControl.Status.TargetSpeed -> {
                val d = desired ?: return emptyList()
                // Le boîtier annonce aussi la consigne qu'il vient d'accepter : seul un écart compte.
                if (abs(s.kmh - d) > 0.05) {
                    desired = null
                    waitingForBelt = false
                    notice = "Vitesse changée sur la console : l'app reprendra au bloc suivant."
                }
            }
            FtmsControl.Status.Stopped -> if (desired != null) waitingForBelt = true
        }
        return emptyList()
    }

    private fun send(): List<ByteArray> {
        val v = desired ?: return emptyList()
        if (!connected || !supported) return emptyList()
        val set = FtmsControl.setTargetSpeed(v)
        return if (granted) listOf(set) else listOf(FtmsControl.requestControl(), set)
    }

    companion object {
        /** Au-delà, la bande tourne : une consigne peut être acceptée. */
        const val BELT_RUNNING_KMH = 0.6
    }
}
