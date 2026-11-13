package fr.relance.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.relance.app.BuildConfig
import fr.relance.app.core.Catalog
import fr.relance.app.core.Catalogs
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Récupère le catalogue publié par le coach (interface d'administration sur Netlify) et le garde sur le téléphone.
 * Rien n'est envoyé : la requête ne contient aucune donnée du sportif.
 */
class CatalogSync(private val dir: File, private val fixedUrl: String? = null) {
    private val file = File(dir, "catalog.json")
    private val urlFile = File(dir, "catalog-url.txt")

    /** Adresse du catalogue : celle réglée dans le profil, sinon celle de cette version de l'app. */
    val url: String get() = fixedUrl ?: urlFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.startsWith("http") } ?: BuildConfig.CATALOG_URL

    fun setUrl(value: String) {
        val v = value.trim()
        if (v.isBlank() || v == BuildConfig.CATALOG_URL) urlFile.delete() else urlFile.writeText(v)
        metaFile.delete() // nouvelle source : on oublie l'ETag de l'ancienne
    }

    private val metaFile = File(dir, "catalog-meta.json")

    @Serializable
    data class Meta(val etag: String = "", val checkedAt: Long = 0, val updatedAt: Long = 0)

    sealed interface Result {
        data class Updated(val catalog: Catalog) : Result
        data class UpToDate(val version: Int) : Result
        data class Failed(val message: String) : Result
    }

    fun meta(): Meta = try {
        if (metaFile.exists()) Catalogs.json.decodeFromString(Meta.serializer(), metaFile.readText()) else Meta()
    } catch (e: Exception) {
        Meta()
    }

    private fun saveMeta(m: Meta) = metaFile.writeText(Catalogs.json.encodeToString(Meta.serializer(), m))

    /** Dernier catalogue téléchargé, s'il est lisible et valide. */
    fun loadCached(): Catalog? = try {
        if (file.exists()) Catalogs.parse(file.readText()).takeIf { Catalogs.check(it) == null } else null
    } catch (e: Exception) {
        null
    }

    fun fetch(now: Long = System.currentTimeMillis()): Result {
        val m = meta()
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                if (m.etag.isNotBlank() && file.exists()) setRequestProperty("If-None-Match", m.etag)
            }
            try {
                when (c.responseCode) {
                    304 -> {
                        saveMeta(m.copy(checkedAt = now))
                        Result.UpToDate(loadCached()?.version ?: Catalogs.current.version)
                    }
                    200 -> {
                        val text = c.inputStream.bufferedReader().use { it.readText() }
                        val catalog = Catalogs.parse(text)
                        Catalogs.check(catalog)?.let { return Result.Failed(it) }
                        val previous = loadCached()?.version ?: Catalogs.builtin.version
                        file.writeText(text)
                        saveMeta(Meta(c.getHeaderField("ETag") ?: "", now, now))
                        if (catalog.version == previous && file.exists()) Result.UpToDate(catalog.version) else Result.Updated(catalog)
                    }
                    else -> Result.Failed("Le serveur des programmes a répondu ${c.responseCode}.")
                }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            Result.Failed("Pas de connexion au serveur des programmes.")
        }
    }

    companion object {
        private const val WORK = "relance-catalog-sync"

        /** Vérification une fois par jour, quand le téléphone a du réseau. */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<CatalogWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

class CatalogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = when (val r = CatalogSync(applicationContext.filesDir).fetch()) {
        is CatalogSync.Result.Updated -> {
            Catalogs.current = r.catalog
            Result.success()
        }
        is CatalogSync.Result.UpToDate -> Result.success()
        is CatalogSync.Result.Failed -> Result.retry()
    }
}
