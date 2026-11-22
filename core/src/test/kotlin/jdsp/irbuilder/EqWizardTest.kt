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

    @Test
    fun aTrebleOnlyFitWithTrebleOnlySweepsLevelsToWhatWasMeasured() {
        // Flat Combined sweeps from 2.8 kHz up, fitted 4–16 kHz with no house curve: nothing to do.
        val dir = createTempDirectory("eq").toFile()
        val ms = ArrayList<Measurement>()
        for (seat in Seat.entries) for (ch in Channel.entries) {
            val file = File(dir, "${seat.name}_${ch.name}_combined.txt")
            Fixtures.writeRew(file, { hz -> Fixtures.C(kotlin.math.cos(-2 * Math.PI * hz * 0.003), kotlin.math.sin(-2 * Math.PI * hz * 0.003)) }, minHz = 2800.0)
            ms += Measurement(file.path, seat, "c", ch, Band.COMBINED, "ref-${ch.name}")
        }
        val r = solve(ms, EqOptions(minHz = 4000.0, maxHz = 16000.0, bassLiftDb = 0.0, trebleTiltDb = 0.0))
        for (s in r.sides) {
            assertTrue(s.bands.isEmpty(), "${s.side}: ${s.bands}")
            assertTrue(s.rmsBeforeDb < 0.5, "${s.side}: ${s.rmsBeforeDb}")
        }
    }

    @Test
    fun houseCurveFilesReadInTheUsualFormats() {
        val rew = HouseCurve.parse("* REW house curve\n20 6\n100 3.5\n1000 0\n10000 -4\n")
        assertEquals(listOf(CurvePoint(20.0, 6.0), CurvePoint(100.0, 3.5), CurvePoint(1000.0, 0.0), CurvePoint(10000.0, -4.0)), rew)
        val csv = HouseCurve.parse("Frequency,dB\n20.0,6.0\n1000.0,0.0\n")
        assertEquals(listOf(CurvePoint(20.0, 6.0), CurvePoint(1000.0, 0.0)), csv)
        val euro = HouseCurve.parse("20,0;6,0\n1000,0;0,0\n")
        assertEquals(listOf(CurvePoint(20.0, 6.0), CurvePoint(1000.0, 0.0)), euro)
        val spacedComma = HouseCurve.parse("# comment\n20,0 6,5 12,0\n1000,0 0,0 0,0 // phase ignored\n")
        assertEquals(listOf(CurvePoint(20.0, 6.5), CurvePoint(1000.0, 0.0)), spacedComma)
        // It round-trips through its own text.
        assertEquals(rew, HouseCurve.parse(HouseCurve.text(rew)))
        kotlin.test.assertFailsWith<IllegalArgumentException> { HouseCurve.parse("nothing here") }
    }

    @Test
    fun aCurveIsStraightInLogFrequencyAndFlatBeyondItsEnds() {
        val c = listOf(CurvePoint(100.0, 6.0), CurvePoint(1000.0, 0.0))
        assertEquals(3.0, HouseCurve.at(c, Math.sqrt(100.0 * 1000.0)), 1e-9)
        assertEquals(6.0, HouseCurve.at(c, 20.0), 0.0)
        assertEquals(0.0, HouseCurve.at(c, 15000.0), 0.0)
    }

    @Test
    fun theFitFollowsACustomCurve() {
        // Flat sweeps; the curve wants the treble 6 dB down above 5 kHz: the EQ cuts it.
        val dir = createTempDirectory("eq").toFile()
        val ms = ArrayList<Measurement>()
        for (seat in Seat.entries) for (ch in Channel.entries) {
            val file = File(dir, "${seat.name}_${ch.name}_combined.txt")
            Fixtures.writeRew(file, { hz -> Fixtures.C(kotlin.math.cos(-2 * Math.PI * hz * 0.003), kotlin.math.sin(-2 * Math.PI * hz * 0.003)) })
            ms += Measurement(file.path, seat, "c", ch, Band.COMBINED, "ref-${ch.name}")
        }
        val curve = listOf(CurvePoint(20.0, 0.0), CurvePoint(2000.0, 0.0), CurvePoint(5000.0, -6.0), CurvePoint(20000.0, -6.0))
        val r = solve(ms, EqOptions(curve = curve, useCurve = true, linked = true))
        val s = r.sides.single()
        val i = r.freqs.indices.minBy { abs(r.freqs[it] - 8000.0) }
        assertEquals(-6.0, s.eqDb[i], 1.0)
        val j = r.freqs.indices.minBy { abs(r.freqs[it] - 500.0) }
        assertEquals(0.0, s.eqDb[j], 1.0)
        assertTrue(s.rmsAfterDb < 1.0, "${s.rmsBeforeDb} -> ${s.rmsAfterDb}")
    }

    @Test
    fun aStackedBoostAndCutIsNotWhatTheFitterReturns() {
        // Asked for exactly what a wide boost plus a narrow cut 1/8 octave away would give (the
        // pair the old fitter returned on real sweeps), it finds a cleaner set: no opposing bands
        // within a third of an octave, for nearly the same fit.
        val f = geomspace(30.0, 16000.0, 600)
        val pair = listOf(EqBand(5966.0, 6.0, 0.3), EqBand(6445.0, -2.5, 8.0))
        val wanted = EqWizard.eqDb(pair, f, fs)
        val inFit = BooleanArray(f.size) { f[it] in 30.0..12000.0 }
        val bs = EqWizard.fit(f, wanted, inFit, EqOptions(maxBoostDb = 6.0), EqSide.BOTH, fs)
        for (a in bs) for (b in bs) if (a !== b && a.gainDb * b.gainDb < 0)
            assertTrue(abs(kotlin.math.log2(a.hz / b.hz)) >= 1.0 / 3, "opposing bands stacked: $a and $b in $bs")
        val idx = f.indices.filter { inFit[it] }
        val got = EqWizard.eqDb(bs, f, fs)
        val rms = Math.sqrt(idx.sumOf { (wanted[it] - got[it]).let { d -> d * d } } / idx.size)
        assertTrue(rms < 0.6, "fit RMS $rms with $bs")
    }

    @Test
    fun theOverlapChargeOnlyHitsFightingBands() {
        assertEquals(0.0, EqWizard.overlapPenalty(listOf(EqBand(100.0, -4.0, 1.0), EqBand(400.0, 4.0, 1.0))), 0.0)
        assertTrue(EqWizard.overlapPenalty(listOf(EqBand(75.0, -5.0, 2.0), EqBand(77.0, 4.0, 0.3))) > 2.0)
        assertEquals(0.0, EqWizard.overlapPenalty(listOf(EqBand(100.0, -4.0, 1.0), EqBand(200.0, -3.0, 1.0))), 0.0)
    }
}

