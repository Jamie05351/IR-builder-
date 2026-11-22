package jdsp.irbuilder

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdspFiltersTest {
    private val fs = 48000.0

    private fun dbAt(f: XoFilter, high: Boolean, hz: Double) =
        JdspFilters.response(f, high, doubleArrayOf(hz), fs).let { 20 * log10(it.abs(0)) }

    @Test
    fun eachSlopeHasItsLevelAtTheCorner() {
        for (t in listOf(XoType.BW1, XoType.BW2, XoType.BW3, XoType.BW4)) {
            assertEquals(-3.01, dbAt(XoFilter(150.0, t), false, 150.0), 0.05, "$t LP")
            assertEquals(-3.01, dbAt(XoFilter(150.0, t), true, 150.0), 0.05, "$t HP")
        }
        assertEquals(-6.02, dbAt(XoFilter(150.0, XoType.LR4), false, 150.0), 0.05)
        assertEquals(-6.02, dbAt(XoFilter(3500.0, XoType.LR4), true, 3500.0), 0.05)
    }

    @Test
    fun slopesFallAtTheirOrder() {
        for (t in XoType.entries) {
            val a = dbAt(XoFilter(100.0, t), false, 1600.0)
            val b = dbAt(XoFilter(100.0, t), false, 3200.0)
            assertEquals(-t.dbPerOctave.toDouble(), b - a, 0.6, "$t")
        }
    }

    @Test
    fun bw1PairIsExactlyComplementary() {
        // J_DSP's 1-pole TPT lowpass and highpass taps sum to exactly 1.
        val freqs = geomspace(20.0, 20000.0, 50)
        val lp = JdspFilters.response(XoFilter(300.0, XoType.BW1), false, freqs, fs)
        val hp = JdspFilters.response(XoFilter(300.0, XoType.BW1), true, freqs, fs)
        for (i in freqs.indices) {
            assertEquals(1.0, lp.re[i] + hp.re[i], 1e-9)
            assertEquals(0.0, lp.im[i] + hp.im[i], 1e-9)
        }
    }
}

class CrossoverFinderTest {
    private val fs = 48000.0

    /** A speaker path through the car: flat, 3 ms late, optionally its own factory filter. */
    private class Driver(val factory: List<Pair<XoFilter, Boolean>> = emptyList(), val delayMs: Double = 0.0)

    /** Writes REW exports of each band measured through J_DSP's [xo] crossovers. */
    private fun sweeps(
        xo: JdspCrossovers,
        drivers: Map<Band, Driver> = emptyMap(),
        bands: List<Band> = Band.BRANCHES,
        coverage: Map<Band, ClosedFloatingPointRange<Double>> = emptyMap(),
        seatExtraMs: (Seat) -> Double = { if (it == Seat.PASSENGER) 0.05 else 0.0 },
    ): List<Measurement> {
        val dir = createTempDirectory("xof").toFile()
        val out = ArrayList<Measurement>()
        for (seat in Seat.entries) for (pos in listOf("c", "p2")) for (ch in Channel.entries) for (b in bands) {
            val range = coverage[b] ?: (10.0..22000.0)
            val freqs = geomspace(range.start, range.endInclusive, 3000)
            val d = drivers[b] ?: Driver()
            var h = JdspFilters.response(xo.chain(b, fs), freqs, fs)
            for ((f, high) in d.factory) h = h.times(JdspFilters.response(f, high, freqs, fs))
            val wiggle = if (pos == "p2") 0.03 else 0.0
            h = h.delayed(freqs, (3.0 + d.delayMs + seatExtraMs(seat) + wiggle) / 1000.0)
            val file = File(dir, "${seat.name}_${pos}_${ch.name}_${b.fileName}.txt")
            val sb = StringBuilder("* Measurement data measured by REW V5.40\n* Smoothing: 1/48 octave\n* Freq(Hz) SPL(dB) Phase(degrees)\n")
            for (i in freqs.indices) {
                val m = 80 + 20 * log10(hypot(h.re[i], h.im[i]).coerceAtLeast(1e-9))
                sb.append("%.6f %.6f %.6f\n".format(java.util.Locale.ROOT, freqs[i], m, Math.toDegrees(atan2(h.im[i], h.re[i]))))
            }
            file.writeText(sb.toString())
            out += Measurement(file.path, seat, pos, ch, b, "ref-${ch.name}")
        }
        return out
    }

    private fun run(ms: List<Measurement>, f: FinderOptions): FinderReport {
        val o = DesignOptions(lowMidHz = f.current.lowMidHz, midHighHz = f.current.midHighHz)
        val set = MeasurementSet.load(ms, o, f.crossovers.toSet())
        assertTrue(set.loadErrors.isEmpty(), set.loadErrors.joinToString { it.message })
        return CrossoverFinder.solve(set, f)
    }

    @Test
    fun aMatchedLr4CrossoverIsLeftAlone() {
        val xo = JdspCrossovers()
        val r = run(sweeps(xo), FinderOptions(current = xo))
        assertFalse(r.hasChanges, r.filterChanges.toString() + r.timingChanges)
        for (res in r.results) assertFalse(res.changed)
    }

    @Test
    fun aBw2NullIsFixedWithoutMakingASeatWorse() {
        // BW2 both sides at 150 Hz, same polarity: a deep null at the corner.
        val xo = JdspCrossovers(lowLowpass = XoFilter(150.0, XoType.BW2), midHighpass = XoFilter(150.0, XoType.BW2), threeWay = false)
        val r = run(sweeps(xo, bands = listOf(Band.LOW, Band.MID)), FinderOptions(current = xo, midHigh = false))
        val res = r.results.single()
        assertTrue(res.changed)
        assertTrue(res.scoreDb > 2.0, "score ${res.scoreDb}")
        for (c in res.channels) {
            for (p in c.positions) assertTrue(p.scoreDb >= -0.01, "${p.key.label} ${p.scoreDb}")
            // Near the corner the new sum is within 1.5 dB of the flat-sum target at both seats.
            val i = r.freqs.indices.minBy { abs(r.freqs[it] - 150.0) }
            c.seats.forEachIndexed { si, s -> assertEquals(c.targetDb[si][i], s.afterDb[i], 1.5, "${c.channel} ${s.seat}") }
        }
    }

    @Test
    fun aHoleBetweenTheBandsIsFilledAtBothSeats() {
        // The Mid speaker has its own factory highpass at 240 Hz: with J_DSP's Low lowpass at 150
        // Hz there is a hole between the bands, which the finder has to close (slope, corners,
        // delay or polarity: whatever sums best) within the default limits.
        val xo = JdspCrossovers(threeWay = false)
        val drivers = mapOf(Band.MID to Driver(listOf(XoFilter(240.0, XoType.LR4) to true)))
        val f = FinderOptions(current = xo, midHigh = false)
        val r = run(sweeps(xo, drivers, bands = listOf(Band.LOW, Band.MID)), f)
        val res = r.results.single()
        assertTrue(res.changed)
        assertTrue(res.scoreDb > 3.0, "score ${res.scoreDb}")
        assertTrue(r.filterChanges.isNotEmpty())
        for (s in listOf(XoSlot.LOW_LOWPASS to res.lower, XoSlot.MID_HIGHPASS to res.upper)) {
            val l = f.limits(s.first)
            assertTrue(s.second.hz in l.minHz..l.maxHz && s.second.type in l.types, "${s.first}: ${s.second}")
        }
        val bandIdx = r.freqs.indices.filter { r.freqs[it] in 120.0..260.0 }
        for (c in res.channels) c.seats.forEachIndexed { si, seat ->
            val before = bandIdx.maxOf { c.targetDb[si][it] - seat.beforeDb[it] }
            val after = bandIdx.maxOf { c.targetDb[si][it] - seat.afterDb[it] }
            assertTrue(after < before - 3 && after < 3.0, "${c.channel} ${seat.seat}: deepest dip ${"%.1f".format(before)} -> ${"%.1f".format(after)} dB")
        }
    }

    @Test
    fun limitsAreRespected() {
        val xo = JdspCrossovers(threeWay = false)
        val drivers = mapOf(Band.MID to Driver(listOf(XoFilter(240.0, XoType.LR4) to true)))
        val f = FinderOptions(current = xo, midHigh = false)
            .withLimits(XoSlot.LOW_LOWPASS, SlotLimits(140.0, 160.0, setOf(XoType.LR4)))
            .withLimits(XoSlot.MID_HIGHPASS, SlotLimits(150.0, 150.0, setOf(XoType.LR4)))
        val res = run(sweeps(xo, drivers, bands = listOf(Band.LOW, Band.MID)), f).results.single()
        assertTrue(res.lower.hz in 140.0..160.0, "${res.lower}")
        assertEquals(XoType.LR4, res.lower.type)
        assertEquals(XoFilter(150.0, XoType.LR4), res.upper)
    }

    @Test
    fun aBandLimitedSweepCantBeLiftedBeyondItsData() {
        // Low was swept only up to 320 Hz: moving its lowpass up would need Low above 320 Hz,
        // which the sweep says nothing about.
        val xo = JdspCrossovers(threeWay = false)
        val drivers = mapOf(Band.MID to Driver(listOf(XoFilter(240.0, XoType.LR4) to true)))
        val f = FinderOptions(current = xo, midHigh = false)
        val res = run(sweeps(xo, drivers, bands = listOf(Band.LOW, Band.MID), coverage = mapOf(Band.LOW to 15.0..320.0)), f).results.single()
        assertTrue(res.unreliable > 0)
        // Anything proposed keeps Low within what was measured: no lift above 320 Hz.
        val freqs = geomspace(320.0, 2000.0, 40)
        val now = JdspFilters.response(xo.lowLowpass, false, freqs, fs)
        val new = JdspFilters.response(res.lower, false, freqs, fs)
        for (i in freqs.indices) assertTrue(new.abs(i) <= maxOf(now.abs(i) * 1.13, 0.032), "${freqs[i]} Hz: ${res.lower}")
    }

    @Test
    fun midHighIsSolvedAfterLowMid() {
        // High arrives 0.15 ms late: a delay (or slope) change at Mid/High.
        val xo = JdspCrossovers()
        val r = run(sweeps(xo, mapOf(Band.HIGH to Driver(delayMs = 0.15))), FinderOptions(current = xo))
        val mh = r.results.single { it.crossover == Crossover.MID_HIGH }
        assertTrue(mh.changed)
        assertTrue(mh.channels.all { it.positions.all { p -> p.scoreDb >= -0.01 } })
        assertTrue(r.hasChanges)
    }

    @Test
    fun defaultLimitsKeepHighpassesNoShallower() {
        val l = FinderOptions.defaultLimits(XoSlot.HIGH_HIGHPASS, XoFilter(3500.0, XoType.LR4))
        assertEquals(setOf(XoType.LR4, XoType.BW4), l.types)
        assertTrue(l.minHz >= 2470 && l.maxHz <= 4950)
        assertEquals(XoType.entries.toSet(), FinderOptions.defaultLimits(XoSlot.LOW_LOWPASS, XoFilter(150.0, XoType.LR4)).types)
    }

    @Test
    fun fastLogIsAccurate() {
        for (x in listOf(1e-9, 0.001, 0.5, 1.0, 1.7, 3.3, 1234.5)) assertEquals(log10(x), CrossoverFinder.fastLog10(x), 1e-7)
    }
}
