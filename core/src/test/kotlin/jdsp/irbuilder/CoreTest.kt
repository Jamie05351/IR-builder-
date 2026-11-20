package jdsp.irbuilder

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DspTest {
    @Test
    fun fftMatchesDirectDftAndRoundTrips() {
        val rnd = Random(1)
        val x = DoubleArray(64) { rnd.nextDouble(-1.0, 1.0) }
        val s = Fft.rfft(x, 64)
        for (k in 0..32) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until 64) { re += x[t] * cos(-2 * PI * k * t / 64); im += x[t] * sin(-2 * PI * k * t / 64) }
            assertEquals(re, s.re[k], 1e-9)
            assertEquals(im, s.im[k], 1e-9)
        }
        val y = Fft.irfft(s, 64)
        for (t in 0 until 64) assertEquals(x[t], y[t], 1e-12)
    }

    @Test
    fun unwrapAndSmoothBehaveLikeNumpyScipy() {
        val wrapped = DoubleArray(50) { wrap(it * 0.9) }
        val u = unwrap(wrapped)
        for (i in u.indices) assertEquals(i * 0.9, u[i], 1e-9)
        val flat = gaussianSmooth(DoubleArray(20) { 3.0 }, 2.0)
        flat.forEach { assertEquals(3.0, it, 1e-12) }
        val w = tukey(11, 0.2)
        assertEquals(0.0, w[0], 1e-12)
        assertEquals(1.0, w[5], 1e-12)
        assertEquals(0.0, w[10], 1e-12)
    }
}

class ImportTest {
    @Test
    fun delayedImpulseWavKeepsUnitMagnitudeAndAbsolutePhase() {
        val dir = createTempDirectory("irb").toFile()
        val file = File(dir, "imp.wav")
        val samples = FloatArray(4800).also { it[480] = 1f }
        writeMonoFloatWav(file, 48000, samples)
        val freqs = geomspace(50.0, 15000.0, 300)
        val r = Importer.read(Measurement(file.path, Seat.DRIVER, "c", Channel.L, Band.LOW, "ref"), freqs, 48000)
        for (i in freqs.indices) {
            assertEquals(1.0, r.response.abs(i), 1e-6)
            assertEquals(0.0, wrap(r.response.arg(i) + 2 * PI * freqs[i] * 0.01), 1e-6)
        }
        assertEquals(480, r.impulsePeakIndex)
    }

    @Test
    fun rejectsMissingPhaseWrongRateAndMdat() {
        val dir = createTempDirectory("irb").toFile()
        val noPhase = File(dir, "a.txt").apply { writeText((1..20).joinToString("\n") { "${it * 1000} 80" }) }
        val freqs = geomspace(100.0, 10000.0, 50)
        assertFailsWith<IllegalArgumentException> { Importer.read(Measurement(noPhase.path, Seat.DRIVER, "c", Channel.L, Band.LOW, "r"), freqs, 48000) }
        val wav = File(dir, "b.wav").also { writeMonoFloatWav(it, 44100, FloatArray(100).also { s -> s[3] = 1f }) }
        assertFailsWith<IllegalArgumentException> { Importer.read(Measurement(wav.path, Seat.DRIVER, "c", Channel.L, Band.LOW, "r"), freqs, 48000) }
        val mdat = File(dir, "c.mdat").apply { writeText("x") }
        assertFailsWith<IllegalArgumentException> { Importer.read(Measurement(mdat.path, Seat.DRIVER, "c", Channel.L, Band.LOW, "r"), freqs, 48000) }
    }

    @Test
    fun unwrappedRewPhaseKeepsLongDelay() {
        val dir = createTempDirectory("irb").toFile()
        val file = File(dir, "u.txt")
        val delay = 0.02
        file.writeText((0 until 400).joinToString("\n") { i ->
            val f = 20.0 * Math.pow(1000.0, i / 399.0)
            "$f 80 ${-360 * f * delay}"
        })
        val freqs = geomspace(100.0, 10000.0, 64)
        val r = Importer.read(Measurement(file.path, Seat.DRIVER, "c", Channel.L, Band.LOW, "r"), freqs, 48000)
        for (i in freqs.indices) assertEquals(0.0, wrap(r.response.arg(i) + 2 * PI * freqs[i] * delay), 1e-6)
    }

    companion object {
        fun writeMonoFloatWav(file: File, rate: Int, x: FloatArray) {
            val b = ByteBuffer.allocate(44 + x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + x.size * 4).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(1).putInt(rate).putInt(rate * 4).putShort(4).putShort(32)
            b.put("data".toByteArray()).putInt(x.size * 4)
            x.forEach { b.putFloat(it) }
            file.writeBytes(b.array())
        }
    }
}

class ChecksTest {
    @Test
    fun alignedSetPassesChecks() {
        val set = MeasurementSet.load(Fixtures.measurementSet(combined = true), DesignOptions())
        val result = Checks.run(set)
        assertEquals(0, result.errors, result.items.joinToString("\n"))
        assertTrue(result.items.any { it.title.contains("matches band sum") })
    }

    @Test
    fun missingBandAndMixedReferencesAreErrors() {
        val ms = Fixtures.measurementSet().filterNot { it.seat == Seat.PASSENGER && it.band == Band.HIGH && it.position == "p2" && it.channel == Channel.R }
        val mixed = ms.mapIndexed { i, m -> if (i == 0) m.copy(timingReference = "other") else m }
        val result = Checks.run(MeasurementSet.load(mixed, DesignOptions()))
        assertTrue(result.items.any { it.severity == Severity.ERROR && it.title.contains("incomplete") })
        assertTrue(result.items.any { it.severity == Severity.ERROR && it.title.contains("timing reference") })
        assertFalse(result.canDesign)
    }

    @Test
    fun allPositionsValidationOnlyLeavesNoDesignData() {
        val ms = Fixtures.measurementSet(positions = listOf("c"), validationPositions = setOf("c"))
        val result = Checks.run(MeasurementSet.load(ms, DesignOptions()))
        assertTrue(result.items.any { it.severity == Severity.ERROR && it.title.startsWith("No design data") })
    }

    @Test
    fun swappedBandTagsAreFlagged() {
        val ms = Fixtures.measurementSet(positions = listOf("c")).map {
            if (it.seat == Seat.DRIVER && it.channel == Channel.L) when (it.band) {
                Band.LOW -> it.copy(band = Band.HIGH)
                Band.HIGH -> it.copy(band = Band.LOW)
                else -> it
            } else it
        }
        val result = Checks.run(MeasurementSet.load(ms, DesignOptions()))
        assertTrue(result.items.any { it.severity == Severity.WARNING && it.title.contains("band order") })
    }

    @Test
    fun completenessGridListsBands() {
        val grid = Checks.completeness(Fixtures.measurementSet(positions = listOf("c")))
        assertEquals(4, grid.size)
        grid.values.forEach { assertEquals(Band.BRANCHES.toSet(), it) }
    }
}

class DesignTest {
    private fun region(r: PositionReport, xo: Crossover) = r.regions.first { it.crossover == xo.label }

    @Test
    fun alignedBandsNeedAlmostNoCorrection() {
        val d = Designer.design(MeasurementSet.load(Fixtures.measurementSet(), DesignOptions()))
        d.report.bands.forEach { assertTrue(it.maxPhaseDeg < 10, "${it.channel} ${it.band}: ${it.maxPhaseDeg}°") }
        d.report.positions.forEach { p -> p.regions.forEach { assertTrue(it.afterMeanDb >= it.beforeMeanDb - 0.05, it.toString()) } }
        // The IR is essentially a delayed unit impulse.
        val imp = d.impulses[0][0]
        val peak = imp.indices.maxBy { abs(imp[it]) }
        assertEquals(d.options.latencyFrames, peak)
        assertTrue(d.report.bands.all { it.magnitudeRippleDb < 0.1 })
    }

    @Test
    fun misalignedBandsAreRealignedAtBothSeats() {
        // Mid 1.2 ms late and High 0.12 ms late relative to Low: cancellations at both crossovers.
        val ms = Fixtures.measurementSet(delays = { _, b -> when (b) { Band.MID -> 0.0012; Band.HIGH -> 0.0013; else -> 0.0 } })
        val d = Designer.design(MeasurementSet.load(ms, DesignOptions()))
        for (p in d.report.positions) for (xo in Crossover.entries) {
            val r = region(p, xo)
            assertTrue(r.afterMeanDb > r.beforeMeanDb + 0.5, "${p.seat} ${p.position} ${p.channel} $xo: $r")
            assertTrue(r.afterWorstDb > r.beforeWorstDb + 1.0, "${p.seat} ${p.position} ${p.channel} $xo: $r")
            assertTrue(r.afterMeanDb > -1.0, "$xo $r")
        }
        assertTrue(d.report.positions.none { it.deteriorated })
        // Phase-only: magnitude of every band IR stays flat.
        assertTrue(d.report.bands.all { it.magnitudeRippleDb < 0.25 }, d.report.bands.toString())
        assertTrue(d.report.bands.all { it.maxGroupDelayChangeMs <= d.options.maxShiftMs + 1e-6 })
        assertTrue(d.commonGainDb <= 0.0)
    }

    @Test
    fun seatWeightSteersTheCompromise() {
        val delays = { s: Seat, b: Band -> if (b == Band.MID) (if (s == Seat.DRIVER) 0.0015 else -0.0005) else 0.0 }
        fun driverGain(w: Double): Double {
            val d = Designer.design(MeasurementSet.load(Fixtures.measurementSet(delays = delays), DesignOptions(driverWeight = w, passengerWeight = 1.0)))
            val s = d.report.seats.first { it.seat == Seat.DRIVER && it.channel == Channel.L }.regions.first { it.crossover == "Low/Mid" }
            return s.afterMeanDb - s.beforeMeanDb
        }
        assertTrue(driverGain(10.0) > driverGain(0.1) + 0.2)
    }

    @Test
    fun validationPositionsDoNotChangeTheFitButAreReported() {
        val delays = { _: Seat, b: Band -> if (b == Band.MID) 0.001 else 0.0 }
        val dir = createTempDirectory("irb").toFile()
        val base = Fixtures.measurementSet(dir = dir, positions = listOf("centre"), delays = delays)
        val withHeld = base + Fixtures.measurementSet(dir = dir, positions = listOf("held"), delays = { _, b -> if (b == Band.MID) 0.004 else 0.0 }, validationPositions = setOf("held"))
        val a = Designer.design(MeasurementSet.load(base, DesignOptions()))
        val b = Designer.design(MeasurementSet.load(withHeld, DesignOptions()))
        for (bi in 0 until 3) for (ci in 0 until 2) for (t in a.impulses[bi][ci].indices) assertEquals(a.impulses[bi][ci][t], b.impulses[bi][ci][t], 1e-7f)
        assertTrue(b.report.positions.any { it.validationOnly })
    }

    @Test
    fun shiftLimitScalesLargeCorrections() {
        val ms = Fixtures.measurementSet(positions = listOf("c"), delays = { _, b -> if (b == Band.MID) 0.002 else 0.0 })
        val d = Designer.design(MeasurementSet.load(ms, DesignOptions(maxShiftMs = 0.3)))
        assertTrue(d.report.bands.all { it.maxGroupDelayChangeMs <= 0.3 + 1e-6 })
        assertTrue(d.report.bands.any { it.shiftLimitScale < 1 })
    }

    @Test
    fun invalidOptionsRejected() {
        assertFailsWith<IllegalArgumentException> { DesignOptions(taps = 3000).validate() }
        assertFailsWith<IllegalArgumentException> { DesignOptions(sampleRate = 44100, taps = 65536, latencyFrames = 32768).validate() }
        assertFailsWith<IllegalArgumentException> { DesignOptions(latencyFrames = 100).validate() }
        assertFailsWith<IllegalArgumentException> { DesignOptions(lowMidHz = 10.0).validate() }
        assertFailsWith<IllegalArgumentException> { DesignOptions(taps = 2048, latencyFrames = 1024, maxShiftMs = 20.0).validate() }
        DesignOptions().validate()
    }
}

class ExportTest {
    @Test
    fun packageMatchesJdspContract() {
        val ms = Fixtures.measurementSet(positions = listOf("c"), delays = { _, b -> if (b == Band.HIGH) 0.0001 else 0.0 })
        val d = Designer.design(MeasurementSet.load(ms, DesignOptions(taps = 8192, latencyFrames = 4096)))
        val dir = createTempDirectory("irb-out").toFile()
        val zip = Exporter.export(d, dir)
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }
            assertEquals(setOf("manifest.json", "report.json", "low.wav", "mid.wav", "high.wav", "README.txt"), names.toSet())
            assertTrue(names.size <= 16 && names.all { it.matches(Regex("[A-Za-z0-9_.-]+")) })
            val manifest = z.getInputStream(z.getEntry("manifest.json")).readBytes().decodeToString()
            for (key in listOf("\"format\": \"siphondsp-band-ir\"", "\"version\": 1", "\"correction_only\": true", "\"sample_rate\": 48000", "\"taps\": 8192", "\"latency_frames\": 4096", "\"low_mid\": 150.0", "\"mid_high\": 3500.0", "\"low\": \"low.wav\"")) {
                assertTrue(manifest.contains(key), "manifest missing $key\n$manifest")
            }
            for (band in listOf("low", "mid", "high")) {
                val bytes = z.getInputStream(z.getEntry("$band.wav")).readBytes()
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("WAVE", String(bytes, 8, 4)); assertEquals("fmt ", String(bytes, 12, 4))
                assertEquals(16, b.getInt(16)); assertEquals(3, b.getShort(20).toInt()); assertEquals(2, b.getShort(22).toInt())
                assertEquals(48000, b.getInt(24)); assertEquals(8, b.getShort(32).toInt()); assertEquals(32, b.getShort(34).toInt())
                assertEquals("data", String(bytes, 36, 4)); assertEquals(8192 * 8, b.getInt(40))
                // Oversampled peak (J_DSP's unity cap) stays at or below unity.
                val left = DoubleArray(8192) { b.getFloat(44 + it * 8).toDouble() }
                val s = Fft.rfft(left, 65536)
                assertTrue((0 until s.size).maxOf { s.abs(it) } <= 1.0)
            }
        }
        assertTrue(File(dir, "low.wav").isFile && File(dir, "README.txt").readText().contains("Per-band"))
    }
}

class ProjectAndTagsTest {
    @Test
    fun tagsParsedFromFileNames() {
        val t = FilenameTags.parse("driver_pos2_L_mid.txt")
        assertEquals(Seat.DRIVER, t.seat); assertEquals("pos2", t.position); assertEquals(Channel.L, t.channel); assertEquals(Band.MID, t.band)
        val u = FilenameTags.parse("Passenger Centre R combined.wav")
        assertEquals(Seat.PASSENGER, u.seat); assertEquals("centre", u.position); assertEquals(Channel.R, u.channel); assertEquals(Band.COMBINED, u.band)
        val v = FilenameTags.parse("measurement 12.txt")
        assertNull(v.seat); assertNull(v.band)
    }

    @Test
    fun projectRoundTrips() {
        val file = File(createTempDirectory("irb").toFile(), "p.jirb")
        val p = Project(measurements = listOf(Measurement("a.txt", Seat.PASSENGER, "c", Channel.R, Band.HIGH, "ref", true, -3.0)), options = DesignOptions(taps = 8192, latencyFrames = 4096))
        Project.save(p, file)
        val q = Project.load(file)
        assertEquals(p, q)
        assertNotNull(q.measurements.first())
    }
}
