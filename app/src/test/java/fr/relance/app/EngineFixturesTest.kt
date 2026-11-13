package fr.relance.app

import fr.relance.app.core.Catalogs
import fr.relance.app.core.Coach
import fr.relance.app.core.Level
import fr.relance.app.core.Profile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Écrit les plannings calculés par le moteur de l'app pour un jeu de profils, dans web/src/engine/fixtures.json.
 * Le moteur du simulateur web (TypeScript) doit retrouver exactement les mêmes : voir web/src/engine/engine.test.ts.
 */
class EngineFixturesTest {
    private val today = LocalDate.of(2026, 11, 12).toEpochDay()

    private val profiles = listOf(
        "debutant-n1" to Profile(age = 55, weightKg = 92.0, level = Level.N1, vr = 4.6, days = setOf(1, 3, 6), minutesPerSession = 30),
        "marcheur-n2" to Profile(age = 42, level = Level.N2, vr = 5.8, days = setOf(1, 3, 6)),
        "n3-sans-pente" to Profile(age = 35, level = Level.N3, vr = 6.0, days = setOf(2, 4, 6, 7), inclinePct = 0.0),
        "n4-deux-jours" to Profile(age = 30, level = Level.N4, vr = 6.4, days = setOf(3, 7), minutesPerSession = 45),
        "coureur-30" to Profile(age = 38, level = Level.N3, vr = 6.2, longestRunMin = 30, days = setOf(1, 2, 3, 4, 5, 6, 7)),
        "coureur-75" to Profile(age = 33, level = Level.N4, vr = 6.8, longestRunMin = 75, days = setOf(1, 3, 5), minutesPerSession = 45),
        "avis-medical" to Profile(age = 67, level = Level.N1, vr = 4.2, healthFlag = true, days = setOf(2, 5), minutesPerSession = 20),
        "un-jour-15min" to Profile(age = 48, level = Level.N2, vr = 5.2, days = setOf(4), minutesPerSession = 15, treadmillMaxKmh = 10.0),
    )

    @Test
    fun writeFixtures() {
        val catalog = Catalogs.builtin
        val cases = buildJsonArray {
            for ((name, prof) in profiles) for (t in catalog.visiblePrograms) {
                if (Coach.lockedReason(t, prof) != null) continue
                val p = Coach.newProgram(t, prof, today, catalog)
                add(buildJsonObject {
                    put("name", "$name/${t.id}")
                    put("program", t.id)
                    put("startDay", today)
                    put("profile", buildJsonObject {
                        put("age", prof.age); put("weightKg", prof.weightKg); put("level", prof.level.name); put("vr", prof.vr)
                        put("longestRunMin", prof.longestRunMin); put("healthFlag", prof.healthFlag)
                        putJsonArray("days") { prof.days.sorted().forEach { add(JsonPrimitive(it)) } }
                        put("minutesPerSession", prof.minutesPerSession); put("inclinePct", prof.inclinePct); put("treadmillMaxKmh", prof.treadmillMaxKmh)
                    })
                    put("weeks", p.weeksPlanned)
                    put("steps", p.steps.size)
                    put("plan", JsonArray(Coach.plan(p, prof, today).map { s ->
                        buildJsonObject {
                            put("day", s.day); put("kind", s.kind.name); put("title", s.title); put("detail", s.detail)
                            put("isKey", s.isKey); put("week", s.week); put("deload", s.deload)
                            put("blocks", JsonArray(s.blocks.map { b ->
                                buildJsonArray { add(JsonPrimitive(b.kind.name)); add(JsonPrimitive(b.seconds)); add(JsonPrimitive(b.speedKmh)); add(JsonPrimitive(b.inclinePct)) }
                            }))
                        }
                    }))
                })
            }
        }
        assertTrue(cases.size > 40)
        File("../web/src/engine/fixtures.json").writeText(cases.toString())
    }
}
