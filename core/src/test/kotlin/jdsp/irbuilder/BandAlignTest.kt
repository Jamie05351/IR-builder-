package jdsp.irbuilder

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}

class BandAlignProjectTest {
    @Test
    fun projectKeepsBandAlignSettings() {
        val file = java.io.File(kotlin.io.path.createTempDirectory("irb").toFile(), "p.jirb")
        val p = Project(tool = Tool.BAND_ALIGN, align = AlignOptions(JdspTiming(lowL = BandSetting(1.25, true)), lowMid = true, midHigh = false))
        Project.save(p, file)
        assertEquals(p, Project.load(file))
    }
}

class ToolSeparationTest {
    @Test
    fun irFilterSettingsNeverBlockTheChecks() {
        // An IR-only setting that's invalid (taps not a power of two) must not stop Band Align.
        val ms = Fixtures.measurementSet(positions = listOf("c"))
        val bad = DesignOptions(taps = 3000)
        assertFailsWith<IllegalArgumentException> { bad.validate() }
        val checks = Checks.run(MeasurementSet.load(ms, bad))
        assertTrue(checks.canDesign, checks.items.joinToString { it.title })
        // A bad crossover does matter to both tools.
        val badXo = Checks.run(MeasurementSet.load(ms, DesignOptions(midHighHz = 200.0)))
        assertTrue(badXo.items.any { it.severity == Severity.ERROR && it.title == "Measurement settings" })
    }

    @Test
    fun bandAlignScoresAroundTheEnteredCrossovers() {
        val ms = Fixtures.measurementSet(positions = listOf("c"))
        val r = BandAlign.solve(MeasurementSet.load(ms, DesignOptions(lowMidHz = 200.0, midHighHz = 3000.0)), AlignOptions())
        val lm = r.results.first { it.pair == AlignPair.LOW_MID }.bandHz
        val mh = r.results.first { it.pair == AlignPair.MID_HIGH }.bandHz
        assertEquals(80.0, lm.start, 1e-9); assertEquals(500.0, lm.endInclusive, 1e-9)
        assertEquals(1200.0, mh.start, 1e-9); assertEquals(7500.0, mh.endInclusive, 1e-9)
    }

    @Test
    fun theIrDesignNoLongerTakesBandAlignInput() {
        // The two tools are independent: a design's report and package say nothing about Band Align.
        val ms = Fixtures.measurementSet(positions = listOf("c"), delays = { _, b -> if (b == Band.LOW) 0.0015 else 0.0 })
        val d = Designer.design(MeasurementSet.load(ms, DesignOptions(taps = 8192, latencyFrames = 4096)))
        assertFalse(Exporter.readme(d).contains("Band Align"))
        assertFalse(Exporter.manifest(d).containsKey("jdsp_setting_changes"))
    }
}

class BandLimitedAndPartialSetsTest {
    private fun bandLimited(
        dir: java.io.File,
        bands: List<Band> = Band.BRANCHES,
        seats: List<Seat> = Seat.entries,
        channels: List<Channel> = Channel.entries,
        ranges: Map<Band, Pair<Double, Double>> = mapOf(Band.LOW to (10.0 to 400.0), Band.MID to (70.0 to 8000.0), Band.HIGH to (1700.0 to 22000.0)),
        lowLateS: Double = 0.0,
    ): List<Measurement> {
        val out = ArrayList<Measurement>()
        for (seat in seats) for (ch in channels) for (b in bands) {
            val file = java.io.File(dir, "${seat.name}_c_${ch.name}_${b.fileName}.txt")
            val (lo, hi) = ranges.getValue(b)
            Fixtures.writeRew(file, { f -> Fixtures.band(b, f, delaySeconds = if (b == Band.LOW) lowLateS else 0.0) }, minHz = lo, maxHz = hi)
            out += Measurement(file.path, seat, "c", ch, b, if (ch == Channel.L) "R" else "L")
        }
        return out
    }

    @Test
    fun bandLimitedSweepsAreAcceptedAndSilentOutsideTheirRange() {
        val dir = kotlin.io.path.createTempDirectory("irb").toFile()
        val set = MeasurementSet.load(bandLimited(dir), DesignOptions())
        assertTrue(set.loadErrors.isEmpty(), set.loadErrors.joinToString { it.message })
        val c = Checks.run(set)
        assertTrue(c.canDesign, c.items.filter { it.severity == Severity.ERROR }.joinToString { it.title })
        val g = set.groups.first()
        val hi = g.branch(Band.HIGH)
        val i150 = set.freqs.indexOfFirst { it >= 150.0 }
        assertEquals(0.0, hi.abs(i150), 0.0)   // High wasn't swept down there.
        assertTrue(g.branch(Band.LOW).abs(i150) > 0)
        // The Phase IR design still works on band-limited sweeps.
        Designer.design(MeasurementSet.load(bandLimited(dir), DesignOptions(taps = 8192, latencyFrames = 4096)))
    }

    @Test
    fun aSweepThatStopsShortOfItsCrossoverRegionIsRefusedWithTheRangeItNeeds() {
        val dir = kotlin.io.path.createTempDirectory("irb").toFile()
        val ms = bandLimited(dir, ranges = mapOf(Band.LOW to (10.0 to 400.0), Band.MID to (120.0 to 8000.0), Band.HIGH to (1700.0 to 22000.0)))
        val set = MeasurementSet.load(ms, DesignOptions())
        val e = set.loadErrors.first { it.measurement.band == Band.MID }
        assertTrue(e.message.contains("75") && e.message.contains("7000"), e.message)
    }

    @Test
    fun bandAlignLowMidNeedsNoHighSweeps() {
        val dir = kotlin.io.path.createTempDirectory("irb").toFile()
        val ms = bandLimited(dir, bands = listOf(Band.LOW, Band.MID), lowLateS = 0.0015)
        val set = MeasurementSet.load(ms, DesignOptions())
        val a = AlignOptions(JdspTiming(BandSetting(2.0), BandSetting(2.0)), lowMid = true, midHigh = false)
        val c = Checks.run(set, CheckScope.bandAlign(a))
        assertTrue(c.canDesign, c.items.filter { it.severity == Severity.ERROR }.joinToString { it.title })
        val r = BandAlign.solve(set, a)
        assertTrue(r.results.all { it.pair == AlignPair.LOW_MID } && r.results.size == 2)
        assertTrue(r.results.all { it.proposal != null })
        // The Phase IRs still insist on all three bands.
        assertFalse(Checks.run(set).canDesign)
    }

    @Test
    fun bandAlignRunsOnOneSeatOrOneSideWithAWarning() {
        val dir = kotlin.io.path.createTempDirectory("irb").toFile()
        val ms = bandLimited(dir, seats = listOf(Seat.DRIVER), channels = listOf(Channel.L), lowLateS = 0.0015)
        val set = MeasurementSet.load(ms, DesignOptions())
        val a = AlignOptions(JdspTiming(BandSetting(2.0), BandSetting(2.0)))
        val c = Checks.run(set, CheckScope.bandAlign(a))
        assertTrue(c.canDesign)
        assertTrue(c.items.any { it.severity == Severity.WARNING && it.title.contains("Right side: no Low/Mid") })
        assertTrue(c.items.any { it.severity == Severity.WARNING && it.title.contains("Driver seat only") })
        val r = BandAlign.solve(set, a)
        assertTrue(r.results.all { it.channel == Channel.L })
        assertNotNull(r.results.first { it.pair == AlignPair.LOW_MID }.proposal)
    }

    @Test
    fun bandAlignWithNothingUsableIsAnError() {
        val dir = kotlin.io.path.createTempDirectory("irb").toFile()
        val ms = bandLimited(dir, bands = listOf(Band.MID))
        val c = Checks.run(MeasurementSet.load(ms, DesignOptions()), CheckScope.bandAlign(AlignOptions()))
        assertFalse(c.canDesign)
        assertTrue(c.items.any { it.title == "Nothing to align" })
    }
}

class PerPairAndCombinedCoverageTest {
    @Test
    fun eachPairCountsPositionsThatHaveItsTwoBands() {
        // Low/Mid sweeps at one position, Mid/High at another: both pairs can run.
        val dir = kotlin.io.path.createTempDirectory("irb").toFile()
        val ms = ArrayList<Measurement>()
        for (seat in Seat.entries) for (ch in Channel.entries) {
            for ((pos, bands) in listOf("a" to listOf(Band.LOW, Band.MID), "b" to listOf(Band.MID, Band.HIGH))) for (b in bands) {
                val file = java.io.File(dir, "${seat.name}_${pos}_${ch.name}_${b.fileName}.txt")
                Fixtures.writeRew(file, { f -> Fixtures.band(b, f, delaySeconds = if (b == Band.LOW) 0.0015 else 0.0) })
                ms += Measurement(file.path, seat, pos, ch, b, if (ch == Channel.L) "R" else "L")
            }
        }
        val set = MeasurementSet.load(ms, DesignOptions())
        val a = AlignOptions(JdspTiming(BandSetting(2.0), BandSetting(2.0), highL = BandSetting(1.0), highR = BandSetting(1.0)))
        val c = Checks.run(set, CheckScope.bandAlign(a))
        assertTrue(c.canDesign, c.items.filter { it.severity == Severity.ERROR }.joinToString { it.title })
        assertTrue(c.items.any { it.title.contains("not used for Mid/High") })
        val r = BandAlign.solve(set, a)
        assertEquals(4, r.results.size)
        assertTrue(r.results.filter { it.pair == AlignPair.LOW_MID }.all { res -> res.positions.all { it.key.position == "a" } })
        assertTrue(r.results.filter { it.pair == AlignPair.MID_HIGH }.all { res -> res.positions.all { it.key.position == "b" } })
    }

    @Test
    fun aBandLimitedCombinedSweepIsOnlyComparedWhereItWasMeasured() {
        val dir = kotlin.io.path.createTempDirectory("irb").toFile()
        val ms = ArrayList<Measurement>()
        for (seat in Seat.entries) for (ch in Channel.entries) {
            for (b in Band.BRANCHES) {
                val file = java.io.File(dir, "${seat.name}_c_${ch.name}_${b.fileName}.txt")
                Fixtures.writeRew(file, { f -> Fixtures.band(b, f) })
                ms += Measurement(file.path, seat, "c", ch, b, "ref-${ch.name}")
            }
            val file = java.io.File(dir, "${seat.name}_c_${ch.name}_combined.txt")
            Fixtures.writeRew(file, { f -> Band.BRANCHES.map { b -> Fixtures.band(b, f) }.reduce { x, y -> x + y } }, minHz = 70.0, maxHz = 8000.0)
            ms += Measurement(file.path, seat, "c", ch, Band.COMBINED, "ref-${ch.name}")
        }
        val d = Designer.design(MeasurementSet.load(ms, DesignOptions(taps = 8192, latencyFrames = 4096)))
        d.report.positions.forEach { assertTrue((it.combinedVsBranchSumRelativeError ?: 1.0) < 0.05, "${it.combinedVsBranchSumRelativeError}") }
        assertTrue(d.report.warnings.none { it.contains("combined sweep disagrees") })
    }
}

class CoverageInUseTest {
    @Test
    fun midOnlyNeedsTheCrossoversInUse() {
        val o = DesignOptions()
        assertEquals(75.0, requiredCoverageHz(Band.MID, o).start, 1e-9)
        assertEquals(7000.0, requiredCoverageHz(Band.MID, o).endInclusive, 1e-9)
        // Low/Mid only: Mid needn't reach above 300 Hz.
        val lm = requiredCoverageHz(Band.MID, o, setOf(Crossover.LOW_MID))
        assertEquals(75.0, lm.start, 1e-9); assertEquals(300.0, lm.endInclusive, 1e-9)
        // Mid/High only: Mid needn't reach below 1750 Hz.
        val mh = requiredCoverageHz(Band.MID, o, setOf(Crossover.MID_HIGH))
        assertEquals(1750.0, mh.start, 1e-9); assertEquals(7000.0, mh.endInclusive, 1e-9)
    }

    @Test
    fun aLowMidRunAcceptsAMidSweepThatStopsAt1kHz() {
        val dir = kotlin.io.path.createTempDirectory("irb").toFile()
        val ms = ArrayList<Measurement>()
        for (seat in Seat.entries) for (ch in Channel.entries) for ((b, hi) in listOf(Band.LOW to 500.0, Band.MID to 1000.0)) {
            val file = java.io.File(dir, "${seat.name}_c_${ch.name}_${b.fileName}.txt")
            Fixtures.writeRew(file, { f -> Fixtures.band(b, f, delaySeconds = if (b == Band.LOW) 0.0015 else 0.0) }, minHz = 15.0, maxHz = hi)
            ms += Measurement(file.path, seat, "c", ch, b, if (ch == Channel.L) "R" else "L")
        }
        val a = AlignOptions(JdspTiming(BandSetting(2.0), BandSetting(2.0)), lowMid = true, midHigh = false)
        val scope = CheckScope.bandAlign(a)
        val set = MeasurementSet.load(ms, DesignOptions(), scope.crossovers.toSet())
        assertTrue(set.loadErrors.isEmpty(), set.loadErrors.joinToString { it.message })
        assertTrue(Checks.run(set, scope).canDesign)
        assertTrue(BandAlign.solve(set, a).results.all { it.proposal != null })
        // The same Mid sweep is refused when the Mid/High crossover is in use.
        assertTrue(MeasurementSet.load(ms, DesignOptions()).loadErrors.any { it.measurement.band == Band.MID })
    }
}

class MissingPairBandTest {
    @Test
    fun aPairWithNoSweepsForItsBandIsLeftOutAndDoesNotStretchMid() {
        // Low and Mid only, both pairs switched on: Mid/High is skipped, so Mid needs 75-300 Hz, not 7 kHz.
        val dir = kotlin.io.path.createTempDirectory("irb").toFile()
        val ms = ArrayList<Measurement>()
        for (ch in Channel.entries) for ((b, hi) in listOf(Band.LOW to 500.0, Band.MID to 500.0)) {
            val file = java.io.File(dir, "driver_centre_${ch.name}_${b.fileName}.txt")
            Fixtures.writeRew(file, { f -> Fixtures.band(b, f, delaySeconds = if (b == Band.LOW) 0.0015 else 0.0) }, minHz = 15.0, maxHz = hi)
            ms += Measurement(file.path, Seat.DRIVER, "centre", ch, b, if (ch == Channel.L) "R" else "L")
        }
        val a = AlignOptions(JdspTiming(BandSetting(2.0), BandSetting(2.0)), lowMid = true, midHigh = true)
        val scope = CheckScope.bandAlign(a, ms)
        assertEquals(setOf(Crossover.LOW_MID), scope.crossovers.toSet())
        val set = MeasurementSet.load(ms, DesignOptions(), scope.crossovers.toSet())
        assertTrue(set.loadErrors.isEmpty(), set.loadErrors.joinToString { it.message })
        val c = Checks.run(set, scope)
        assertTrue(c.canDesign, c.items.filter { it.severity == Severity.ERROR }.joinToString { it.title })
        assertTrue(c.items.any { it.severity == Severity.WARNING && it.title.startsWith("Mid/High: no High sweeps") })
        assertTrue(BandAlign.solve(set, a).results.all { it.pair == AlignPair.LOW_MID && it.proposal != null })
    }

    @Test
    fun aMidSweepStartingAbove75HzIsStillRefusedForLowMid() {
        // J_DSP's rule: Mid must start an octave below its 150 Hz high-pass, so the overlap with Low is in it.
        val dir = kotlin.io.path.createTempDirectory("irb").toFile()
        val file = java.io.File(dir, "mid.txt")
        Fixtures.writeRew(file, { f -> Fixtures.band(Band.MID, f) }, minHz = 100.0, maxHz = 500.0)
        val m = Measurement(file.path, Seat.DRIVER, "c", Channel.L, Band.MID, "R")
        val set = MeasurementSet.load(listOf(m), DesignOptions(), setOf(Crossover.LOW_MID))
        assertTrue(set.loadErrors.single().message.contains("75–300 Hz"), set.loadErrors.single().message)
    }
}
