package fr.relance.app.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Analyse acoustique descriptive d'une lecture à voix haute, entièrement sur l'appareil.
 * Elle décrit la lecture (durée, segments de parole, pauses) et la compare aux lectures de départ.
 * Ce n'est ni une mesure de la respiration, ni un classificateur physiologique : un silence n'est pas un souffle,
 * et un changement mesuré n'est pas une probabilité. Elle ne valide jamais un palier sans réponse de l'utilisateur.
 */

/** Seuils techniques, heuristiques et ajustables (voir docs/CALIBRATION.md et AcousticTest). */
object AcousticConfig {
    const val SAMPLE_RATE = 16000
    const val FRAME_MS = 20
    /** Filtre passe-haut du premier ordre : atténue le ronflement du moteur et les chocs sourds. */
    const val HIGH_PASS_HZ = 150.0
    /** Début de parole : énergie au-dessus du bruit de fond de ce nombre de dB ; fin : sous le second (hystérésis). */
    const val ON_DB = 9.0
    const val OFF_DB = 5.0
    /** Le bruit de fond est le 15e centile des trames de l'enregistrement (début et pauses), ou le bruit ambiant s'il est plus fort. */
    const val NOISE_PERCENTILE = 0.15
    /** Bouffées plus courtes ignorées (pas sur la bande, clics). */
    const val MIN_SPEECH_MS = 100
    /** Silences plus courts : à l'intérieur d'un mot ou d'une liaison, pas une pause. */
    const val MIN_PAUSE_MS = 250
    /** Capture inutilisable : rapport parole / bruit trop faible, saturation, parole trop courte. */
    const val MIN_SNR_DB = 15.0
    const val MAX_CLIPPED_FRACTION = 0.005
    const val MIN_READING_SEC = 3.0
    /** Changement notable par rapport aux lectures de départ (déclenche une question de confort, rien d'autre). */
    const val DURATION_CHANGE_PCT = 15
    const val PAUSES_DELTA = 2
    const val PAUSE_SEC_DELTA = 1.0
    const val SPEECH_FRACTION_DROP = 0.08
    /** Les deux lectures de départ doivent être cohérentes entre elles. */
    const val BASELINE_MAX_SPREAD_PCT = 30
}

@Serializable
enum class CaptureIssue(val label: String) {
    NO_SPEECH("Aucune parole détectée"),
    TOO_NOISY("Bruit trop fort par rapport à la voix"),
    CLIPPED("Son saturé : micro trop près ou voix trop forte"),
    TOO_SHORT("Lecture trop courte ou incomplète"),
    TRUNCATED("Lecture coupée par la fin de l'enregistrement"),
}

@Serializable
data class AcousticFeatures(
    val recordedSec: Double,
    /** Du début de la première parole à la fin de la dernière. */
    val readingSec: Double,
    val speechSec: Double,
    val segments: Int,
    /** Pauses internes (silences d'au moins 250 ms entre deux segments de parole). */
    val pauses: Int,
    val pauseSec: Double,
    val longestPauseSec: Double,
    val speechFraction: Double,
    val noiseDb: Double,
    val speechDb: Double,
    val snrDb: Double,
    val clippedFraction: Double,
    val issues: List<CaptureIssue> = emptyList(),
) {
    val usable: Boolean get() = issues.isEmpty()
}

/** Médiane des deux lectures de départ, avec ce qui permet de savoir si elle est encore valable. */
@Serializable
data class VoiceBaseline(
    val readings: List<AcousticFeatures>,
    val ambientDb: Double,
    val passageVersion: Int = PaceProtocol.PASSAGE_VERSION,
    /** Micro utilisé (intégré, filaire, Bluetooth…) : en changer impose de refaire la référence. */
    val device: String = "",
    val day: Long = 0,
) {
    val readingSec: Double get() = median(readings.map { it.readingSec })
    val pauses: Double get() = median(readings.map { it.pauses.toDouble() })
    val pauseSec: Double get() = median(readings.map { it.pauseSec })
    val speechFraction: Double get() = median(readings.map { it.speechFraction })

    /** Deux lectures utilisables et cohérentes entre elles. */
    val valid: Boolean
        get() = readings.size >= 2 && readings.all { it.usable } &&
            readings.maxOf { it.readingSec } <= readings.minOf { it.readingSec } * (1 + AcousticConfig.BASELINE_MAX_SPREAD_PCT / 100.0)

    companion object {
        fun median(v: List<Double>): Double {
            if (v.isEmpty()) return 0.0
            val s = v.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}

/** Écarts d'une lecture pendant l'effort par rapport à la référence personnelle. */
@Serializable
data class AcousticComparison(
    val usable: Boolean,
    val issues: List<CaptureIssue> = emptyList(),
    val readingSec: Double = 0.0,
    val baselineReadingSec: Double = 0.0,
    val durationChangePct: Int = 0,
    val pausesDelta: Int = 0,
    val pauseSecDelta: Double = 0.0,
    val speechFractionDelta: Double = 0.0,
    /** Au moins un écart dépasse les seuils techniques : une question de confort supplémentaire peut être posée. */
    val changed: Boolean = false,
    /** Restitution en français simple. */
    val summary: List<String> = emptyList(),
)

object Acoustic {
    private val cfg = AcousticConfig

    private fun db(rms: Double) = 20 * log10(max(rms, 1e-9) / 32768.0)

    /** Énergie par trame (dB), après filtre passe-haut. */
    fun frameDb(pcm: ShortArray, rate: Int = cfg.SAMPLE_RATE): DoubleArray {
        val n = max(1, rate * cfg.FRAME_MS / 1000)
        val frames = pcm.size / n
        val out = DoubleArray(frames)
        val rc = 1.0 / (2 * Math.PI * cfg.HIGH_PASS_HZ)
        val dt = 1.0 / rate
        val a = rc / (rc + dt)
        var prevX = 0.0
        var prevY = 0.0
        for (f in 0 until frames) {
            var sum = 0.0
            for (i in f * n until (f + 1) * n) {
                val x = pcm[i].toDouble()
                val y = a * (prevY + x - prevX)
                prevX = x; prevY = y
                sum += y * y
            }
            out[f] = db(sqrt(sum / n))
        }
        return out
    }

    /** Niveau du bruit ambiant (médiane des trames). */
    fun noiseDb(pcm: ShortArray, rate: Int = cfg.SAMPLE_RATE): Double {
        val d = frameDb(pcm, rate)
        return if (d.isEmpty()) -120.0 else d.sorted()[d.size / 2]
    }

    /** Segments de parole (trames de début et de fin, exclue), par seuil adaptatif à hystérésis. */
    fun speechSegments(frames: DoubleArray, noiseDb: Double): List<IntRange> {
        val minFrames = cfg.MIN_SPEECH_MS / cfg.FRAME_MS
        val bridge = cfg.MIN_PAUSE_MS / cfg.FRAME_MS
        val raw = ArrayList<IntRange>()
        var start = -1
        for (i in frames.indices) {
            val on = frames[i] > noiseDb + cfg.ON_DB
            val stay = frames[i] > noiseDb + cfg.OFF_DB
            if (start < 0 && on) start = i
            else if (start >= 0 && !stay) {
                raw += start until i; start = -1
            }
        }
        if (start >= 0) raw += start until frames.size
        // Silences trop courts pour être des pauses : on réunit les segments.
        val merged = ArrayList<IntRange>()
        for (r in raw) {
            val last = merged.lastOrNull()
            if (last != null && r.first - (last.last + 1) < bridge) merged[merged.size - 1] = last.first..r.last
            else merged += r
        }
        return merged.filter { it.last - it.first + 1 >= minFrames }
    }

    fun analyze(pcm: ShortArray, rate: Int = cfg.SAMPLE_RATE, ambientDb: Double? = null): AcousticFeatures {
        val frames = frameDb(pcm, rate)
        val sec = cfg.FRAME_MS / 1000.0
        val recorded = pcm.size.toDouble() / rate
        val clipped = if (pcm.isEmpty()) 0.0 else pcm.count { abs(it.toInt()) >= 32000 }.toDouble() / pcm.size
        if (frames.isEmpty()) return AcousticFeatures(recorded, 0.0, 0.0, 0, 0, 0.0, 0.0, 0.0, -120.0, -120.0, 0.0, clipped, listOf(CaptureIssue.NO_SPEECH))
        val sorted = frames.sorted()
        val local = sorted[(sorted.size * cfg.NOISE_PERCENTILE).toInt().coerceIn(0, sorted.size - 1)]
        val noise = max(local, ambientDb ?: -120.0)
        val segs = speechSegments(frames, noise)
        val issues = ArrayList<CaptureIssue>()
        if (segs.isEmpty()) {
            return AcousticFeatures(recorded, 0.0, 0.0, 0, 0, 0.0, 0.0, 0.0, noise, noise, 0.0, clipped, listOf(CaptureIssue.NO_SPEECH))
        }
        val speechFrames = segs.flatMap { it.toList() }
        val speechDb = 10 * log10(speechFrames.map { Math.pow(10.0, frames[it] / 10) }.average())
        val gaps = segs.zipWithNext { a, b -> (b.first - a.last - 1) * sec }
        val reading = (segs.last().last + 1 - segs.first().first) * sec
        val speech = speechFrames.size * sec
        val snr = speechDb - noise
        if (snr < cfg.MIN_SNR_DB) issues += CaptureIssue.TOO_NOISY
        if (clipped > cfg.MAX_CLIPPED_FRACTION) issues += CaptureIssue.CLIPPED
        if (reading < cfg.MIN_READING_SEC) issues += CaptureIssue.TOO_SHORT
        if (segs.last().last >= frames.size - 2) issues += CaptureIssue.TRUNCATED
        return AcousticFeatures(
            recordedSec = recorded, readingSec = r2(reading), speechSec = r2(speech), segments = segs.size,
            pauses = gaps.size, pauseSec = r2(gaps.sum()), longestPauseSec = r2(gaps.maxOrNull() ?: 0.0),
            speechFraction = r2(speech / reading), noiseDb = r1(noise), speechDb = r1(speechDb), snrDb = r1(snr),
            clippedFraction = clipped, issues = issues,
        )
    }

    private fun r1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun r2(v: Double) = (v * 100).roundToInt() / 100.0

    fun compare(b: VoiceBaseline, f: AcousticFeatures): AcousticComparison {
        if (!b.valid) return AcousticComparison(false, f.issues, f.readingSec, summary = listOf("Pas de lecture de départ fiable : comparaison impossible."))
        if (!f.usable) return AcousticComparison(false, f.issues, f.readingSec, b.readingSec, summary = listOf("Capture inutilisable : " + f.issues.joinToString(", ") { it.label.lowercase() } + "."))
        val pct = ((f.readingSec / b.readingSec - 1) * 100).roundToInt()
        val dp = (f.pauses - b.pauses).roundToInt()
        val ds = r2(f.pauseSec - b.pauseSec)
        val dfrac = r2(f.speechFraction - b.speechFraction)
        val lines = ArrayList<String>()
        when {
            pct >= cfg.DURATION_CHANGE_PCT -> lines += "Lecture plus longue de $pct % que la lecture de départ."
            pct <= -cfg.DURATION_CHANGE_PCT -> lines += "Lecture plus rapide de ${-pct} % que la lecture de départ."
        }
        if (dp >= cfg.PAUSES_DELTA || ds >= cfg.PAUSE_SEC_DELTA) lines += "Davantage de pauses que lors de la lecture de départ ($dp de plus, ${Fmt.kmh(max(0.0, ds))} s de silence en plus)."
        if (-dfrac >= cfg.SPEECH_FRACTION_DROP) lines += "Moins de temps de parole dans la lecture (${(-dfrac * 100).roundToInt()} points de moins)."
        val changed = pct >= cfg.DURATION_CHANGE_PCT || dp >= cfg.PAUSES_DELTA || ds >= cfg.PAUSE_SEC_DELTA || -dfrac >= cfg.SPEECH_FRACTION_DROP
        if (!changed) lines += "Lecture proche de la lecture de départ."
        return AcousticComparison(true, emptyList(), f.readingSec, r2(b.readingSec), pct, dp, ds, dfrac, changed, lines)
    }

    /** Fin de lecture probable : de la parole a été entendue, puis `silenceSec` de silence. */
    fun readingEnded(frames: DoubleArray, noiseDb: Double, silenceSec: Double = 1.5): Boolean {
        if (frames.isEmpty()) return false
        // Bruit de fond local (tapis en marche) s'il dépasse le bruit ambiant mesuré avant le mouvement.
        val sorted = frames.sorted()
        val noise = max(noiseDb, sorted[(sorted.size * cfg.NOISE_PERCENTILE).toInt().coerceIn(0, sorted.size - 1)])
        val segs = speechSegments(frames, noise)
        val last = segs.lastOrNull() ?: return false
        val speech = segs.sumOf { it.last - it.first + 1 } * cfg.FRAME_MS / 1000.0
        val silence = (frames.size - 1 - last.last) * cfg.FRAME_MS / 1000.0
        return speech >= cfg.MIN_READING_SEC * 0.6 && silence >= silenceSec
    }

    fun clamp(v: Double, lo: Double, hi: Double) = min(hi, max(lo, v))
}
