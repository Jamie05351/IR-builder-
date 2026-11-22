package jdsp.irbuilder

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FdwTest {
    private val freqs = geomspace(40.0, 16000.0, 600)

    private fun m(path: String) = Measurement(path, Seat.DRIVER, "c", Channel.L, Band.COMBINED, "r")

    @Test
    fun aPureDelayStaysExact() {
        val x = DoubleArray(48000).also { it[480] = 1.0 }
        val h = Fdw.spectrum(x, 48000, 0.0, Fdw.onset(x, 48000), 8.0, freqs)
        for (i in freqs.indices) {
            assertEquals(1.0, h.abs(i), 1e-6)
            assertEquals(0.0, wrap(h.arg(i) + 2 * PI * freqs[i] * 0.01), 1e-6)
        }
    }

    @Test
    fun aReflectionIsWindowedOutAboveAFewKilohertzButKeptInTheBass() {
        // Direct sound at 10 ms, a reflection half as loud 3 ms later: a ±3.5 dB comb everywhere.
        val x = DoubleArray(48000).also { it[480] = 1.0; it[480 + 144] = 0.5 }
        val h = Fdw.spectrum(x, 48000, 0.0, Fdw.onset(x, 48000), 8.0, freqs)
        fun rippleDb(lo: Double, hi: Double): Double {
            val db = freqs.indices.filter { freqs[it] in lo..hi }.map { 20 * log10(h.abs(it)) }
            return db.max() - db.min()
        }
        assertTrue(rippleDb(5000.0, 16000.0) < 0.5, "high-frequency ripple ${rippleDb(5000.0, 16000.0)} dB")
        assertTrue(rippleDb(60.0, 400.0) > 6.0, "bass keeps the room: ${rippleDb(60.0, 400.0)} dB")
    }

    private fun rewImpulse(samples: DoubleArray, startTime: Double, extraHeader: String = "", normalisedPeak: Double? = null): String {
        val sb = StringBuilder("* Impulse Response data saved by REW V5.40 Beta 135\n")
        sb.append(if (normalisedPeak != null) "* IR is normalised\n" else "* IR is not normalised\n")
        sb.append("* IR window has not been applied\n* IR is not the min phase version\n").append(extraHeader)
        normalisedPeak?.let { sb.append("$it // Peak value before normalisation\n") }
        sb.append("0 // Peak index\n${samples.size} // Response length\n2.0833333333333333E-5 // Sample interval (seconds)\n")
        sb.append("$startTime // Start time (seconds)\n0.0 // Data offset (dB)\n* Data start\n")
        for (v in samples) sb.append(v).append('\n')
        return sb.toString()
    }

    @Test
    fun rewImpulseTextKeepsItsTimeBaseAndLevel() {
        val dir = createTempDirectory("fdw").toFile()
        // t = 0 at sample 240 (start time -5 ms); the arrival 2 ms after it.
        val x = DoubleArray(24000).also { it[240 + 96] = 0.25 }
        val file = File(dir, "ir.txt").apply { writeText(rewImpulse(x.map { it / 0.25 }.toDoubleArray(), -0.005, normalisedPeak = 0.25)) }
        val r = Importer.read(m(file.path), freqs, 48000, windowCycles = 8)
        assertEquals(SourceKind.REW_IMPULSE_TEXT, r.kind)
        assertEquals(96, r.impulsePeakIndex)
        for (i in freqs.indices) {
            assertEquals(0.25, r.response.abs(i), 1e-6)
            assertEquals(0.0, wrap(r.response.arg(i) + 2 * PI * freqs[i] * 0.002), 1e-6)
        }
        // Wrong rate, windowed and min-phase exports are refused.
        val windowed = File(dir, "w.txt").apply { writeText(rewImpulse(x, -0.005).replace("IR window has not been applied", "IR window has been applied")) }
        assertTrue(assertFailsWith<IllegalArgumentException> { Importer.read(m(windowed.path), freqs, 48000) }.message!!.contains("window"))
        val minPhase = File(dir, "p.txt").apply { writeText(rewImpulse(x, -0.005).replace("IR is not the min phase version", "IR is the min phase version")) }
        assertTrue(assertFailsWith<IllegalArgumentException> { Importer.read(m(minPhase.path), freqs, 48000) }.message!!.contains("minimum-phase"))
        assertFailsWith<IllegalArgumentException> { Importer.read(m(file.path), freqs, 44100) }
    }

    @Test
    fun anArrivalBeforeTheReferenceIsKept() {
        // An acoustic timing reference can put the direct sound before t = 0 (REW's negative delay).
        val dir = createTempDirectory("fdw").toFile()
        val x = DoubleArray(24000).also { it[2400 - 192] = 1.0 }
        val file = File(dir, "early.txt").apply { writeText(rewImpulse(x, -0.05)) }
        val r = Importer.read(m(file.path), freqs, 48000, windowCycles = 8)
        assertEquals(-192, r.impulsePeakIndex)
        for (i in freqs.indices) assertEquals(0.0, wrap(r.response.arg(i) - 2 * PI * freqs[i] * 0.004), 1e-6)
    }
}
