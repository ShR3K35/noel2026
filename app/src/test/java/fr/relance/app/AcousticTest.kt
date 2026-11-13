package fr.relance.app

import fr.relance.app.core.Acoustic
import fr.relance.app.core.AcousticConfig
import fr.relance.app.core.CaptureIssue
import fr.relance.app.core.Heard
import fr.relance.app.core.Question
import fr.relance.app.core.Speech
import fr.relance.app.core.VoiceBaseline
import fr.relance.app.core.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Analyse acoustique descriptive et interprétation des réponses. Les enregistrements sont des voix de synthèse
 * (espeak-ng) : ils vérifient le logiciel, pas la pertinence physiologique des mesures.
 */
class AcousticTest {
    private val passage = wav(File("src/test/resources/audio/passage-fr.wav"))
    private val baseline = VoiceBaseline(listOf(Acoustic.analyze(passage), Acoustic.analyze(passage)), ambientDb = -90.0)

    @Test
    fun describesAReading() {
        val f = Acoustic.analyze(passage)
        assertTrue(f.usable)
        assertEquals(9.8, f.readingSec, 0.3)
        assertEquals(2, f.pauses) // les deux points du texte
        assertTrue(f.speechFraction > 0.85)
        assertTrue(baseline.valid)
        val same = Acoustic.compare(baseline, f)
        assertFalse(same.changed)
        assertEquals(listOf("Lecture proche de la lecture de départ."), same.summary)
    }

    @Test
    fun morePausesAreReportedInPlainFrench() {
        // Trois silences de 0,6 s insérés au milieu de la lecture.
        val slowed = insertSilences(passage, listOf(2.4, 5.2, 8.1), 0.6)
        val c = Acoustic.compare(baseline, Acoustic.analyze(slowed))
        assertTrue(c.usable)
        assertTrue(c.changed)
        assertEquals(3, c.pausesDelta)
        assertTrue(c.durationChangePct in 15..25)
        assertTrue(c.summary.any { it.startsWith("Davantage de pauses que lors de la lecture de départ") })
        assertTrue(c.summary.any { it.startsWith("Lecture plus longue de") })
    }

    /** Sensibilité au bruit : un bruit modéré ne crée pas d'écart notable ; un bruit fort rend la capture inutilisable, sans fausse mesure. */
    @Test
    fun noiseSensitivity() {
        for ((level, expectUsable) in listOf(200.0 to true, 600.0 to true, 1500.0 to false, 3000.0 to false, 6000.0 to false)) {
            val f = Acoustic.analyze(withTreadmillNoise(passage, level, Random(1)))
            assertEquals("bruit $level : ${f.snrDb} dB ${f.issues}", expectUsable, f.usable)
            val c = Acoustic.compare(baseline, f)
            if (expectUsable) assertFalse("bruit $level ne doit pas passer pour un changement : ${c.summary}", c.changed)
            else {
                assertFalse(c.usable)
                assertTrue(c.summary.single().startsWith("Capture inutilisable"))
            }
        }
        assertTrue(Acoustic.analyze(withTreadmillNoise(passage, 3000.0, Random(1))).issues.contains(CaptureIssue.TOO_NOISY))
    }

    @Test
    fun unusableCaptures() {
        assertEquals(listOf(CaptureIssue.NO_SPEECH), Acoustic.analyze(ShortArray(16000 * 5)).issues)
        val loud = ShortArray(passage.size) { (passage[it] * 30).coerceIn(-32768, 32767).toShort() }
        assertTrue(CaptureIssue.CLIPPED in Acoustic.analyze(loud).issues)
        val cut = passage.copyOfRange(0, 16000 * 5)
        assertTrue(CaptureIssue.TRUNCATED in Acoustic.analyze(cut).issues)
        // Deux lectures de départ trop différentes : pas de référence fiable.
        val half = Acoustic.analyze(passage.copyOfRange(0, 16000 * 7))
        assertFalse(VoiceBaseline(listOf(Acoustic.analyze(passage), half), -90.0).valid)
        assertFalse(Acoustic.compare(VoiceBaseline(emptyList(), -90.0), Acoustic.analyze(passage)).usable)
    }

    @Test
    fun readingEndIsDetected() {
        val frames = Acoustic.frameDb(passage)
        assertTrue(Acoustic.readingEnded(frames, -90.0))
        assertFalse(Acoustic.readingEnded(frames.copyOfRange(0, 150), -90.0))
    }

    /** Transcriptions obtenues hors ligne avec Vosk small-fr-0.22 et la grammaire de l'app (tools/vosk-check). */
    @Test
    fun voskTranscriptsAreInterpretedInContext() {
        assertEquals(Heard.Comfort(Speech.YES), VoiceCommands.parse("oui", Question.SPEECH))
        assertEquals(Heard.Comfort(Speech.NO), VoiceCommands.parse("non", Question.SPEECH))
        assertEquals(Heard.Comfort(Speech.UNSURE), VoiceCommands.parse("incertain", Question.SPEECH))
        assertEquals(Heard.Comfort(Speech.UNSURE), VoiceCommands.parse("je ne sais pas", Question.SPEECH))
        assertEquals(Heard.Effort(3), VoiceCommands.parse("trois", Question.RPE))
        assertEquals(Heard.Effort(7), VoiceCommands.parse("sept", Question.RPE))
        assertEquals(Heard.Effort(10), VoiceCommands.parse("dix", Question.RPE))
        assertEquals(Heard.YesNo(true), VoiceCommands.parse("oui", Question.MOVEMENT))
        assertEquals(Heard.Stop, VoiceCommands.parse("arrêter", Question.RPE))
        assertEquals(Heard.SlowDown, VoiceCommands.parse("ralentir", null))
        // Le même mot change de sens selon la question : « un » est un chiffre seulement pour l'effort.
        assertEquals(Heard.Effort(1), VoiceCommands.parse("un", Question.RPE))
        assertEquals(Heard.Nothing, VoiceCommands.parse("un", Question.SPEECH))
        assertEquals(Heard.Nothing, VoiceCommands.parse("trois", Question.SPEECH))
        // Écho de la question lue par l'app, reconnu par Vosk : ambigu, jamais pris pour une réponse.
        assertTrue(VoiceCommands.parse("[unk] oui incertain non", Question.SPEECH) is Heard.Ambiguous)
        assertTrue(VoiceCommands.parse("deux trois", Question.RPE) is Heard.Ambiguous)
        assertEquals(Heard.Nothing, VoiceCommands.parse("[unk]", Question.SPEECH))
    }

    companion object {
        /** WAV 16 bits mono : données après l'en-tête « data ». */
        fun wav(f: File): ShortArray {
            val b = f.readBytes()
            var i = 12
            while (i < b.size - 8) {
                val id = String(b, i, 4)
                val len = ByteBuffer.wrap(b, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "data") {
                    val bb = ByteBuffer.wrap(b, i + 8, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    return ShortArray(bb.remaining()).also { bb.get(it) }
                }
                i += 8 + len
            }
            error("pas de données")
        }

        /** Bruit de tapis simulé : ronflement du moteur (50, 100, 150 Hz), souffle large bande, chocs de pas à 2,6 Hz. */
        fun withTreadmillNoise(pcm: ShortArray, level: Double, rnd: Random, rate: Int = 16000): ShortArray {
            var brown = 0.0
            return ShortArray(pcm.size) { i ->
                val t = i.toDouble() / rate
                brown = (brown + rnd.nextDouble(-1.0, 1.0) * 0.1) * 0.995
                val hum = sin(2 * PI * 50 * t) + 0.6 * sin(2 * PI * 100 * t) + 0.3 * sin(2 * PI * 150 * t)
                val white = rnd.nextDouble(-1.0, 1.0)
                val phase = (t * 2.6) % 1.0
                val thump = if (phase < 0.03) sin(2 * PI * 80 * t) * (1 - phase / 0.03) * 2.0 else 0.0
                val v = pcm[i] + level * (hum * 0.8 + white * 0.5 + brown * 3 + thump)
                v.coerceIn(-32768.0, 32767.0).toInt().toShort()
            }
        }

        fun insertSilences(pcm: ShortArray, atSec: List<Double>, durSec: Double, rate: Int = 16000): ShortArray {
            val out = ArrayList<Short>(pcm.size + (atSec.size * durSec * rate).toInt())
            val cuts = atSec.map { (it * rate).toInt() }.sorted()
            var c = 0
            for (i in pcm.indices) {
                if (c < cuts.size && i == cuts[c]) {
                    repeat((durSec * rate).toInt()) { out += 0 }
                    c++
                }
                out += pcm[i]
            }
            return out.toShortArray()
        }
    }
}
