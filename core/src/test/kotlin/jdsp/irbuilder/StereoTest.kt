package jdsp.irbuilder

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StereoTest {
    private val o = DesignOptions(irMode = IrMode.STEREO, taps = 16384, latencyFrames = 8192, maxShiftMs = 6.0)

    /** Combined sweeps of an LR4 3-way sum (allpass phase rotation) at both seats, plus extra delay. */
    private fun combined(dir: File, sum: (Double) -> Fixtures.C, channels: List<Channel> = Channel.entries): List<Measurement> {
        val out = ArrayList<Measurement>()
        for (seat in Seat.entries) for (pos in listOf("c", "p2")) for (ch in channels) {
            val extra = if (seat == Seat.PASSENGER) 0.0004 else 0.0
            val file = File(dir, "${seat.name}_${pos}_${ch.name}_combined.txt")
            Fixtures.writeRew(file, { f -> sum(f) * Fixtures.C(kotlin.math.cos(-2 * Math.PI * f * extra), kotlin.math.sin(-2 * Math.PI * f * extra)) })
            out += Measurement(file.path, seat, pos, ch, Band.COMBINED, "ref-${ch.name}")
        }
        return out
    }

    private fun lr4Sum(f: Double) = Band.BRANCHES.map { Fixtures.band(it, f) }.reduce { a, b -> a + b }

    @Test
    fun theCrossoverPhaseRotationIsLinearisedAtBothSeats() {
        val ms = combined(createTempDirectory("irb").toFile(), ::lr4Sum)
        val set = MeasurementSet.load(ms, o, emptySet())
        assertTrue(set.loadErrors.isEmpty(), set.loadErrors.joinToString { it.message })
        val d = StereoDesigner.design(set)
        assertTrue(d.report.positions.isNotEmpty())
        for (p in d.report.positions) {
            assertTrue(p.afterRmsDeg < p.beforeRmsDeg * 0.4, "${p.seat} ${p.position} ${p.channel}: ${p.beforeRmsDeg}° -> ${p.afterRmsDeg}°")
            assertFalse(p.deteriorated)
        }
        // Phase-only: the IR's magnitude stays flat.
        assertTrue(d.report.channels.all { it.magnitudeRippleDb < 0.3 }, d.report.channels.toString())
    }

    @Test
    fun aPureDelayNeedsNoCorrection() {
        val ms = combined(createTempDirectory("irb").toFile(), { f -> Fixtures.C(kotlin.math.cos(-2 * Math.PI * f * 0.003), kotlin.math.sin(-2 * Math.PI * f * 0.003)) })
        val d = StereoDesigner.design(MeasurementSet.load(ms, o, emptySet()))
        assertTrue(d.report.channels.all { it.maxPhaseDeg < 3 }, d.report.channels.toString())
        val imp = d.impulses[0]
        assertEquals(o.latencyFrames, imp.indices.maxBy { abs(imp[it]) })
    }

    @Test
    fun aChannelWithoutCombinedSweepsIsAPureDelay() {
        val ms = combined(createTempDirectory("irb").toFile(), ::lr4Sum, channels = listOf(Channel.L))
        val d = StereoDesigner.design(MeasurementSet.load(ms, o, emptySet()))
        val r = d.impulses[Channel.R.ordinal]
        val peak = r.indices.maxBy { abs(r[it]) }
        assertEquals(o.latencyFrames, peak)
        assertTrue(r.indices.filter { it != peak }.sumOf { r[it].toDouble() * r[it] } < 1e-8)
        assertTrue(d.report.warnings.any { it.contains("Right channel: no Combined sweeps") })
    }

    @Test
    fun exportWritesOneStereoFloatWav() {
        val ms = combined(createTempDirectory("irb").toFile(), ::lr4Sum)
        val d = StereoDesigner.design(MeasurementSet.load(ms, o, emptySet()))
        val dir = createTempDirectory("irb-out").toFile()
        val wav = Exporter.exportStereo(d, dir)
        val bytes = wav.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3, b.getShort(20).toInt()); assertEquals(2, b.getShort(22).toInt()); assertEquals(48000, b.getInt(24))
        assertEquals(o.taps * 8, b.getInt(40))
        assertTrue(File(dir, "README.txt").readText().contains("Stereo slot"))
        assertTrue(File(dir, "report.json").readText().contains("stereo_phase_linearisation"))
    }

    @Test
    fun stereoChecksOnlyNeedCombinedSweepsCoveringTheRange() {
        val dir = createTempDirectory("irb").toFile()
        val ms = combined(dir, ::lr4Sum)
        assertTrue(Checks.run(MeasurementSet.load(ms, o, emptySet()), CheckScope.STEREO).canDesign)
        // A Combined sweep that stops at 5 kHz can't cover the 10 kHz range.
        val short = File(dir, "short.txt").also { Fixtures.writeRew(it, ::lr4Sum, maxHz = 5000.0) }
        val set = MeasurementSet.load(listOf(Measurement(short.path, Seat.DRIVER, "c", Channel.L, Band.COMBINED, "r")), o, emptySet())
        assertTrue(set.loadErrors.single().message.contains("Combined"), set.loadErrors.single().message)
    }

    @Test
    fun aLongRecordedArrivalDelayIsNotMistakenForPhaseRotation() {
        // Flat Combined sweeps 100 ms late, which REW took out of the phase and noted in the
        // header. Estimated from the log grid alone, such a delay aliases; the file's own note
        // must be used, so nothing needs correcting.
        val dir = createTempDirectory("irb").toFile()
        val ms = ArrayList<Measurement>()
        for (seat in Seat.entries) for (pos in listOf("c", "p2")) for (ch in Channel.entries) {
            val file = File(dir, "${seat.name}_${pos}_${ch.name}_combined.txt")
            val late = 0.1 + if (seat == Seat.PASSENGER) 0.0004 else 0.0
            Fixtures.writeRew(file, { f -> Fixtures.C(kotlin.math.cos(-2 * Math.PI * f * late), kotlin.math.sin(-2 * Math.PI * f * late)) }, removedDelayMs = 100.0)
            ms += Measurement(file.path, seat, pos, ch, Band.COMBINED, "ref-${ch.name}")
        }
        val d = StereoDesigner.design(MeasurementSet.load(ms, o, emptySet()))
        for (c in d.report.channels) assertTrue(c.maxPhaseDeg < 5.0, "${c.channel}: ${c.maxPhaseDeg}°")
        for (p in d.report.positions) assertTrue(p.beforeRmsDeg < 5.0, "${p.seat} ${p.position}: ${p.beforeRmsDeg}°")
    }

    @Test
    fun hiddenPerBandRegionSettingsDontBlockStereo() {
        // Valid per-band settings, then Stereo with a 1-octave fade: the per-band Mid/High region
        // would reach 28 kHz, but Stereo doesn't use it (and its controls are hidden).
        DesignOptions(midHighHz = 7000.0, regionOctaves = 1.0, fadeOctaves = 0.5).validate()
        DesignOptions(midHighHz = 7000.0, regionOctaves = 1.0, fadeOctaves = 1.0, irMode = IrMode.STEREO, maxShiftMs = 5.0).validate()
    }
}

class CombinedOnlyChecksTest {
    @Test
    fun perBandChecksWithOnlyCombinedSweepsPointToStereo() {
        val dir = createTempDirectory("irb").toFile()
        val file = File(dir, "c.txt").also { Fixtures.writeRew(it, { f -> Band.BRANCHES.map { b -> Fixtures.band(b, f) }.reduce { a, c -> a + c } }) }
        val ms = listOf(Measurement(file.path, Seat.DRIVER, "c", Channel.L, Band.COMBINED, "r"))
        val scope = CheckScope.phaseIrs(ms)
        val r = Checks.run(MeasurementSet.load(ms, DesignOptions(), scope.crossovers.toSet()), scope)
        assertFalse(r.canDesign)
        assertTrue(r.items.single().title.contains("Stereo"), r.items.joinToString { it.title })
    }
}
