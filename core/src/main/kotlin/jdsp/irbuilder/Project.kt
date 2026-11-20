package jdsp.irbuilder

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A saved session: the measurement list with its tags, and the design settings. */
@Serializable
data class Project(
    val format: String = FORMAT,
    val version: Int = 1,
    val measurements: List<Measurement> = emptyList(),
    val options: DesignOptions = DesignOptions(),
    val notes: String = "",
) {
    companion object {
        const val FORMAT = "jdsp-ir-builder-project"
        const val EXTENSION = "jirb"
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

        fun load(file: File): Project {
            val p = json.decodeFromString(serializer(), file.readText())
            require(p.format == FORMAT) { "Not a J_DSP IR Builder project" }
            require(p.version == 1) { "Unsupported project version ${p.version}" }
            return p
        }

        fun save(p: Project, file: File) = file.writeText(json.encodeToString(serializer(), p))
    }
}

/** Fills in tags from names like `driver_pos2_L_mid.txt` or `Passenger Centre R combined.wav`. */
object FilenameTags {
    data class Tags(val seat: Seat?, val position: String?, val channel: Channel?, val band: Band?)

    private val seatWords = mapOf(
        "driver" to Seat.DRIVER, "drv" to Seat.DRIVER, "drive" to Seat.DRIVER, "ds" to Seat.DRIVER,
        "passenger" to Seat.PASSENGER, "pass" to Seat.PASSENGER, "psg" to Seat.PASSENGER, "pas" to Seat.PASSENGER, "ps" to Seat.PASSENGER,
    )
    private val channelWords = mapOf("l" to Channel.L, "left" to Channel.L, "lh" to Channel.L, "r" to Channel.R, "right" to Channel.R, "rh" to Channel.R)
    private val bandWords = mapOf(
        "low" to Band.LOW, "lo" to Band.LOW, "bass" to Band.LOW, "woofer" to Band.LOW, "sub" to Band.LOW,
        "mid" to Band.MID, "mids" to Band.MID, "midrange" to Band.MID,
        "high" to Band.HIGH, "hi" to Band.HIGH, "tweeter" to Band.HIGH, "tw" to Band.HIGH,
        "combined" to Band.COMBINED, "sum" to Band.COMBINED, "all" to Band.COMBINED, "full" to Band.COMBINED, "fullrange" to Band.COMBINED,
    )
    private val positionWords = setOf("centre", "center", "middle", "inner", "outer", "front", "rear", "back", "fwd", "up", "down", "head")
    private val positionPattern = Regex("(pos|p|mic|m|position)\\d+[a-z]?")

    fun parse(fileName: String): Tags {
        val tokens = fileName.substringBeforeLast('.').lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        var seat: Seat? = null
        var channel: Channel? = null
        var band: Band? = null
        val position = ArrayList<String>()
        for (t in tokens) {
            when {
                seat == null && t in seatWords -> seat = seatWords[t]
                band == null && t in bandWords -> band = bandWords[t]
                channel == null && t in channelWords -> channel = channelWords[t]
                t.matches(positionPattern) || t in positionWords -> position += t
            }
        }
        return Tags(seat, position.takeIf { it.isNotEmpty() }?.joinToString("-"), channel, band)
    }
}
