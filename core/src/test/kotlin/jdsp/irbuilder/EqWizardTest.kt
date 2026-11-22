package jdsp.irbuilder

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EqWizardTest {
    private val fs = 48000.0

    /** Combined sweeps: flat, a broad +6 dB hump at 200 Hz, a narrow −15 dB null at 1 kHz. */
    private fun sweeps(rightExtraDb: Double = 0.0): List<Measurement> {
        val dir = createTempDirectory("eq").toFile()
        val hump = EqBand(200.0, 6.0, 1.0)
        val nul = EqBand(1000.0, -15.0, 12.0)
        val out = ArrayList<Measurement>()
        for (seat in Seat.entries) for (pos in listOf("c", "p2")) for (ch in Channel.entries) {
            val file = File(dir, "${seat.name}_${pos}_${ch.name}_combined.txt")
            val extra = if (ch == Channel.R) EqBand(3000.0, rightExtraDb, 2.0) else null
            Fixtures.writeRew(file, { hz ->
                var db = 80 + EqWizard.peakDb(hump, hz, fs) + EqWizard.peakDb(nul, hz, fs)
                if (extra != null && extra.gainDb != 0.0) db += EqWizard.peakDb(extra, hz, fs)
                val m = Math.pow(10.0, db / 20)
                Fixtures.C(m * kotlin.math.cos(-2 * Math.PI * hz * 0.003), m * kotlin.math.sin(-2 * Math.PI * hz * 0.003))
            })
            out += Measurement(file.path, seat, pos, ch, Band.COMBINED, "ref-${ch.name}")
        }
        return out
    }

    private fun solve(ms: List<Measurement>, o: EqOptions): EqResult {
        val d = DesignOptions(irMode = IrMode.STEREO, stereoMinHz = o.minHz, stereoMaxHz = o.maxHz)
        val set = MeasurementSet.load(ms, d, emptySet())
        assertTrue(set.loadErrors.isEmpty(), set.loadErrors.joinToString { it.message })
        return EqWizard.solve(set, o)
    }

    @Test
    fun aBroadPeakIsCutAndANarrowNullIsNotBoosted() {
        val r = solve(sweeps(), EqOptions(bassLiftDb = 0.0, trebleTiltDb = 0.0))
        for (s in r.sides) {
            val cut = s.bands.filter { it.hz in 140.0..280.0 && it.gainDb < -3 }
            assertTrue(cut.isNotEmpty(), "${s.side}: ${s.bands}")
            // At the 1 kHz null the EQ adds at most a little: the octave-wide response barely misses it.
            val i = r.freqs.indices.minBy { abs(r.freqs[it] - 1000.0) }
            assertTrue(s.eqDb[i] < 2.0, "${s.side}: ${s.eqDb[i]} dB of boost into the null")
            assertTrue(s.rmsAfterDb < s.rmsBeforeDb * 0.6, "${s.side}: ${s.rmsBeforeDb} -> ${s.rmsAfterDb}")
            assertTrue(s.bands.all { it.gainDb <= 4.0 && it.q in 0.3..8.0 && (it.gainDb <= 0 || it.q <= 3.0) })
        }
        assertEquals(listOf(EqSide.LEFT, EqSide.RIGHT), r.sides.map { it.side })
    }

    @Test
    fun linkedFitsOneSetForBothSidesAndPerSideSeesTheirDifference() {
        val linked = solve(sweeps(rightExtraDb = 6.0), EqOptions(bassLiftDb = 0.0, trebleTiltDb = 0.0, linked = true))
        assertEquals(listOf(EqSide.BOTH), linked.sides.map { it.side })
        val split = solve(sweeps(rightExtraDb = 6.0), EqOptions(bassLiftDb = 0.0, trebleTiltDb = 0.0))
        val right = split.sides.single { it.side == EqSide.RIGHT }
        val left = split.sides.single { it.side == EqSide.LEFT }
        assertTrue(right.bands.any { it.hz in 2000.0..4500.0 && it.gainDb < -3 }, "${right.bands}")
        assertTrue(left.bands.none { it.hz in 2000.0..4500.0 && it.gainDb < -3 }, "${left.bands}")
    }

    @Test
    fun theExportIsWhatJdspImports() {
        val r = EqResult(DoubleArray(0), EqOptions(), listOf(
            EqSideResult(EqSide.LEFT, DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), emptyMap(), emptyMap(), listOf(EqBand(200.0, -6.2, 1.25, EqSide.LEFT)), 0.0, 0.0),
            EqSideResult(EqSide.RIGHT, DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), emptyMap(), emptyMap(), listOf(EqBand(3150.0, 2.5, 2.0, EqSide.RIGHT)), 0.0, 0.0),
        ), preampDb = -2.5, warnings = emptyList())
        val text = EqWizard.apoText(r)
        assertEquals("Preamp: -2.5 dB\nChannel: L\nFilter 1: ON PK Fc 200 Hz Gain -6.2 dB Q 1.25\nChannel: R\nFilter 2: ON PK Fc 3150 Hz Gain 2.5 dB Q 2\n", text)
        // J_DSP's own reader (ParametricEqBandList.fromApoString).
        val filter = Regex("""Filter\s+\d+:\s+ON\s+(\S+)\s+Fc\s+([-+]?\d*\.?\d+)\s+Hz\s+Gain\s+([-+]?\d*\.?\d+)\s+dB\s+Q\s+([-+]?\d*\.?\d+)""", RegexOption.IGNORE_CASE)
        assertEquals(2, text.lines().count { filter.containsMatchIn(it) })
    }

    @Test
    fun aPeakingBandHasItsGainAtItsCentre() {
        assertEquals(-6.0, EqWizard.peakDb(EqBand(1000.0, -6.0, 2.0), 1000.0, fs), 1e-9)
        assertEquals(0.0, EqWizard.peakDb(EqBand(1000.0, -6.0, 2.0), 20.0, fs), 0.01)
    }
}
