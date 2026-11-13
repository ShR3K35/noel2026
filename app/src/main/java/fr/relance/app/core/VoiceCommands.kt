package fr.relance.app.core

import java.text.Normalizer

/** Question posée à voix haute : chaque réponse est interprétée dans ce contexte seulement. */
enum class Question(val prompt: String, val retry: String) {
    SPEECH("Parler était-il confortable ? Oui, incertain ou non.", "Je n'ai pas compris. Réponds oui, incertain ou non."),
    RPE("Ton effort, de zéro à dix ?", "Je n'ai pas compris. Donne un seul chiffre, de zéro à dix."),
    MOVEMENT("Ton déplacement était-il naturel et confortable ? Oui ou non.", "Je n'ai pas compris. Réponds oui ou non."),
    EXTRA("Ta lecture semblait moins fluide qu'au départ. Parler reste-t-il vraiment confortable ? Oui, incertain ou non.", "Réponds oui, incertain ou non."),
}

sealed interface Heard {
    data class Comfort(val value: Speech) : Heard
    data class Effort(val value: Int) : Heard
    data class YesNo(val value: Boolean) : Heard
    data object Stop : Heard
    data object SlowDown : Heard
    data object Pause : Heard
    data object Repeat : Heard
    /** Plusieurs réponses incompatibles entendues : on redemande, de façon ciblée. */
    data class Ambiguous(val heard: String) : Heard
    data object Nothing : Heard
}

/** Transcription → réponse ou commande, selon la question en cours. */
object VoiceCommands {
    /** Vocabulaire limité donné à la reconnaissance (Vosk, mode grammaire) : moins d'erreurs, et le reste devient [unk]. */
    val GRAMMAR: List<String> = listOf(
        "oui", "non", "incertain", "je ne sais pas", "peut-être", "bof",
        "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
        "arrêter", "arrête", "stop", "ralentir", "ralentis", "pause", "répète", "répéter", "[unk]",
    )

    private val numbers = mapOf(
        "zero" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6,
        "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10,
    )

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace("[unk]", " ").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun parse(text: String, q: Question?): Heard {
        val t = normalize(text)
        if (t.isBlank()) return Heard.Nothing
        val w = t.split(' ')
        // Commandes : valables à tout moment, prioritaires.
        if (w.any { it == "arreter" || it == "arrete" || it == "stop" }) return Heard.Stop
        if (w.any { it == "ralentir" || it == "ralentis" }) return Heard.SlowDown
        if ("pause" in w) return Heard.Pause
        if (w.any { it == "repete" || it == "repeter" }) return Heard.Repeat
        return when (q) {
            Question.SPEECH, Question.EXTRA -> {
                val found = buildSet {
                    if ("oui" in w) add(Speech.YES)
                    if ("non" in w) add(Speech.NO)
                    if ("incertain" in w || "bof" in w || t.contains("peut etre") || t.contains("sais pas")) add(Speech.UNSURE)
                }
                // « je ne sais pas » contient « ne » mais pas « non » : pas d'ambiguïté.
                when (found.size) {
                    0 -> Heard.Nothing
                    1 -> Heard.Comfort(found.first())
                    else -> Heard.Ambiguous(t)
                }
            }
            Question.RPE -> {
                val found = w.mapNotNull { numbers[it] ?: it.toIntOrNull()?.takeIf { n -> n in 0..10 } }.distinct()
                when (found.size) {
                    0 -> Heard.Nothing
                    1 -> Heard.Effort(found.first())
                    else -> Heard.Ambiguous(t)
                }
            }
            Question.MOVEMENT -> {
                val yes = "oui" in w
                val no = "non" in w
                when {
                    yes && no -> Heard.Ambiguous(t)
                    yes -> Heard.YesNo(true)
                    no -> Heard.YesNo(false)
                    else -> Heard.Nothing
                }
            }
            null -> Heard.Nothing
        }
    }
}
