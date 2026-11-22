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

/** Where correction is allowed: 1 inside each crossover region, a cosine fade, 0 elsewhere. */
object Regions {
    fun weight(f: Double, o: DesignOptions): Double = Crossover.entries.maxOf { xo ->
        val d = abs(log2(f / xo.hz(o)))
        when {
            d <= o.regionOctaves -> 1.0
            d <= o.regionOctaves + o.fadeOctaves -> 0.5 * (1 + cos(PI * (d - o.regionOctaves) / o.fadeOctaves))
            else -> 0.0
        }
    }

    /** True for the fully-active core of [xo]'s region, where the report scores the result. */
    fun inCore(f: Double, xo: Crossover, o: DesignOptions) = abs(log2(f / xo.hz(o))) <= o.regionOctaves
}

@Serializable
data class RegionMetrics(
    val crossover: String,
    val hz: Double,
    /** Mean summation efficiency in the region: 0 dB means the bands add fully coherently. */
    val beforeMeanDb: Double,
    val afterMeanDb: Double,
    /** Deepest point of the summation efficiency (cancellation dip) in the region. */
    val beforeWorstDb: Double,
    val afterWorstDb: Double,
    /** How much the summed level changes in the region (affects the PEQ already tuned). */
    val levelChangeMeanDb: Double,
    val levelChangeMaxDb: Double,
)

@Serializable
data class PositionReport(
    val seat: Seat,
    val position: String,
    val channel: Channel,
    val validationOnly: Boolean,
    val regions: List<RegionMetrics>,
    val deteriorated: Boolean,
    val combinedVsBranchSumRelativeError: Double? = null,
    val files: Map<String, String>,
)

@Serializable
data class SeatReport(val seat: Seat, val channel: Channel, val positions: Int, val regions: List<RegionMetrics>)

@Serializable
data class BandReport(
    val band: Band,
    val channel: Channel,
    val maxPhaseDeg: Double,
    val maxGroupDelayChangeMs: Double,
    /** 1 means the solved correction was used as is; below 1 it was scaled down to respect the shift limit. */
    val shiftLimitScale: Double,
    val magnitudeRippleDb: Double,
    val preRingingDb: Double,
)

@Serializable
data class DesignReport(
    val correctionType: String = "phase_only",
    val positions: List<PositionReport>,
    val seats: List<SeatReport>,
    val bands: List<BandReport>,
    val commonGainDb: Double,
    val latencyMs: Double,
    val jdspLatencyMs: Double,
    val prediction: String = "Linear prediction from the exported finite FIR taps and the imported measurements; not a post-install measurement.",
    val warnings: List<String>,
    /** J_DSP delay/polarity changes from Band Align the IRs were designed for; empty when none. */
    val jdspSettingChanges: List<JdspSettingChange> = emptyList(),
)

/** A J_DSP band output's delay/polarity change, as listed in the report and manifest. */
@Serializable
data class JdspSettingChange(
    val output: String,
    val delayMsFrom: Double,
    val delayMsTo: Double,
    val invertedFrom: Boolean,
    val invertedTo: Boolean,
) {
    val text: String
        get() = buildString {
            append(output).append(": ")
            val parts = ArrayList<String>()
            if (kotlin.math.abs(delayMsFrom - delayMsTo) > 1e-6) parts += "delay %.2f → %.2f ms".format(java.util.Locale.ROOT, delayMsFrom, delayMsTo)
            if (invertedFrom != invertedTo) parts += "polarity ${if (invertedTo) "normal → inverted" else "inverted → normal"}"
            append(parts.joinToString(", "))
        }

    companion object {
        fun of(c: SettingChange) = JdspSettingChange(c.label, c.from.delayMs, c.to.delayMs, c.from.inverted, c.to.inverted)
    }
}

/** Result of a phase-only design. Impulses are indexed [band][channel], responses [channel][band]. */
class PhaseDesign(
    val options: DesignOptions,
    val set: MeasurementSet,
    val designedPhase: Array<Array<DoubleArray>>,
    /** Actual response of the exported taps with the common delay removed. */
    val responses: Array<Array<CVec>>,
    val impulses: Array<Array<FloatArray>>,
    val commonGainDb: Double,
    val report: DesignReport,
    /** Band Align the IRs were designed on top of, when its changes were used. */
    val alignment: BandAlignReport? = null,
) {
    val freqs get() = set.freqs
    private val commonGain = Math.pow(10.0, commonGainDb / 20)

    /** Predicted response at a position after correction (common delay and common gain removed). */
    fun predicted(g: LoadedGroup): CVec {
        val ci = g.key.channel.ordinal
        val out = CVec(freqs.size)
        Band.BRANCHES.forEachIndexed { bi, b ->
            val h = g.branch(b)
            val k = responses[ci][bi]
            for (i in freqs.indices) {
                out.re[i] += (h.re[i] * k.re[i] - h.im[i] * k.im[i]) / commonGain
                out.im[i] += (h.re[i] * k.im[i] + h.im[i] * k.re[i]) / commonGain
            }
        }
        return out
    }

    /** Summation efficiency (dB, ≤ 0) of the branch sum vs the coherent maximum Σ|H|. */
    fun efficiencyDb(g: LoadedGroup, after: Boolean): DoubleArray {
        val s = if (after) predicted(g) else g.branchSum()
        return DoubleArray(freqs.size) { i -> db(s.abs(i)) - db(Band.BRANCHES.sumOf { g.branch(it).abs(i) }) }
    }

    /** Power average over a seat's positions: level of the band sum and its efficiency. */
    fun seatAverage(seat: Seat, channel: Channel, includeValidation: Boolean = false): SeatCurves? {
        val gs = set.groups.filter { it.key.seat == seat && it.key.channel == channel && it.isComplete && (includeValidation || !it.validationOnly) }
        if (gs.isEmpty()) return null
        val n = freqs.size
        val before = DoubleArray(n)
        val after = DoubleArray(n)
        val coherent = DoubleArray(n)
        for (g in gs) {
            val b = g.branchSum()
            val a = predicted(g)
            for (i in 0 until n) {
                before[i] += b.abs(i).let { it * it } / gs.size
                after[i] += a.abs(i).let { it * it } / gs.size
                coherent[i] += Band.BRANCHES.sumOf { g.branch(it).abs(i) }.let { it * it } / gs.size
            }
        }
        fun d(x: DoubleArray) = DoubleArray(n) { 10 * kotlin.math.log10(maxOf(x[it], 1e-24)) }
        return SeatCurves(d(before), d(after), d(coherent), gs.size)
    }
}

class SeatCurves(val beforeDb: DoubleArray, val afterDb: DoubleArray, val coherentDb: DoubleArray, val positions: Int) {
    val beforeEfficiencyDb get() = DoubleArray(beforeDb.size) { beforeDb[it] - coherentDb[it] }
    val afterEfficiencyDb get() = DoubleArray(afterDb.size) { afterDb[it] - coherentDb[it] }
}

object Designer {
    private const val SWEEPS = 200

    /**
     * Designs the phase IRs. With an [alignment] that has changes, the design runs on the
     * measurements as they will be once those J_DSP delay/polarity changes are made, and the
     * changes are listed in the report: the IRs are only right with them applied.
     */
    fun design(measured: MeasurementSet, alignment: BandAlignReport? = null): PhaseDesign {
        val used = alignment?.takeIf { it.hasChanges }
        val set = used?.alignedSet(measured) ?: measured
        val o = set.options
        o.validate()
        val checks = Checks.run(set)
        require(checks.canDesign) { "Fix the errors on the Checks page first: " + checks.items.first { it.severity == Severity.ERROR }.title }
        val f = set.freqs
        val n = f.size
        val mask = DoubleArray(n) { Regions.weight(f[it], o) }
        val ptsPerOctave = (n - 1) / log2(f.last() / f.first())
        val sigma = o.smoothingOctaves * ptsPerOctave / 2

        val phase = Array(2) { Array(3) { DoubleArray(n) } }
        val shiftScale = Array(2) { DoubleArray(3) { 1.0 } }
        for (ch in Channel.entries) {
            val ci = ch.ordinal
            val groups = set.groups.filter { it.key.channel == ch && it.isComplete && !it.validationOnly }
            val counts = Seat.entries.associateWith { s -> groups.count { it.key.seat == s } }
            val w = groups.map { o.seatWeight(it.key.seat) / counts.getValue(it.key.seat) }.let { raw -> raw.map { it / raw.sum() } }
            val kr = DoubleArray(3) { 1.0 }
            val ki = DoubleArray(3)
            val rr = Array(3) { DoubleArray(3) }
            val ri = Array(3) { DoubleArray(3) }
            for (j in 0 until n) {
                if (mask[j] <= 0.0) {
                    kr.fill(1.0); ki.fill(0.0)
                    continue
                }
                // R[b][c] = Σ_p w_p · conj(h_b) · h_c / Σ_b |h_b|²  (each position scale-free).
                for (b in 0 until 3) { rr[b].fill(0.0); ri[b].fill(0.0) }
                groups.forEachIndexed { p, g ->
                    val hr = DoubleArray(3) { g.branch(Band.BRANCHES[it]).re[j] }
                    val hi = DoubleArray(3) { g.branch(Band.BRANCHES[it]).im[j] }
                    val s = (0 until 3).sumOf { hr[it] * hr[it] + hi[it] * hi[it] }.coerceAtLeast(1e-30)
                    for (b in 0 until 3) for (c in 0 until 3) {
                        rr[b][c] += w[p] * (hr[b] * hr[c] + hi[b] * hi[c]) / s
                        ri[b][c] += w[p] * (hr[b] * hi[c] - hi[b] * hr[c]) / s
                    }
                }
                // Coordinate ascent on unit-modulus K: maximise K^H R K + 2λ Re ΣK, warm-started
                // from the neighbouring frequency so the phase stays continuous.
                for (sweep in 0 until SWEEPS) {
                    var moved = 0.0
                    for (b in 0 until 3) {
                        var sr = o.regularization
                        var si = 0.0
                        for (c in 0 until 3) if (c != b) {
                            sr += rr[b][c] * kr[c] - ri[b][c] * ki[c]
                            si += rr[b][c] * ki[c] + ri[b][c] * kr[c]
                        }
                        val m = hypot(sr, si)
                        if (m < 1e-15) continue
                        val nr = sr / m
                        val ni = si / m
                        moved = maxOf(moved, hypot(nr - kr[b], ni - ki[b]))
                        kr[b] = nr; ki[b] = ni
                    }
                    if (moved < 1e-10) break
                }
                for (b in 0 until 3) phase[ci][b][j] = atan2(ki[b], kr[b])
            }
            for (b in 0 until 3) {
                val p = unwrap(phase[ci][b])
                for (j in 0 until n) p[j] *= mask[j]
                val sm = gaussianSmooth(p, sigma)
                for (j in 0 until n) sm[j] *= mask[j]
                val maxTau = maxGroupDelayMs(sm, f)
                if (maxTau > o.maxShiftMs) {
                    val s = o.maxShiftMs / maxTau
                    shiftScale[ci][b] = s
                    for (j in 0 until n) sm[j] *= s
                }
                phase[ci][b] = sm
            }
        }

        // FIR synthesis: unit-magnitude spectrum exp(jφ), one common delay, finite taps.
        val fftN = maxOf(65536, o.taps * 8)
        val fftF = Fft.rfftFreqs(fftN, o.sampleRate.toDouble())
        val window = tukey(o.taps, 0.2)
        val raw = Array(3) { Array(2) { DoubleArray(o.taps) } }
        for (bi in 0 until 3) for (ci in 0 until 2) {
            val ph = interp(fftF, f, phase[ci][bi], left = 0.0, right = 0.0)
            val spec = CVec(DoubleArray(ph.size) { cos(ph[it]) }, DoubleArray(ph.size) { sin(ph[it]) })
            spec.im[0] = 0.0
            spec.im[spec.size - 1] = 0.0
            val circ = Fft.irfft(spec, fftN)
            val out = raw[bi][ci]
            for (t in 0 until o.taps) out[t] = circ[((t - o.latencyFrames) % fftN + fftN) % fftN] * window[t]
        }
        // One normalisation for all six channels (J_DSP's band mode shares one unity cap, and
        // per-file normalisation would change relative band gains).
        var peak = 0.0
        for (bi in 0 until 3) for (ci in 0 until 2) {
            val s = Fft.rfft(raw[bi][ci], fftN)
            for (k in 0 until s.size) peak = maxOf(peak, s.abs(k))
        }
        val gain = minOf(1.0, 0.999 / maxOf(peak, 1e-15))
        val impulses = Array(3) { bi -> Array(2) { ci -> FloatArray(o.taps) { (raw[bi][ci][it] * gain).toFloat() } } }
        val responses = Array(2) { ci ->
            Array(3) { bi ->
                val x = DoubleArray(o.taps) { impulses[bi][ci][it].toDouble() }
                val s = Fft.rfft(x, fftN).delayed(fftF, -o.latencyFrames.toDouble() / o.sampleRate)
                CVec(interp(f, fftF, s.re), interp(f, fftF, s.im))
            }
        }
        val commonGainDb = db(gain)

        val bands = ArrayList<BandReport>()
        for (ci in 0 until 2) for (bi in 0 until 3) {
            val r = responses[ci][bi]
            val ripple = (0 until n).maxOf { abs(db(r.abs(it)) - commonGainDb) }
            val imp = impulses[bi][ci]
            val total = imp.sumOf { it.toDouble() * it }
            val preEnd = o.latencyFrames - ((o.maxShiftMs + 0.5) * o.sampleRate / 1000).toInt()
            val pre = (0 until preEnd.coerceAtLeast(0)).sumOf { imp[it].toDouble() * imp[it] }
            bands += BandReport(
                band = Band.BRANCHES[bi], channel = Channel.entries[ci],
                maxPhaseDeg = Math.toDegrees(phase[ci][bi].maxOf { abs(it) }).round(1),
                maxGroupDelayChangeMs = maxGroupDelayMs(phase[ci][bi], f).round(3),
                shiftLimitScale = shiftScale[ci][bi].round(3),
                magnitudeRippleDb = ripple.round(3),
                preRingingDb = (10 * kotlin.math.log10(maxOf(pre, 1e-30) / maxOf(total, 1e-30))).coerceAtLeast(-200.0).round(1),
            )
        }
        val partial = PhaseDesign(o, set, phase, responses, impulses, commonGainDb,
            DesignReport(positions = emptyList(), seats = emptyList(), bands = bands, commonGainDb = commonGainDb, latencyMs = o.latencyMs, jdspLatencyMs = o.jdspLatencyMs, warnings = emptyList()))

        val positions = set.groups.filter { it.isComplete }.map { g ->
            val before = partial.efficiencyDb(g, after = false)
            val after = partial.efficiencyDb(g, after = true)
            val sumBefore = g.branchSum()
            val sumAfter = partial.predicted(g)
            val regions = Crossover.entries.map { xo ->
                val idx = (0 until n).filter { Regions.inCore(f[it], xo, o) }
                val change = idx.map { db(sumAfter.abs(it)) - db(sumBefore.abs(it)) }
                RegionMetrics(
                    xo.label, xo.hz(o),
                    idx.map { before[it] }.average().round(2), idx.map { after[it] }.average().round(2),
                    idx.minOf { before[it] }.round(2), idx.minOf { after[it] }.round(2),
                    change.average().round(2), change.max().round(2),
                )
            }
            val combined = g.responses[Band.COMBINED]?.response?.let { c ->
                var num = 0.0
                var den = 0.0
                for (i in 0 until n) {
                    num += (c.re[i] - sumBefore.re[i]).let { it * it } + (c.im[i] - sumBefore.im[i]).let { it * it }
                    den += c.re[i] * c.re[i] + c.im[i] * c.im[i]
                }
                sqrt(num / maxOf(den, 1e-24)).round(3)
            }
            PositionReport(
                g.key.seat, g.key.position, g.key.channel, g.validationOnly, regions,
                deteriorated = regions.any { it.afterMeanDb < it.beforeMeanDb - 0.25 || it.afterWorstDb < it.beforeWorstDb - 1.0 },
                combinedVsBranchSumRelativeError = combined,
                files = g.measurements.entries.associate { it.key.fileName to it.value.path },
            )
        }
        val seats = Seat.entries.flatMap { seat ->
            Channel.entries.mapNotNull { ch ->
                val c = partial.seatAverage(seat, ch) ?: return@mapNotNull null
                val be = c.beforeEfficiencyDb
                val ae = c.afterEfficiencyDb
                SeatReport(seat, ch, c.positions, Crossover.entries.map { xo ->
                    val idx = (0 until n).filter { Regions.inCore(f[it], xo, o) }
                    val change = idx.map { c.afterDb[it] - c.beforeDb[it] }
                    RegionMetrics(xo.label, xo.hz(o),
                        idx.map { be[it] }.average().round(2), idx.map { ae[it] }.average().round(2),
                        idx.minOf { be[it] }.round(2), idx.minOf { ae[it] }.round(2),
                        change.average().round(2), change.max().round(2))
                })
            }
        }
        val warnings = ArrayList<String>()
        warnings += "J_DSP's crossovers, PEQ, delays, polarity and gains must match the measured baseline exactly."
        warnings += "Mono bass and cross-channel routing must be off during measurement and playback."
        warnings += "These are predictions. Re-measure both seats with the IRs loaded before trusting the tune."
        positions.filter { it.deteriorated }.forEach {
            warnings += "${it.seat.label} ${it.position} ${it.channel.label}${if (it.validationOnly) " (validation)" else ""}: predicted to get worse in at least one crossover region."
        }
        bands.filter { it.shiftLimitScale < 1 }.forEach {
            warnings += "${it.channel.label} ${it.band.label}: correction scaled to %.0f%% by the maximum-shift limit.".format(it.shiftLimitScale * 100)
        }
        val rise = seats.flatMap { s -> s.regions.map { s to it } }.maxByOrNull { it.second.levelChangeMaxDb }
        if (rise != null && rise.second.levelChangeMaxDb > 2) {
            warnings += "Summed level rises up to %.1f dB near the %s crossover (%s %s seat average): re-check the PEQ there after installing.".format(
                rise.second.levelChangeMaxDb, rise.second.crossover, rise.first.seat.label, rise.first.channel.label)
        }
        if (positions.any { (it.combinedVsBranchSumRelativeError ?: 0.0) > 0.25 }) {
            warnings += "A combined sweep disagrees with its band sum; check timing, gain, routing and dynamics."
        }
        if (used != null) {
            warnings.add(0, "Designed for the Band Align changes: make them in J_DSP before loading these IRs, or the IRs are wrong for your tune.")
        }
        val report = DesignReport(positions = positions, seats = seats, bands = bands, commonGainDb = commonGainDb.round(3),
            latencyMs = o.latencyMs.round(2), jdspLatencyMs = o.jdspLatencyMs.round(2), warnings = warnings,
            jdspSettingChanges = used?.changes?.map(JdspSettingChange::of).orEmpty())
        return PhaseDesign(o, set, phase, responses, impulses, commonGainDb, report, used)
    }

    internal fun maxGroupDelayMs(phase: DoubleArray, f: DoubleArray): Double {
        var m = 0.0
        for (j in 0 until phase.size - 1) {
            val tau = -(phase[j + 1] - phase[j]) / (2 * PI * (f[j + 1] - f[j]))
            m = maxOf(m, abs(tau))
        }
        return m * 1000
    }
}
