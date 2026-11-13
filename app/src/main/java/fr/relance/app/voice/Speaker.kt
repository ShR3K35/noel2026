package fr.relance.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Synthèse vocale de l'app. Pendant qu'elle parle (et un court instant après), le micro est mis en sourdine :
 * le texte prononcé par l'app ne doit jamais être pris pour une réponse.
 */
class Speaker(ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    var ready = false
        private set
    @Volatile var speaking = false
        private set
    /** Fin de la sourdine (horloge monotone, ms). */
    @Volatile var muteUntil = 0L
        private set
    private val ids = AtomicInteger()
    private val callbacks = HashMap<String, () -> Unit>()

    init {
        try {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val r = tts?.setLanguage(Locale.FRANCE) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String) { speaking = true }
                        override fun onDone(id: String) = finished(id)
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String) = finished(id)
                        override fun onError(id: String, code: Int) = finished(id)
                        // Phrase coupée par une autre : sa suite (écoute d'une réponse) doit quand même avoir lieu.
                        override fun onStop(id: String, interrupted: Boolean) = finished(id)
                    })
                }
            }
        } catch (e: Exception) {
            // Pas de synthèse vocale : consignes à l'écran seulement.
        }
    }

    private fun finished(id: String) {
        speaking = false
        muteUntil = android.os.SystemClock.elapsedRealtime() + 400
        val cb = synchronized(callbacks) { callbacks.remove(id) }
        if (cb != null) main.postDelayed(cb, 400)
    }

    /** Dit `text`, puis appelle `then` (sur le fil principal), même si la synthèse est indisponible. */
    fun say(text: String, then: (() -> Unit)? = null) {
        val t = tts
        if (!ready || t == null) {
            then?.let { main.post(it) }
            return
        }
        val id = "relance-${ids.incrementAndGet()}"
        if (then != null) synchronized(callbacks) { callbacks[id] = then }
        speaking = true
        muteUntil = Long.MAX_VALUE
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finished(id)
    }

    fun muted(nowMillis: Long): Boolean = speaking || nowMillis < muteUntil

    fun shutdown() {
        tts?.shutdown()
    }
}
