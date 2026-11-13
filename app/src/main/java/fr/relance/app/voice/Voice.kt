package fr.relance.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import fr.relance.app.core.Acoustic
import fr.relance.app.core.AcousticConfig
import fr.relance.app.core.VoiceCommands
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** État du micro, affiché en permanence pendant un test. */
enum class MicState(val label: String) {
    OFF("Micro désactivé : réponses par les boutons"),
    NO_PERMISSION("Micro non autorisé : réponses par les boutons"),
    NO_MODEL("Modèle vocal français absent : réponses par les boutons"),
    DOWNLOADING("Téléchargement du modèle vocal…"),
    LOADING("Préparation de la reconnaissance vocale…"),
    READY("Micro prêt : dis « pause », « ralentir » ou « arrêter » si besoin"),
    SPEAKING("L'app parle : micro en sourdine"),
    READING("Enregistrement de la lecture…"),
    LISTENING("J'écoute ta réponse…"),
    ERROR("Micro indisponible : réponses par les boutons"),
}

/** Le modèle Vosk français (Apache 2.0), téléchargé une fois puis utilisé hors ligne. */
class SpeechModelStore(private val dir: File) {
    companion object {
        const val NAME = "vosk-model-small-fr-0.22"
        const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
        /** Taille publiée de l'archive : un fichier incomplet est refusé. */
        const val SIZE = 42_233_323L
    }

    val modelDir = File(dir, NAME)
    val ready: Boolean get() = File(modelDir, "am/final.mdl").exists() && File(modelDir, "conf/model.conf").exists()

    /** Télécharge et décompresse le modèle. Renvoie null si tout s'est bien passé, sinon un message. */
    fun download(onProgress: (Float) -> Unit, cancelled: () -> Boolean = { false }): String? {
        dir.mkdirs()
        val part = File(dir, "$NAME.zip.part")
        val tmp = File(dir, "$NAME.tmp")
        try {
            val c = URL(URL).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            if (c.responseCode != 200) return "Téléchargement impossible (code ${c.responseCode})."
            val total = c.contentLengthLong.takeIf { it > 0 } ?: SIZE
            var got = 0L
            c.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val b = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) return "Téléchargement annulé."
                        val n = input.read(b)
                        if (n < 0) break
                        out.write(b, 0, n)
                        got += n
                        onProgress(got.toFloat() / total)
                    }
                }
            }
            if (got != SIZE) return "Téléchargement interrompu ($got octets sur $SIZE). Réessaie avec une meilleure connexion."
            tmp.deleteRecursively()
            ZipInputStream(part.inputStream().buffered()).use { zip ->
                val root = tmp.canonicalPath + File.separator
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = File(tmp, e.name)
                    if (!out.canonicalPath.startsWith(root)) return "Archive du modèle invalide."
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zip.copyTo(it) }
                    }
                }
            }
            val inner = File(tmp, NAME).takeIf { it.isDirectory } ?: tmp
            modelDir.deleteRecursively()
            if (!inner.renameTo(modelDir)) return "Impossible d'installer le modèle."
            return if (ready) null else "Modèle incomplet après décompression."
        } catch (e: java.io.IOException) {
            return "Téléchargement interrompu : vérifie ta connexion et réessaie."
        } finally {
            part.delete()
            tmp.deleteRecursively()
        }
    }

    fun delete() = modelDir.deleteRecursively()
}

/** Reconnaissance des réponses, limitée au vocabulaire de l'app. */
interface AnswerRecognizer {
    /** Renvoie le texte reconnu quand une réponse se termine, sinon null. */
    fun accept(buf: ShortArray, n: Int): String?
    fun reset()
    fun close()
}

class VoskAnswerRecognizer(modelDir: File) : AnswerRecognizer {
    private val model = org.vosk.Model(modelDir.absolutePath)
    private val grammar = VoiceCommands.GRAMMAR.joinToString(",", "[", "]") { JSONObject.quote(it) }
    private val rec = org.vosk.Recognizer(model, AcousticConfig.SAMPLE_RATE.toFloat(), grammar)

    override fun accept(buf: ShortArray, n: Int): String? =
        if (rec.acceptWaveForm(buf, n)) JSONObject(rec.result).optString("text").takeIf { it.isNotBlank() } else null

    override fun reset() = rec.reset()

    override fun close() {
        rec.close(); model.close()
    }
}

/**
 * Capture du micro : un seul flux audio, en mémoire. Il alimente soit l'analyse d'une lecture, soit la reconnaissance
 * des réponses, jamais les deux à la fois, et rien n'est reconnu pendant que l'app parle.
 */
class AudioCapture(private val ctx: Context) {
    interface Sink {
        fun onAudio(buf: ShortArray, n: Int)
        fun onError(message: String)
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    var device: String = ""
        private set

    fun permitted(): Boolean = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Démarre la capture. Renvoie null si elle tourne, sinon la raison. */
    @Suppress("MissingPermission")
    fun start(sink: Sink): String? {
        if (running) return null
        if (!permitted()) return "Micro non autorisé."
        val rate = AcousticConfig.SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return "Micro incompatible."
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        } catch (e: Exception) {
            return "Micro indisponible."
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release(); return "Micro indisponible (utilisé par une autre app ?)."
        }
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release(); return "Micro indisponible (utilisé par une autre app ?)."
        }
        device = when (r.routedDevice?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "micro du téléphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "micro Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "micro filaire"
            null -> "micro"
            else -> "micro (${r.routedDevice?.productName})"
        }
        record = r
        running = true
        thread = Thread({
            val b = ShortArray(rate / 10)
            while (running) {
                val n = r.read(b, 0, b.size)
                if (n < 0) {
                    running = false
                    sink.onError("Micro interrompu (code $n).")
                    break
                }
                if (n > 0) sink.onAudio(b, n)
            }
        }, "relance-micro").also { it.start() }
        return null
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        try {
            record?.stop()
        } catch (e: Exception) {
            // Déjà arrêté.
        }
        record?.release()
        record = null
    }
}

/** Enregistrements de diagnostic : seulement si l'utilisateur l'a choisi, effaçables à tout moment. */
class Diagnostics(base: File) {
    val dir = File(base, "voice-diagnostics")

    fun save(name: String, pcm: ShortArray) {
        dir.mkdirs()
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val rate = AcousticConfig.SAMPLE_RATE
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size * 2)
        File(dir, "$name.wav").writeBytes(h.array() + data.array())
    }

    fun count(): Int = dir.listFiles()?.size ?: 0
    fun clear() = dir.deleteRecursively()
}

/** Tampon d'une lecture, détecte sa fin. */
class ReadingBuffer(private val maxSec: Int = 35) {
    private val chunks = ArrayList<ShortArray>()
    private var size = 0
    val seconds: Double get() = size.toDouble() / AcousticConfig.SAMPLE_RATE

    fun add(b: ShortArray, n: Int) {
        chunks += b.copyOf(n); size += n
    }

    fun pcm(): ShortArray {
        val out = ShortArray(size)
        var i = 0
        for (c in chunks) {
            c.copyInto(out, i); i += c.size
        }
        return out
    }

    fun full(): Boolean = seconds >= maxSec

    fun ended(noiseDb: Double): Boolean = seconds > 2 && Acoustic.readingEnded(Acoustic.frameDb(pcm()), noiseDb)

    fun clear() {
        chunks.clear(); size = 0
    }
}
