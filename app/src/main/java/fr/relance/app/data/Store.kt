package fr.relance.app.data

import fr.relance.app.core.AppState
import fr.relance.app.core.Sample
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Stockage local : un fichier JSON pour l'état, un fichier par séance pour les mesures seconde par seconde. */
class Store(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateFile = File(dir, "state.json")
    private val samplesDir = File(dir, "samples")

    fun load(): AppState = try {
        if (stateFile.exists()) json.decodeFromString(AppState.serializer(), stateFile.readText()) else AppState()
    } catch (e: Exception) {
        // Fichier illisible : on le met de côté plutôt que de l'écraser.
        stateFile.copyTo(File(dir, "state-illisible-${System.currentTimeMillis()}.json"), overwrite = true)
        AppState()
    }

    @Synchronized
    fun save(state: AppState) {
        dir.mkdirs()
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(json.encodeToString(AppState.serializer(), state))
        if (!tmp.renameTo(stateFile)) {
            stateFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun saveSamples(id: String, samples: List<Sample>) {
        samplesDir.mkdirs()
        File(samplesDir, "$id.json").writeText(json.encodeToString(ListSerializer(Sample.serializer()), samples))
    }

    fun loadSamples(id: String): List<Sample> = try {
        val f = File(samplesDir, "$id.json")
        if (f.exists()) json.decodeFromString(ListSerializer(Sample.serializer()), f.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}
