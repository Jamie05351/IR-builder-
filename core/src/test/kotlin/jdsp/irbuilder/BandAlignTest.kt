package jdsp.irbuilder

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors J_DSP's BandAlignSolverTest, plus the Mid/High pair and multi-position seats. */
class BandAlignTest {
    /**
     * Mid's own 3.5 kHz LR4 low-pass delays it by √2/(π·3500) ≈ 0.13 ms at 150 Hz, so the phase
     * match is up to that much later than the pure time match. J_DSP's score (mean dB over the
     * region, cuts counted double) can settle anywhere in that window, so tests accept it.
     */
    private val midLag = 0.13
    private fun assertAligned(timeMatch: Double, actual: Double) =
        assertTrue(actual in timeMatch - 0.02..timeMatch + midLag + 0.02, "expected $timeMatch..${timeMatch + midLag} ms, got $actual")

    private fun timing(low: Double = 0.0, mid: Double = 0.0, high: Double = 0.0) = JdspTiming(
        BandSetting(low), BandSetting(low), BandSetting(mid), BandSetting(mid), BandSetting(high), BandSetting(high),
    )

    private fun solve(ms: List<Measurement>, t: JdspTiming, lowMid: Boolean = true, midHigh: Boolean = true, o: DesignOptions = DesignOptions()) =
        BandAlign.solve(MeasurementSet.load(ms, o), AlignOptions(t, lowMid, midHigh))

    private fun result(r: BandAlignReport, ch: Channel, pair: AlignPair) = r.results.first { it.channel == ch && it.pair == pair }

    @Test
    fun itFindsTheLowDelayThatBringsLowBackInStepWithMid() {
        // Low arrives 1.5 ms late with its delay at 2.0 ms: about 0.5 ms puts it back.
        val ms = Fixtures.measurementSet(positions = listOf("c"), delays = { _, b -> if (b == Band.LOW) 0.0015 else 0.0 })
        val r = solve(ms, timing(low = 2.0), midHigh = false)
        for (ch in Channel.entries) {
            val p = assertNotNull(result(r, ch, AlignPair.LOW_MID).proposal)
            assertAligned(0.5, p.delayMs)
            assertFalse(p.inverted)
        }
        assertNull(r.error)
    }

    @Test
    fun anInvertedLowIsPutBackTheRightWayRound() {
        val ms = Fixtures.measurementSet(positions = listOf("c"), inverted = { _, b -> b == Band.LOW })
        val r = solve(ms, timing(low = 1.0), midHigh = false)
        val p = assertNotNull(result(r, Channel.L, AlignPair.LOW_MID).proposal)
        assertTrue(p.inverted)
        assertAligned(1.0, p.delayMs)
    }

    @Test
    fun aSideThatAlreadyAddsUpGetsNoProposal() {
        val r = solve(Fixtures.measurementSet(positions = listOf("c")), timing(low = 1.0, high = 1.0))
        assertTrue(r.results.all { it.proposal == null })
        assertFalse(r.hasChanges)
    }

    @Test
    fun highIsAlignedAgainstMidAtTheUpperCrossover() {
        // High 0.15 ms late with its delay at 0.5 ms: 0.35 ms puts it back.
        val ms = Fixtures.measurementSet(positions = listOf("c"), delays = { _, b -> if (b == Band.HIGH) 0.00015 else 0.0 })
        val r = solve(ms, timing(high = 0.5), lowMid = false)
        val p = assertNotNull(result(r, Channel.R, AlignPair.MID_HIGH).proposal)
        assertEquals(0.35, p.delayMs, 0.02)
    }

    @Test
    fun noSeatIsTradedForTheOther() {
        // The seats want opposite Low timings; whatever is proposed leaves neither seat worse.
        val ms = Fixtures.measurementSet(positions = listOf("c"), delays = { s, b -> if (b == Band.LOW) (if (s == Seat.DRIVER) 0.001 else -0.001) else 0.0 })
        val r = solve(ms, timing(low = 1.4), midHigh = false)
        for (res in r.results) {
            for (seat in Seat.entries) {
                val scores = res.positions.filter { it.key.seat == seat && !it.validationOnly }.map { it.scoreDb }
                assertTrue(scores.average() >= -1e-9, "$seat ${res.channel}: ${res.positions}")
            }
        }
    }

    @Test
    fun aLowWantedEarlierThanZeroMakesEveryOtherDelayLater() {
        val ms = Fixtures.measurementSet(positions = listOf("c"), delays = { _, b -> if (b == Band.LOW) 0.0015 else 0.0 })
        val r = solve(ms, timing(), midHigh = false)
        // The offset is minus Low's proposal: 1.5 ms, less up to Mid's lag.
        assertAligned(1.5 - midLag, r.sharedOffsetMs)
        val t = assertNotNull(r.newTiming)
        for (ch in Channel.entries) {
            assertEquals(0.0, t.get(Band.LOW, ch).delayMs, 1e-9)
            assertEquals(r.sharedOffsetMs, t.get(Band.MID, ch).delayMs, 1e-9)
            assertEquals(r.sharedOffsetMs, t.get(Band.HIGH, ch).delayMs, 1e-9)
        }
        // Both Lows stay at 0; only the four Mid/High delays change.
        assertEquals(setOf(Band.MID, Band.HIGH), r.changes.map { it.band }.toSet())
        assertEquals(4, r.changes.size)
    }

    @Test
    fun proposalsThatDontFitTheRangeTogetherAreRefused() {
        // Low wants to be earlier than 0 (limited to -0.6 ms by the 2.2 ms delays, an offset of
        // 0.6) and High wants 0.1 ms later than 2.2: with the offset it would need 2.9 ms.
        val ms = Fixtures.measurementSet(positions = listOf("c"), delays = { _, b ->
            when (b) { Band.LOW -> 0.0015; Band.HIGH -> -0.0001; else -> 0.0 }
        })
        val r = solve(ms, timing(low = 0.0, mid = 2.2, high = 2.2))
        assertNull(r.newTiming)
        assertNotNull(r.error)
        assertFalse(r.hasChanges)
    }

    @Test
    fun aBestAtTheEndOfTheRangeIsFlagged() {
        // Every other delay is at the top, so Low can't go below 0, but it wants to.
        val ms = Fixtures.measurementSet(positions = listOf("c"), delays = { _, b -> if (b == Band.LOW) 0.002 else 0.0 })
        val r = solve(ms, timing(low = 1.0, mid = 2.8, high = 2.8), midHigh = false)
        val res = result(r, Channel.L, AlignPair.LOW_MID)
        assertEquals(0.0, assertNotNull(res.proposal).delayMs, 1e-9)
        assertTrue(res.atRangeLimit)
    }

    @Test
    fun validationPositionsDoNotChangeTheProposal() {
        val dir = createTempDirectory("irb").toFile()
        val base = Fixtures.measurementSet(dir = dir, positions = listOf("c"), delays = { _, b -> if (b == Band.LOW) 0.0015 else 0.0 })
        val held = Fixtures.measurementSet(dir = dir, positions = listOf("held"), delays = { _, b -> if (b == Band.LOW) 0.002 else 0.0 }, validationPositions = setOf("held"))
        val a = solve(base, timing(low = 2.0), midHigh = false)
        val b = solve(base + held, timing(low = 2.0), midHigh = false)
        assertNotNull(result(a, Channel.L, AlignPair.LOW_MID).proposal)
        assertEquals(result(a, Channel.L, AlignPair.LOW_MID).proposal, result(b, Channel.L, AlignPair.LOW_MID).proposal)
        assertTrue(result(b, Channel.L, AlignPair.LOW_MID).positions.any { it.validationOnly })
    }

    @Test
    fun theAlignedSetIsWhatTheMeasurementsWillBeAndFeedsTheDesign() {
        val ms = Fixtures.measurementSet(positions = listOf("c"), delays = { _, b -> if (b == Band.LOW) 0.0015 else 0.0 }, combined = true)
        val set = MeasurementSet.load(ms, DesignOptions())
        val r = BandAlign.solve(set, AlignOptions(timing(low = 2.0), midHigh = false))
        assertTrue(r.hasChanges)
        val aligned = r.alignedSet(set)
        val o = set.options
        for (g in aligned.groups) {
            assertFalse(Band.COMBINED in g.responses)
            val before = set.group(g.key)!!
            val pb = Checks.relativePhase(before.branch(Band.LOW), before.branch(Band.MID), set.freqs, o.lowMidHz)
            val pa = Checks.relativePhase(g.branch(Band.LOW), g.branch(Band.MID), set.freqs, o.lowMidHz)
            assertTrue(kotlin.math.abs(pa) < kotlin.math.abs(pb), "before ${Math.toDegrees(pb)}°, after ${Math.toDegrees(pa)}°")
        }
        // The phase design runs on the aligned set.
        val d = Designer.design(aligned)
        assertTrue(d.report.positions.isNotEmpty())
    }
}

class BandAlignExportTest {
    @Test
    fun packageListsTheJdspChangesTheIrsNeed() {
        val ms = Fixtures.measurementSet(positions = listOf("c"), delays = { _, b -> if (b == Band.LOW) 0.0015 else 0.0 })
        val set = MeasurementSet.load(ms, DesignOptions(taps = 8192, latencyFrames = 4096))
        val t = JdspTiming(BandSetting(2.0), BandSetting(2.0))
        val align = BandAlign.solve(set, AlignOptions(t, midHigh = false))
        val d = Designer.design(set, align)
        assertEquals(2, d.report.jdspSettingChanges.size)
        assertTrue(d.report.warnings.first().contains("Band Align"))
        val dir = kotlin.io.path.createTempDirectory("irb-out").toFile()
        Exporter.export(d, dir)
        val readme = java.io.File(dir, "README.txt").readText()
        assertTrue(readme.contains("FIRST: Band Align changes"), readme)
        assertTrue(readme.contains("Low L: delay 2.00 →"), readme)
        assertTrue(readme.lines().none { it.startsWith("            ") }, "README indentation broken:\n$readme")
        assertTrue(java.io.File(dir, "manifest.json").readText().contains("jdsp_setting_changes"))
        // Without changes nothing extra is written.
        val plain = Designer.design(set)
        assertTrue(plain.report.jdspSettingChanges.isEmpty())
        assertFalse(Exporter.readme(plain).contains("Band Align"))
    }

    @Test
    fun projectKeepsBandAlignSettings() {
        val file = java.io.File(kotlin.io.path.createTempDirectory("irb").toFile(), "p.jirb")
        val p = Project(tool = Tool.BAND_ALIGN, align = AlignOptions(JdspTiming(lowL = BandSetting(1.25, true)), lowMid = true, midHigh = false))
        Project.save(p, file)
        assertEquals(p, Project.load(file))
    }
}
