package fr.relance.app.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Export d'une séance : TCX (accepté par Strava, Garmin Connect et la plupart des plateformes) et CSV. */
object Export {
    private fun iso(millis: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis / 1000 * 1000))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun fileName(s: CompletedSession, ext: String): String {
        val d = java.time.LocalDate.ofEpochDay(s.day)
        return "relance-$d-${s.kind.name.lowercase()}.$ext"
    }

    fun tcx(s: CompletedSession, samples: List<Sample>): String {
        val sb = StringBuilder()
        val start = iso(s.startMillis)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\" ")
        sb.append("xmlns:ns3=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\">\n")
        sb.append(" <Activities>\n  <Activity Sport=\"Running\">\n   <Id>").append(start).append("</Id>\n")
        sb.append("   <Lap StartTime=\"").append(start).append("\">\n")
        sb.append("    <TotalTimeSeconds>").append(s.durationSec).append("</TotalTimeSeconds>\n")
        sb.append("    <DistanceMeters>").append(String.format(Locale.US, "%.1f", s.distanceM)).append("</DistanceMeters>\n")
        sb.append("    <MaximumSpeed>").append(String.format(Locale.US, "%.3f", s.maxSpeed / 3.6)).append("</MaximumSpeed>\n")
        sb.append("    <Calories>").append(s.calories).append("</Calories>\n")
        if (s.avgHr > 0) {
            sb.append("    <AverageHeartRateBpm><Value>").append(s.avgHr).append("</Value></AverageHeartRateBpm>\n")
            sb.append("    <MaximumHeartRateBpm><Value>").append(s.maxHr).append("</Value></MaximumHeartRateBpm>\n")
        }
        sb.append("    <Intensity>Active</Intensity>\n    <TriggerMethod>Manual</TriggerMethod>\n    <Track>\n")
        var dist = 0.0
        for (p in samples) {
            dist += p.speed / 3.6
            sb.append("     <Trackpoint><Time>").append(iso(s.startMillis + p.t * 1000L)).append("</Time>")
            sb.append("<DistanceMeters>").append(String.format(Locale.US, "%.1f", dist)).append("</DistanceMeters>")
            if (p.hr > 0) sb.append("<HeartRateBpm><Value>").append(p.hr).append("</Value></HeartRateBpm>")
            sb.append("<Extensions><ns3:TPX><ns3:Speed>").append(String.format(Locale.US, "%.3f", p.speed / 3.6))
            sb.append("</ns3:Speed></ns3:TPX></Extensions></Trackpoint>\n")
        }
        sb.append("    </Track>\n   </Lap>\n")
        sb.append("   <Notes>").append(esc("${s.title} · ${s.detail}")).append("</Notes>\n")
        sb.append("   <Creator xsi:type=\"Device_t\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><Name>Relance</Name>")
        sb.append("<UnitId>0</UnitId><ProductID>0</ProductID><Version><VersionMajor>0</VersionMajor><VersionMinor>1</VersionMinor>")
        sb.append("<BuildMajor>0</BuildMajor><BuildMinor>0</BuildMinor></Version></Creator>\n")
        sb.append("  </Activity>\n </Activities>\n</TrainingCenterDatabase>\n")
        return sb.toString()
    }

    fun csv(samples: List<Sample>): String {
        val sb = StringBuilder("temps_s;vitesse_kmh;fc_bpm;inclinaison_pct;distance_m\n")
        var dist = 0.0
        for (p in samples) {
            dist += p.speed / 3.6
            sb.append(p.t).append(';').append(String.format(Locale.US, "%.1f", p.speed)).append(';')
            sb.append(if (p.hr > 0) p.hr.toString() else "").append(';')
            sb.append(String.format(Locale.US, "%.1f", p.incline)).append(';')
            sb.append(String.format(Locale.US, "%.1f", dist)).append('\n')
        }
        return sb.toString()
    }
}

/** Décodage des trames Bluetooth standard : ceinture cardio (0x2A37) et tapis FTMS (0x2ACD). */
object BleParsers {
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()

    /** Fréquence cardiaque en bpm, ou null si la trame est invalide. */
    fun heartRate(b: ByteArray): Int? {
        if (b.size < 2) return null
        val wide = (u8(b, 0) and 0x01) != 0
        if (wide && b.size < 3) return null
        return if (wide) u16(b, 1) else u8(b, 1)
    }

    data class Treadmill(val speedKmh: Double?, val inclinePct: Double?)

    /** Vitesse instantanée et inclinaison d'une trame « Treadmill Data ». */
    fun treadmill(b: ByteArray): Treadmill? {
        if (b.size < 2) return null
        val flags = u16(b, 0)
        var i = 2
        var speed: Double? = null
        var incline: Double? = null
        if (flags and 0x0001 == 0) {
            if (b.size < i + 2) return null
            speed = u16(b, i) / 100.0
            i += 2
        }
        if (flags and 0x0002 != 0) i += 2
        if (flags and 0x0004 != 0) i += 3
        if (flags and 0x0008 != 0 && b.size >= i + 2) incline = s16(b, i) / 10.0
        return Treadmill(speed, incline)
    }
}
