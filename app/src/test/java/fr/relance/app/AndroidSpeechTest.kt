package fr.relance.app

import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import fr.relance.app.core.Heard
import fr.relance.app.core.Question
import fr.relance.app.core.Speech
import fr.relance.app.core.VoiceCommands
import fr.relance.app.voice.AndroidSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSpeechRecognizer

/** Reconnaissance vocale d'Android sur l'appareil (Pixel 8a et autres), simulée par Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSpeechTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @Test
    fun onDeviceFrenchIsRequestedWithTheExpectedWords() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        assertTrue(AndroidSpeech.available(ctx))
        val a = AndroidSpeech(ctx)
        val heard = ArrayList<List<String>>()
        a.listen({ heard += it }, { error("erreur $it") })
        ShadowLooper.idleMainLooper()
        val shadow = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        val intent = shadow.lastRecognizerIntent
        assertEquals("fr-FR", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertTrue("incertain" in intent.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS)!!)
        // Le moteur de Google renvoie souvent des chiffres et de la ponctuation.
        shadow.triggerOnResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Trois.", "3")) })
        assertEquals(listOf("Trois.", "3"), heard.single())
        assertEquals(Heard.Effort(3), VoiceCommands.parse(VoiceCommands.best(heard.single(), Question.RPE), Question.RPE))
    }

    @Test
    fun languageErrorsAreReported() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val a = AndroidSpeech(ctx)
        var err = 0
        a.listen({}, { err = it })
        ShadowLooper.idleMainLooper()
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnError(13)
        assertTrue(err in AndroidSpeech.LANGUAGE_ERRORS)
    }

    @Test
    fun unavailableWithoutOnDeviceRecognition() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        assertTrue(!AndroidSpeech.available(ctx))
        var support: AndroidSpeech.Support? = null
        AndroidSpeech(ctx).checkSupport { support = it }
        assertEquals(AndroidSpeech.Support.UNSUPPORTED, support)
    }

    @Test
    fun bestHypothesisForTheQuestion() {
        // La première hypothèse ne veut rien dire pour la question : on prend la suivante.
        assertEquals("oui", VoiceCommands.best(listOf("ouïe", "oui"), Question.SPEECH))
        assertEquals(Heard.Comfort(Speech.UNSURE), VoiceCommands.parse(VoiceCommands.best(listOf("Je ne sais pas."), Question.SPEECH), Question.SPEECH))
        // Une première hypothèse ambiguë n'est pas remplacée par une suivante plus commode.
        assertEquals("oui non", VoiceCommands.best(listOf("oui non", "oui"), Question.SPEECH))
        assertEquals("", VoiceCommands.best(emptyList(), Question.SPEECH))
        assertEquals(Heard.Effort(10), VoiceCommands.parse("10", Question.RPE))
    }
}
