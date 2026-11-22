package jdsp.irbuilder

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class StereoPositionReport(
    val seat: Seat,
    val position: String,
    val channel: Channel,
    val validationOnly: Boolean,
    /** RMS deviation of the summed phase from a pure delay over the stereo range, degrees. */
    val beforeRmsDeg: Double,
    val afterRmsDeg: Double,
    /** Spread (5th–95th percentile) of the summed group delay over the range, ms. */
    val beforeGroupDelaySpreadMs: Double,
    val afterGroupDelaySpreadMs: Double,
    val deteriorated: Boolean,
)

@Serializable
data class StereoSeatReport(
    val seat: Seat,
    val channel: Channel,
    val positions: Int,
    val beforeRmsDeg: Double,
    val afterRmsDeg: Double,
    val beforeGroupDelaySpreadMs: Double,
    val afterGroupDelaySpreadMs: Double,
)

@Serializable
data class StereoChannelReport(
    val channel: Channel,
    val corrected: Boolean,
    val maxPhaseDeg: Double,
    val maxGroupDelayChangeMs: Double,
    /** 1 means the solved correction was used as is; below 1 it was scaled to respect the shift limit. */
    val shiftLimitScale: Double,
    val magnitudeRippleDb: Double,
    val preRingingDb: Double,
)

@Serializable
data class StereoReport(
    val correctionType: String = "stereo_phase_linearisation",
    val rangeHz: List<Double>,
    val positions: List<StereoPositionReport>,
    val seats: List<StereoSeatReport>,
    val channels: List<StereoChannelReport>,
    val commonGainDb: Double,
    val latencyMs: Double,
    val jdspLatencyMs: Double,
    val prediction: String = "Linear prediction from the exported finite FIR taps and the imported Combined sweeps; not a post-install measurement.",
    val warnings: List<String>,
)

/**
 * A phase-only stereo IR for J_DSP's Stereo convolver slot, which sits after the band sum. It
 * applies to every band alike, so it can't change how the bands add at a crossover; it removes
 * the phase rotation of the summed response (crossovers and speakers) toward one arrival time.
 * Arrays are indexed by channel.
 */
class StereoDesign(
    val options: DesignOptions,
    val set: MeasurementSet,
    /** The designed correction phase on the grid, radians. */
    val correction: Array<DoubleArray>,
    /** Actual response of the exported taps with the common delay removed. */
    val responses: Array<CVec>,
    val impulses: Array<FloatArray>,
    val commonGainDb: Double,
    val report: StereoReport,
) {
    val freqs get() = set.freqs
    private val commonGain = Math.pow(10.0, commonGainDb / 20)

    /** Positions with a Combined sweep for [channel]. */
    fun groups(channel: Channel, includeValidation: Boolean = true) =
        set.groups.filter { it.key.channel == channel && Band.COMBINED in it.responses && (includeValidation || !it.validationOnly) }

    fun combined(g: LoadedGroup): CVec = g.responses.getValue(Band.COMBINED).response

    /** The Combined response after the exported IR (common delay and common gain removed). */
    fun predicted(g: LoadedGroup): CVec {
        val h = combined(g)
        val k = responses[g.key.channel.ordinal]
        val out = CVec(h.size)
        for (i in 0 until h.size) {
            out.re[i] = (h.re[i] * k.re[i] - h.im[i] * k.im[i]) / commonGain
            out.im[i] = (h.re[i] * k.im[i] + h.im[i] * k.re[i]) / commonGain
        }
        return out
    }

    /** The arrival delay [g]'s Combined file states (REW header delay or impulse peak), seconds. */
    fun delayHint(g: LoadedGroup): Double = Importer.delayHintSeconds(g.responses.getValue(Band.COMBINED), options.sampleRate)

    /** Deviation of [h]'s phase from the best-fitting pure delay over the stereo range, degrees. */
    fun excessPhaseDeg(h: CVec, hintSeconds: Double = 0.0): DoubleArray = StereoDesigner.excessPhase(h, freqs, options, hintSeconds).let { p -> DoubleArray(p.size) { Math.toDegrees(p[it]) } }

    /** Group delay of [h] relative to its arrival, ms (from its smoothed excess phase). */
    fun groupDelayMs(h: CVec, hintSeconds: Double = 0.0): DoubleArray = StereoDesigner.groupDelayMs(StereoDesigner.excessPhase(h, freqs, options, hintSeconds), freqs)
}

object StereoDesigner {
    fun inRange(f: Double, o: DesignOptions) = f >= o.stereoMinHz && f <= o.stereoMaxHz

    /** 1 inside the stereo range, a cosine fade over [DesignOptions.fadeOctaves] outside it, 0 beyond. */
    fun weight(f: Double, o: DesignOptions): Double {
        val d = when {
            f < o.stereoMinHz -> log2(o.stereoMinHz / f)
            f > o.stereoMaxHz -> log2(f / o.stereoMaxHz)
            else -> return 1.0
        }
        return if (d >= o.fadeOctaves) 0.0 else 0.5 * (1 + cos(PI * d / o.fadeOctaves))
    }

    /**
     * Unwrapped phase of [h] with its arrival time taken out, minus the straight line (pure delay)
     * that fits it best over the stereo range: what's left is the phase rotation to remove.
     */
    internal fun excessPhase(h: CVec, f: DoubleArray, o: DesignOptions, hintSeconds: Double = 0.0): DoubleArray {
        val tau = Importer.bulkDelaySeconds(h, f, hintSeconds)
        val p = unwrap(DoubleArray(f.size) { i ->
            val a = 2 * PI * f[i] * tau
            atan2(h.re[i] * sin(a) + h.im[i] * cos(a), h.re[i] * cos(a) - h.im[i] * sin(a))
        })
        return removeLinear(p, f, o)
    }

    /** [p] minus the line through its (1/12-octave averaged) values at the two ends of the range. */
    private fun removeEndpointLine(p: DoubleArray, f: DoubleArray, o: DesignOptions): DoubleArray {
        fun near(hz: Double) = f.indices.filter { abs(log2(f[it] / hz)) <= 1.0 / 24 }.let { idx -> idx.sumOf { p[it] } / idx.size to hz }
        val (p0, f0) = near(o.stereoMinHz)
        val (p1, f1) = near(o.stereoMaxHz)
        val slope = (p1 - p0) / (f1 - f0)
        return DoubleArray(p.size) { p[it] - (p0 + slope * (f[it] - f0)) }
    }

    private fun removeLinear(p: DoubleArray, f: DoubleArray, o: DesignOptions): DoubleArray {
        val idx = f.indices.filter { inRange(f[it], o) }
        if (idx.size < 2) return p.copyOf()
        val mf = idx.sumOf { f[it] } / idx.size
        val mp = idx.sumOf { p[it] } / idx.size
        val slope = idx.sumOf { (f[it] - mf) * (p[it] - mp) } / idx.sumOf { (f[it] - mf) * (f[it] - mf) }
        return DoubleArray(p.size) { p[it] - (mp + slope * (f[it] - mf)) }
    }

    internal fun groupDelayMs(phase: DoubleArray, f: DoubleArray): DoubleArray = DoubleArray(f.size) { i ->
        val a = (i - 1).coerceAtLeast(0)
        val b = (i + 1).coerceAtMost(f.size - 1)
        -(phase[b] - phase[a]) / (2 * PI * (f[b] - f[a])) * 1000
    }

    private fun rms(x: DoubleArray, idx: List<Int>) = sqrt(idx.sumOf { x[it] * x[it] } / maxOf(idx.size, 1))
    private fun spread(x: DoubleArray, idx: List<Int>): Double {
        if (idx.isEmpty()) return 0.0
        val v = idx.map { x[it] }.sorted()
        return v[(v.size * 95 / 100).coerceAtMost(v.size - 1)] - v[v.size * 5 / 100]
    }

    fun design(set: MeasurementSet): StereoDesign {
        val o = set.options
        o.validate()
        require(o.irMode == IrMode.STEREO) { "Stereo design needs the Stereo IR mode" }
        val checks = Checks.run(set, CheckScope.STEREO)
        require(checks.canDesign) { "Fix the errors on the Checks page first: " + checks.items.first { it.severity == Severity.ERROR }.title }
        val f = set.freqs
        val n = f.size
        val mask = DoubleArray(n) { weight(f[it], o) }
        val ptsPerOctave = (n - 1) / log2(f.last() / f.first())
        val sigma = o.smoothingOctaves * ptsPerOctave / 2
        val correction = Array(2) { DoubleArray(n) }
        val shiftScale = DoubleArray(2) { 1.0 }
        val correctedChannels = BooleanArray(2)

        for (ch in Channel.entries) {
            val ci = ch.ordinal
            val groups = set.groups.filter { it.key.channel == ch && !it.validationOnly && Band.COMBINED in it.responses }
            if (groups.isEmpty()) continue
            correctedChannels[ci] = true
            val counts = Seat.entries.associateWith { s -> groups.count { it.key.seat == s } }
            val w = groups.map { o.seatWeight(it.key.seat) / counts.getValue(it.key.seat) }.let { raw -> raw.map { it / raw.sum() } }
            // Seat-weighted average of each position's unit phasor with its own arrival removed,
            // so seats at different distances agree on the shape of the phase, not its delay.
            val zr = DoubleArray(n)
            val zi = DoubleArray(n)
            groups.forEachIndexed { p, g ->
                val r = g.responses.getValue(Band.COMBINED)
                val h = r.response
                val tau = Importer.bulkDelaySeconds(h, f, Importer.delayHintSeconds(r, o.sampleRate))
                for (i in 0 until n) {
                    val m = h.abs(i)
                    if (m <= 0.0) continue
                    val a = 2 * PI * f[i] * tau
                    val c = cos(a)
                    val s = sin(a)
                    zr[i] += w[p] * (h.re[i] * c - h.im[i] * s) / m
                    zi[i] += w[p] * (h.re[i] * s + h.im[i] * c) / m
                }
            }
            val avg = unwrap(DoubleArray(n) { atan2(zi[it], zr[it]) })
            // Reference: the straight line (a pure delay plus a constant) through the averaged phase
            // at both ends of the range, so the correction is ~0 where it fades out. A least-squares
            // line would leave large residuals at the ends, and fading those to zero in a short
            // low-frequency span would need tens of ms of shift.
            val excess = removeEndpointLine(avg, f, o)
            val c = DoubleArray(n) { -excess[it] * mask[it] }
            val sm = gaussianSmooth(c, sigma)
            for (j in 0 until n) sm[j] *= mask[j]
            val maxTau = Designer.maxGroupDelayMs(sm, f)
            if (maxTau > o.maxShiftMs) {
                shiftScale[ci] = o.maxShiftMs / maxTau
                for (j in 0 until n) sm[j] *= shiftScale[ci]
            }
            correction[ci] = sm
        }

        // FIR synthesis: unit-magnitude spectrum exp(jφ), one common delay, finite taps.
        val fftN = maxOf(65536, o.taps * 8)
        val fftF = Fft.rfftFreqs(fftN, o.sampleRate.toDouble())
        val window = tukey(o.taps, 0.2)
        val raw = Array(2) { ci ->
            val ph = interp(fftF, f, correction[ci], left = 0.0, right = 0.0)
            val spec = CVec(DoubleArray(ph.size) { cos(ph[it]) }, DoubleArray(ph.size) { sin(ph[it]) })
            spec.im[0] = 0.0
            spec.im[spec.size - 1] = 0.0
            val circ = Fft.irfft(spec, fftN)
            DoubleArray(o.taps) { t -> circ[((t - o.latencyFrames) % fftN + fftN) % fftN] * window[t] }
        }
        var peak = 0.0
        for (ci in 0 until 2) {
            val s = Fft.rfft(raw[ci], fftN)
            for (k in 0 until s.size) peak = maxOf(peak, s.abs(k))
        }
        val gain = minOf(1.0, 0.999 / maxOf(peak, 1e-15))
        val impulses = Array(2) { ci -> FloatArray(o.taps) { (raw[ci][it] * gain).toFloat() } }
        val responses = Array(2) { ci ->
            val x = DoubleArray(o.taps) { impulses[ci][it].toDouble() }
            val s = Fft.rfft(x, fftN).delayed(fftF, -o.latencyFrames.toDouble() / o.sampleRate)
            CVec(interp(f, fftF, s.re), interp(f, fftF, s.im))
        }
        val commonGainDb = db(gain)
        val idx = f.indices.filter { inRange(f[it], o) }

        val channels = Channel.entries.map { ch ->
            val ci = ch.ordinal
            val imp = impulses[ci]
            val total = imp.sumOf { it.toDouble() * it }
            val preEnd = o.latencyFrames - ((o.maxShiftMs + 0.5) * o.sampleRate / 1000).toInt()
            val pre = (0 until preEnd.coerceAtLeast(0)).sumOf { imp[it].toDouble() * imp[it] }
            StereoChannelReport(
                ch, correctedChannels[ci],
                maxPhaseDeg = Math.toDegrees(correction[ci].maxOf { abs(it) }).round(1),
                maxGroupDelayChangeMs = Designer.maxGroupDelayMs(correction[ci], f).round(3),
                shiftLimitScale = shiftScale[ci].round(3),
                magnitudeRippleDb = (0 until n).maxOf { abs(db(responses[ci].abs(it)) - commonGainDb) }.round(3),
                preRingingDb = (10 * kotlin.math.log10(maxOf(pre, 1e-30) / maxOf(total, 1e-30))).coerceAtLeast(-200.0).round(1),
            )
        }
        val partial = StereoDesign(o, set, correction, responses, impulses, commonGainDb,
            StereoReport(rangeHz = listOf(o.stereoMinHz, o.stereoMaxHz), positions = emptyList(), seats = emptyList(), channels = channels,
                commonGainDb = commonGainDb, latencyMs = o.latencyMs, jdspLatencyMs = o.jdspLatencyMs, warnings = emptyList()))

        val positions = set.groups.filter { Band.COMBINED in it.responses }.map { g ->
            val before = excessPhase(partial.combined(g), f, o, partial.delayHint(g))
            val after = excessPhase(partial.predicted(g), f, o, partial.delayHint(g))
            val bRms = Math.toDegrees(rms(before, idx))
            val aRms = Math.toDegrees(rms(after, idx))
            StereoPositionReport(
                g.key.seat, g.key.position, g.key.channel, g.validationOnly,
                bRms.round(1), aRms.round(1),
                spread(groupDelayMs(before, f), idx).round(3), spread(groupDelayMs(after, f), idx).round(3),
                deteriorated = aRms > bRms + 5,
            )
        }
        val seats = Seat.entries.flatMap { seat ->
            Channel.entries.mapNotNull { ch ->
                val ps = positions.filter { it.seat == seat && it.channel == ch && !it.validationOnly }
                if (ps.isEmpty()) null else StereoSeatReport(seat, ch, ps.size,
                    ps.map { it.beforeRmsDeg }.average().round(1), ps.map { it.afterRmsDeg }.average().round(1),
                    ps.map { it.beforeGroupDelaySpreadMs }.average().round(3), ps.map { it.afterGroupDelaySpreadMs }.average().round(3))
            }
        }
        val warnings = ArrayList<String>()
        warnings += "A stereo IR acts after the band sum, on every band alike: it can't fix cancellation between bands at a crossover. Use Band Align or the per-band IRs for that."
        for (c in channels) {
            if (!c.corrected) warnings += "${c.channel.label} channel: no Combined sweeps, so its IR is a pure delay (no correction)."
            else if (c.shiftLimitScale < 1) warnings += "${c.channel.label}: correction scaled to %.0f%% by the %.1f ms maximum-shift limit; low-frequency phase may need a larger limit (and accepts more pre-ringing).".format(c.shiftLimitScale * 100, o.maxShiftMs)
        }
        positions.filter { it.deteriorated }.forEach {
            warnings += "${it.seat.label} ${it.position} ${it.channel.label}${if (it.validationOnly) " (validation)" else ""}: predicted phase deviation gets worse."
        }
        warnings += "J_DSP's crossovers, PEQ, delays, polarity and gains must match the measured baseline exactly."
        warnings += "These are predictions. Re-measure both seats with the IR loaded before trusting the tune."
        val report = StereoReport(rangeHz = listOf(o.stereoMinHz, o.stereoMaxHz), positions = positions, seats = seats, channels = channels,
            commonGainDb = commonGainDb.round(3), latencyMs = o.latencyMs.round(2), jdspLatencyMs = o.jdspLatencyMs.round(2), warnings = warnings)
        return StereoDesign(o, set, correction, responses, impulses, commonGainDb, report)
    }
}
