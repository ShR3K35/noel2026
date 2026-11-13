package fr.relance.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import fr.relance.app.core.VoiceCommands

/**
 * Reconnaissance vocale d'Android, **sur l'appareil** (Android 12 et plus : Pixel, et tout téléphone qui la propose).
 * Rien ne part sur Internet. Elle écoute une réponse à la fois, pendant les questions des contrôles.
 */
class AndroidSpeech(private val ctx: Context) {
    enum class Support { INSTALLED, DOWNLOADABLE, UNSUPPORTED, UNKNOWN }

    companion object {
        const val LANG = "fr-FR"
        /** Langue indisponible sur l'appareil (codes 12 et 13 d'Android 12). */
        val LANGUAGE_ERRORS = setOf(12, 13)

        fun available(ctx: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    }

    private var rec: SpeechRecognizer? = null

    private fun recognizer(): SpeechRecognizer =
        rec ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).also { rec = it }

    fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANG)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .apply {
            // Les mots attendus (oui, non, incertain, chiffres, commandes) sont favorisés.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(VoiceCommands.GRAMMAR.filter { it != "[unk]" }))
            }
        }

    /** Le français est-il installé pour la reconnaissance hors ligne ? (Android 13 et plus ; sinon : inconnu, on essaie.) */
    fun checkSupport(done: (Support) -> Unit) {
        if (!available(ctx)) return done(Support.UNSUPPORTED)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return done(Support.UNKNOWN)
        try {
            recognizer().checkRecognitionSupport(intent(), ctx.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    fun fr(l: List<String>) = l.any { it.lowercase().startsWith("fr") }
                    done(
                        when {
                            fr(s.installedOnDeviceLanguages) -> Support.INSTALLED
                            fr(s.supportedOnDeviceLanguages) || fr(s.pendingOnDeviceLanguages) -> Support.DOWNLOADABLE
                            else -> Support.UNSUPPORTED
                        }
                    )
                }

                override fun onError(error: Int) = done(Support.UNKNOWN)
            })
        } catch (e: Exception) {
            done(Support.UNKNOWN)
        }
    }

    /** Demande à Android d'installer le français hors ligne (téléchargement géré par le système). */
    fun download() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) try {
            recognizer().triggerModelDownload(intent())
        } catch (e: Exception) {
            // Indisponible : le choix de Vosk reste possible.
        }
    }

    /** Écoute une réponse. `onResult` reçoit les hypothèses, de la plus probable à la moins probable. */
    fun listen(onResult: (List<String>) -> Unit, onError: (Int) -> Unit) {
        val r = try {
            recognizer()
        } catch (e: Exception) {
            return onError(SpeechRecognizer.ERROR_CLIENT)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) = onResult(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf())
            override fun onError(error: Int) = onError(error)
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(intent())
    }

    fun cancel() {
        try {
            rec?.cancel()
        } catch (e: Exception) {
            // Déjà arrêté.
        }
    }

    fun destroy() {
        rec?.destroy()
        rec = null
    }
}
