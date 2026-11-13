package fr.relance.app

import fr.relance.app.core.Catalogs
import fr.relance.app.core.Coach
import fr.relance.app.core.Feedback
import fr.relance.app.core.Level
import fr.relance.app.core.Profile
import fr.relance.app.core.ProgramType
import fr.relance.app.data.CatalogSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.nio.file.Files
import java.time.LocalDate

class CatalogSyncTest {
    private val today = LocalDate.of(2026, 11, 12).toEpochDay()
    private val prof = Profile(age = 42, vr = 5.8, level = Level.N2, days = setOf(1, 3, 6))

    /** Catalogue publié par le coach : séance de marche du premier palier allongée, nouvelle version. */
    private fun edited(version: Int) = Catalogs.builtin.let { c ->
        c.copy(version = version, programs = c.programs.map { p ->
            if (p.id != "run-30") p else p.copy(name = "Courir 30 min (v2)", weekNotes = mapOf("1" to "On démarre en douceur."),
                steps = p.steps.mapIndexed { i, s -> if (i == 1) s.copy(sessions = listOf(s.sessions[0].copy(params = mapOf("minutes" to 22.0)))) else s })
        })
    }

    @Test fun anUpdateAppliesNowAtTheStartOfAWeekOtherwiseNextWeek() {
        val p = Coach.newProgram(ProgramType.RUN_30, prof, today)
        val c2 = edited(2)
        assertTrue(Coach.hasUpdate(p, c2))
        assertFalse(Coach.hasUpdate(p, Catalogs.builtin))
        // Début de semaine : appliqué tout de suite.
        val now = Coach.scheduleUpdate(p, prof, c2)
        assertNull(now.pending)
        assertEquals("Courir 30 min (v2)", now.name)
        assertEquals(2, now.snapshot!!.catalogVersion)
        assertEquals(22.0, now.steps[0].sessions[0].params["minutes"]!!, 0.0) // le palier réservé aux N1 est écarté pour un N2
        assertFalse(Coach.hasUpdate(now, c2))
        // En cours de semaine : en attente, puis appliqué au passage à la semaine suivante.
        val planned = Coach.sessionFor(p, prof, today)
        val mid = Coach.applyFeedback(p, prof, planned, Feedback(rpe = 5), today).program
        val waiting = Coach.scheduleUpdate(mid, prof, c2)
        assertNotNull(waiting.pending)
        assertEquals("Courir 30 min", waiting.name)
        assertFalse(Coach.hasUpdate(waiting, c2))
        var q = waiting
        while (q.week == 0) q = Coach.advance(q, prof)
        assertNull(q.pending)
        assertEquals("Courir 30 min (v2)", q.name)
        // Réadapté au profil de départ, même si le sportif a progressé entre-temps.
        val later = Coach.scheduleUpdate(p, prof.copy(longestRunMin = 20, level = Level.N4), c2)
        assertEquals(now.steps, later.steps)
    }

    @Test fun anIncompleteCatalogIsRefused() {
        assertNull(Catalogs.check(Catalogs.builtin))
        val broken = Catalogs.builtin.copy(sessionTypes = Catalogs.builtin.sessionTypes.filter { it.id != "intervals" })
        assertTrue(Catalogs.check(broken)!!.contains("intervals"))
        assertNotNull(Catalogs.check(Catalogs.builtin.copy(schema = 2)))
        assertNotNull(Catalogs.check(Catalogs.builtin.copy(programs = emptyList())))
    }

    @Test fun downloadsTheCatalogAndHonoursTheETag() {
        val body = Catalogs.json.encodeToString(fr.relance.app.core.Catalog.serializer(), edited(3)).toByteArray()
        var hits = 0
        // Petit serveur HTTP : renvoie le catalogue avec un ETag, ou 304 si le téléphone a déjà cette version.
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (e: Exception) { break }
                sock.use { so ->
                    val reader = so.getInputStream().bufferedReader()
                    val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    hits++
                    val out = so.getOutputStream()
                    if (headers.any { it.equals("If-None-Match: \"v3\"", ignoreCase = true) }) {
                        out.write("HTTP/1.1 304 Not Modified\r\nETag: \"v3\"\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nETag: \"v3\"\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body)
                    }
                    out.flush()
                }
            }
        }
        try {
            val dir = Files.createTempDirectory("catalog").toFile()
            val sync = CatalogSync(dir, "http://127.0.0.1:${server.localPort}/api/catalog")
            val first = sync.fetch(1000)
            assertTrue(first is CatalogSync.Result.Updated)
            assertEquals(3, (first as CatalogSync.Result.Updated).catalog.version)
            assertEquals(3, sync.loadCached()!!.version)
            val second = sync.fetch(2000)
            assertTrue(second is CatalogSync.Result.UpToDate)
            assertEquals(2000, sync.meta().checkedAt)
            assertEquals(2, hits)
            val offline = CatalogSync(dir, "http://127.0.0.1:1/api/catalog").fetch()
            assertTrue(offline is CatalogSync.Result.Failed)
            assertEquals(3, sync.loadCached()!!.version) // le catalogue en cache reste utilisable
        } finally {
            server.close()
        }
    }
}
