package jdsp.irbuilder

import kotlinx.serialization.Serializable
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** J_DSP's crossover slopes (its crossover type dropdown). */
@Serializable
enum class XoType(val label: String, val dbPerOctave: Int) {
    BW1("BW1", 6),
    BW2("BW2", 12),
    BW3("BW3", 18),
    LR4("LR4", 24),
    BW4("BW4", 24);

    val longLabel: String get() = "$label ($dbPerOctave dB/oct)"
}

/** One J_DSP crossover filter: its corner and slope. */
@Serializable
data class XoFilter(val hz: Double, val type: XoType = XoType.LR4) {
    val label: String get() = "${trimHz(hz)} Hz ${type.label}"
}

private fun trimHz(hz: Double) = if (abs(hz - hz.roundToInt()) < 1e-6) hz.roundToInt().toString() else "%.1f".format(java.util.Locale.ROOT, hz)

/**
 * The four J_DSP crossover filters the finder can move, each with its J_DSP slider range and step
 * (Crossover pages: Low lowpass 80–320 Hz, Mid highpass 60–320 Hz, Mid lowpass 300–8000 Hz,
 * High highpass 1000–8000 Hz).
 */
@Serializable
enum class XoSlot(val label: String, val band: Band, val highpass: Boolean, val crossover: Crossover, val minHz: Double, val maxHz: Double, val stepHz: Double) {
    LOW_LOWPASS("Low lowpass", Band.LOW, false, Crossover.LOW_MID, 80.0, 320.0, 1.0),
    MID_HIGHPASS("Mid highpass", Band.MID, true, Crossover.LOW_MID, 60.0, 320.0, 1.0),
    MID_LOWPASS("Mid lowpass", Band.MID, false, Crossover.MID_HIGH, 300.0, 8000.0, 10.0),
    HIGH_HIGHPASS("High highpass", Band.HIGH, true, Crossover.MID_HIGH, 1000.0, 8000.0, 10.0);

    fun round(hz: Double) = ((hz / stepHz).roundToInt() * stepHz).coerceIn(minHz, maxHz)

    companion object {
        fun of(xo: Crossover): Pair<XoSlot, XoSlot> =
            if (xo == Crossover.LOW_MID) LOW_LOWPASS to MID_HIGHPASS else MID_LOWPASS to HIGH_HIGHPASS
    }
}

/**
 * J_DSP's crossover filters exactly as its engine builds them (NativeBmwDspProcessor, mirrored by
 * J_DSP's BiquadCascade): RBJ Butterworth sections and a 1-pole TPT section, cascaded per slope.
 * BW1 = 1-pole; BW2 = one section, Q 0.7071; BW3 = 1-pole + section Q 1.0; LR4 = two sections
 * Q 0.7071; BW4 = sections Q 0.5412 and 1.3066. Corners are held to 20 Hz…0.49·fs. Each pair runs
 * exactly as set (no auto-pairing).
 */
object JdspFilters {
    private const val FREQ_MIN = 20.0
    private const val NYQUIST_FRACTION = .49
    private const val Q2 = .7071067812
    private const val Q3 = 1.0
    private const val Q4_LOW = 0.5411961
    private const val Q4_HIGH = 1.3065630

    /** One section: b0, b1, b2, a1, a2 (a0 = 1). */
    private fun biquad(fc: Double, q: Double, fs: Double, high: Boolean): DoubleArray {
        val w = 2.0 * PI * fc.coerceIn(FREQ_MIN, fs * NYQUIST_FRACTION) / fs
        val c = cos(w)
        val alpha = sin(w) / (2.0 * q)
        val d = 1.0 + alpha
        return if (high) {
            val b0 = ((1.0 + c) * .5) / d
            doubleArrayOf(b0, -(1.0 + c) / d, b0, (-2.0 * c) / d, (1.0 - alpha) / d)
        } else {
            val b0 = ((1.0 - c) * .5) / d
            doubleArrayOf(b0, (1.0 - c) / d, b0, (-2.0 * c) / d, (1.0 - alpha) / d)
        }
    }

    private fun onePole(fc: Double, fs: Double, high: Boolean): DoubleArray {
        val w = 2.0 * PI * fc.coerceIn(FREQ_MIN, fs * NYQUIST_FRACTION) / fs
        val g = tan(w * .5)
        val a = g / (1.0 + g)
        return if (high) doubleArrayOf(1.0 - a, -(1.0 - a), 0.0, 2.0 * a - 1.0, 0.0)
        else doubleArrayOf(a, a, 0.0, 2.0 * a - 1.0, 0.0)
    }

    fun sections(f: XoFilter, high: Boolean, fs: Double): List<DoubleArray> = when (f.type) {
        XoType.BW1 -> listOf(onePole(f.hz, fs, high))
        XoType.BW2 -> listOf(biquad(f.hz, Q2, fs, high))
        XoType.BW3 -> listOf(onePole(f.hz, fs, high), biquad(f.hz, Q3, fs, high))
        XoType.LR4 -> listOf(biquad(f.hz, Q2, fs, high), biquad(f.hz, Q2, fs, high))
        XoType.BW4 -> listOf(biquad(f.hz, Q4_LOW, fs, high), biquad(f.hz, Q4_HIGH, fs, high))
    }

    /** The Low band's subsonic high-pass: LR4 is two Butterworth sections, BW2 one. */
    fun subsonic(hz: Double, bw2: Boolean, fs: Double): List<DoubleArray> =
        List(if (bw2) 1 else 2) { biquad(hz, Q2, fs, high = true) }

    /** The cascade's complex response on [freqs]. */
    fun response(sections: List<DoubleArray>, freqs: DoubleArray, fs: Double): CVec {
        val out = CVec(freqs.size)
        for (i in freqs.indices) {
            val w = 2 * PI * freqs[i] / fs
            val c1 = cos(w); val s1 = -sin(w)
            val c2 = cos(2 * w); val s2 = -sin(2 * w)
            var re = 1.0
            var im = 0.0
            for (s in sections) {
                val nr = s[0] + s[1] * c1 + s[2] * c2
                val ni = s[1] * s1 + s[2] * s2
                val dr = 1.0 + s[3] * c1 + s[4] * c2
                val di = s[3] * s1 + s[4] * s2
                val d = dr * dr + di * di
                val hr = (nr * dr + ni * di) / d
                val hi = (ni * dr - nr * di) / d
                val r = re * hr - im * hi
                im = re * hi + im * hr
                re = r
            }
            out.re[i] = re
            out.im[i] = im
        }
        return out
    }

    fun response(f: XoFilter, high: Boolean, freqs: DoubleArray, fs: Double) = response(sections(f, high, fs), freqs, fs)
}

/** J_DSP's crossover settings (the same on both sides: its Crossover pages link L and R). */
@Serializable
data class JdspCrossovers(
    val lowLowpass: XoFilter = XoFilter(150.0),
    val midHighpass: XoFilter = XoFilter(150.0),
    /** 3-way: Mid's upper lowpass and the High band are on. */
    val threeWay: Boolean = true,
    val midLowpass: XoFilter = XoFilter(3500.0),
    val highHighpass: XoFilter = XoFilter(3500.0),
    val subsonic: Boolean = false,
    val subsonicHz: Double = 30.0,
    /** Subsonic slope BW2 (12 dB/oct); off is LR4 (24 dB/oct, J_DSP's default). */
    val subsonicBw2: Boolean = false,
) {
    fun get(s: XoSlot): XoFilter = when (s) {
        XoSlot.LOW_LOWPASS -> lowLowpass
        XoSlot.MID_HIGHPASS -> midHighpass
        XoSlot.MID_LOWPASS -> midLowpass
        XoSlot.HIGH_HIGHPASS -> highHighpass
    }

    fun with(s: XoSlot, f: XoFilter): JdspCrossovers = when (s) {
        XoSlot.LOW_LOWPASS -> copy(lowLowpass = f)
        XoSlot.MID_HIGHPASS -> copy(midHighpass = f)
        XoSlot.MID_LOWPASS -> copy(midLowpass = f)
        XoSlot.HIGH_HIGHPASS -> copy(highHighpass = f)
    }

    /** The crossover filters on [band]'s software branch. */
    fun slots(band: Band): List<XoSlot> = when (band) {
        Band.LOW -> listOf(XoSlot.LOW_LOWPASS)
        Band.MID -> if (threeWay) listOf(XoSlot.MID_HIGHPASS, XoSlot.MID_LOWPASS) else listOf(XoSlot.MID_HIGHPASS)
        Band.HIGH -> listOf(XoSlot.HIGH_HIGHPASS)
        Band.COMBINED -> emptyList()
    }

    /** [band]'s whole J_DSP filter chain (crossovers, plus the subsonic on Low). */
    fun chain(band: Band, fs: Double): List<DoubleArray> =
        slots(band).flatMap { JdspFilters.sections(get(it), it.highpass, fs) } +
            if (band == Band.LOW && subsonic) JdspFilters.subsonic(subsonicHz, subsonicBw2, fs) else emptyList()

    /** The crossover corners the sweeps are checked against (geometric mean of each pair). */
    val lowMidHz: Double get() = sqrt(lowLowpass.hz * midHighpass.hz)
    val midHighHz: Double get() = sqrt(midLowpass.hz * highHighpass.hz)

    fun validate() {
        for (s in XoSlot.entries) {
            if (!threeWay && s.crossover == Crossover.MID_HIGH) continue
            val f = get(s).hz
            require(f.isFinite() && f in s.minHz..s.maxHz) { "${s.label} must be ${trimHz(s.minHz)}–${trimHz(s.maxHz)} Hz (J_DSP's range)" }
        }
        if (subsonic) require(subsonicHz.isFinite() && subsonicHz in 20.0..60.0) { "Subsonic must be 20–60 Hz (J_DSP's range)" }
        if (threeWay) require(midLowpass.hz > midHighpass.hz * 2) { "Mid lowpass must be more than an octave above Mid highpass" }
    }
}

/** How far the finder may move one filter: the user's speaker-safety limits and allowed slopes. */
@Serializable
data class SlotLimits(val minHz: Double, val maxHz: Double, val types: Set<XoType>)

/** Crossover Finder settings saved with the project. */
@Serializable
data class FinderOptions(
    /** J_DSP's crossovers during the sweeps. */
    val current: JdspCrossovers = JdspCrossovers(),
    /** J_DSP's band delays and polarity during the sweeps. */
    val timing: JdspTiming = JdspTiming(),
    val lowMid: Boolean = true,
    val midHigh: Boolean = true,
    /** User limits per filter; a filter without an entry uses [defaultLimits]. */
    val limits: Map<XoSlot, SlotLimits> = emptyMap(),
) {
    fun limits(s: XoSlot): SlotLimits = limits[s] ?: defaultLimits(s, current.get(s))

    fun withLimits(s: XoSlot, l: SlotLimits) = copy(limits = limits + (s to l))

    /** The crossovers switched on and available (Mid/High needs J_DSP's 3-way split). */
    val crossovers: List<Crossover> get() = Crossover.entries.filter {
        if (it == Crossover.LOW_MID) lowMid else midHigh && current.threeWay
    }

    fun validate() {
        current.validate()
        timing.validate()
        require(crossovers.isNotEmpty()) { if (midHigh && !current.threeWay) "Mid/High needs J_DSP's 3-way split: switch on 3-way or Low/Mid" else "Switch on Low/Mid, Mid/High or both" }
        for (xo in crossovers) for (s in XoSlot.of(xo).toList()) {
            val l = limits(s)
            require(l.minHz.isFinite() && l.maxHz.isFinite() && l.minHz <= l.maxHz) { "${s.label}: lowest must not be above highest" }
            require(l.minHz >= s.minHz - 1e-9 && l.maxHz <= s.maxHz + 1e-9) { "${s.label} limits must be within J_DSP's ${trimHz(s.minHz)}–${trimHz(s.maxHz)} Hz" }
            require(l.types.isNotEmpty()) { "${s.label}: allow at least one slope" }
        }
    }

    companion object {
        /**
         * Half an octave either way within J_DSP's range; any slope for a lowpass, but a highpass
         * no shallower than it is now, since a shallower highpass sends a driver more low end.
         */
        fun defaultLimits(s: XoSlot, cur: XoFilter): SlotLimits = SlotLimits(
            minHz = s.round(max(s.minHz, cur.hz / sqrt(2.0))),
            maxHz = s.round(min(s.maxHz, cur.hz * sqrt(2.0))),
            types = if (s.highpass) XoType.entries.filter { it.dbPerOctave >= cur.type.dbPerOctave }.toSet() else XoType.entries.toSet(),
        )
    }
}

/** One channel's best delay and polarity for the chosen crossover, and how the seats fare. */
class FinderChannel(
    val channel: Channel,
    /** The band moved against Mid (Low at Low/Mid, High at Mid/High). */
    val moving: Band,
    val current: BandSetting,
    val proposed: BandSetting,
    /** Seat-weighted mean improvement over the scored band, dB (dips counted double). */
    val scoreDb: Double,
    val seats: List<AlignSeatCurve>,
    /** Per seat: the flat-sum target (dB), what a perfectly coherent band sum would give. */
    val targetDb: List<DoubleArray>,
    val positions: List<AlignPositionScore>,
    val delayAtLimit: Boolean,
)

/** One crossover option the search found, best first. */
data class FinderOption(val lower: XoFilter, val upper: XoFilter, val scoreDb: Double)

class FinderResult(
    val crossover: Crossover,
    val currentLower: XoFilter,
    val currentUpper: XoFilter,
    /** The proposal: the current filters when nothing beats them by [CrossoverFinder.MIN_IMPROVEMENT_DB]. */
    val lower: XoFilter,
    val upper: XoFilter,
    val changed: Boolean,
    val scoreDb: Double,
    val bandHz: ClosedFloatingPointRange<Double>,
    val channels: List<FinderChannel>,
    /** Next-best options, for comparison. */
    val alternatives: List<FinderOption>,
    /** Filters whose proposal sits at the edge of what was searched. */
    val atSearchEdge: List<XoSlot>,
    val evaluated: Int,
    /** Candidates left out because they'd lean on data the sweeps can't vouch for. */
    val unreliable: Int,
)

/** A filter to change in J_DSP. */
data class FilterChange(val slot: XoSlot, val from: XoFilter, val to: XoFilter)

class FinderReport(
    val freqs: DoubleArray,
    val results: List<FinderResult>,
    val newCrossovers: JdspCrossovers,
    val filterChanges: List<FilterChange>,
    val sharedOffsetMs: Double,
    val newTiming: JdspTiming?,
    val timingChanges: List<SettingChange>,
    val error: String?,
) {
    val hasChanges get() = newTiming != null && (filterChanges.isNotEmpty() || timingChanges.isNotEmpty())
}

/**
 * Crossover Finder: which J_DSP crossover (corner and slope of each side) and which delay and
 * polarity make the bands add up best through each crossover at both seats.
 *
 * It reuses the normal band-solo sweeps. Each sweep was taken through J_DSP's current crossover,
 * so a candidate is the measured band with the current filter divided out exactly and the
 * candidate filter put in: H · X_new / X_now, both computed as J_DSP builds them. Everything else in
 * the measurement (drivers, factory crossover, cabin) stays as measured. A candidate is only
 * considered where the sweeps can vouch for it: where a band's sweep is more than 45 dB below
 * its peak, or wasn't swept at all, a candidate may not lift it, beyond what stays 30 dB down.
 *
 * Each candidate is scored against a flat-sum target: for each position, the level the bands
 * would give summed perfectly in phase through the current filters, sqrt(Σ|H_b|² / Σ|X_b|²). Its
 * band sum, power-smoothed ±1/24 octave, is compared with the target over the crossover region:
 * peaks count once, dips double (a dip is cancellation that EQ can't fill). The improvement over
 * the current setting is averaged per seat; a candidate that leaves any seat worse is rejected, and
 * the winner must be at least [MIN_IMPROVEMENT_DB] better. Delay and polarity per side are searched
 * with each candidate like Band Align (0–2.8 ms, 0.01 ms steps), since a new filter shifts the
 * bands' phase. Low/Mid is solved first; Mid/High then sees its result.
 *
 * Frequencies are searched in 1/12-octave steps up to an octave either way, within the user's
 * limits (the tool can't know what a driver can safely take) and J_DSP's ranges; a lower lowpass
 * and upper highpass may differ by up to half an octave (overlap or gap).
 */
object CrossoverFinder {
    const val MIN_IMPROVEMENT_DB = 0.5
    /** Below this, relative to a band's peak, its sweep is treated as noise. */
    const val TRUST_DB = -45.0
    /** A candidate may lift untrusted data only while the result stays this far down. */
    const val NEGLIGIBLE_DB = -30.0
    /** Largest gap or overlap between a crossover's two corners, octaves. */
    const val MAX_SPLIT_OCTAVES = 0.5
    /** Options within this of the best count as equal; the smallest change among them wins. */
    private const val TIE_DB = 0.05
    private const val STAGE2_KEEP = 6

    fun solve(set: MeasurementSet, f: FinderOptions): FinderReport {
        f.validate()
        val o = set.options
        val freqs = set.freqs
        val fs = o.sampleRate.toDouble()
        var working = f.current
        var timing = f.timing
        val results = ArrayList<FinderResult>()
        val proposals = ArrayList<Triple<Band, Channel, BandSetting>>()
        for (xo in f.crossovers) {
            val r = solveCrossover(set, f, xo, working, timing, fs) ?: continue
            results += r
            working = working.with(XoSlot.of(xo).first, r.lower).with(XoSlot.of(xo).second, r.upper)
            for (c in r.channels) if (c.proposed != c.current) {
                timing = timing.with(c.moving, c.channel, c.proposed)
                proposals += Triple(c.moving, c.channel, c.proposed)
            }
        }
        val filterChanges = XoSlot.entries.mapNotNull { s ->
            val a = f.current.get(s)
            val b = working.get(s)
            if (a != b) FilterChange(s, a, b) else null
        }
        val t = BandAlign.assemble(f.timing, proposals)
        return FinderReport(freqs, results, working, filterChanges, t.offsetMs, t.timing, t.changes, t.error)
    }

    /** Everything about one position the search needs, on the full grid. */
    private class Pos(
        val key: GroupKey,
        val validationOnly: Boolean,
        /** Each band as it plays in the working state (earlier stages applied). */
        val bands: Map<Band, CVec>,
        /** Flat-sum target power, |E|². */
        val target: DoubleArray,
        /** Per band: where its sweep is trustworthy, and its peak level. */
        val trusted: Map<Band, BooleanArray>,
        val covered: Map<Band, BooleanArray>,
        val measured: Map<Band, DoubleArray>,
        val peak: Map<Band, Double>,
    )

    private class Cand(val lower: XoFilter, val upper: XoFilter, val lowerIdx: Int, val upperIdx: Int)

    /** The best delay per channel for one candidate, and the score. */
    private class Eval(val cand: Cand, val score: Double, val delays: Map<Channel, BandSetting>)

    private fun solveCrossover(set: MeasurementSet, f: FinderOptions, xo: Crossover, working: JdspCrossovers, timing: JdspTiming, fs: Double): FinderResult? {
        val o = set.options
        val freqs = set.freqs
        val n = freqs.size
        val (loSlot, upSlot) = XoSlot.of(xo)
        val moving = if (xo == Crossover.LOW_MID) Band.LOW else Band.HIGH
        val other = if (xo == Crossover.LOW_MID) Band.HIGH else Band.LOW

        // The original chains (in the sweeps) and the working chains (earlier stages applied).
        val origChain = Band.BRANCHES.associateWith { JdspFilters.response(f.current.chain(it, fs), freqs, fs) }
        val workSlot = { s: XoSlot -> JdspFilters.response(working.get(s), s.highpass, freqs, fs) }
        val origSlot = { s: XoSlot -> JdspFilters.response(f.current.get(s), s.highpass, freqs, fs) }

        val channels = Channel.entries.mapNotNull { ch ->
            val groups = set.groups.filter { it.key.channel == ch && it.has(listOf(xo.lower, xo.upper)) }
            if (groups.none { !it.validationOnly }) null else ch to groups
        }
        if (channels.isEmpty()) return null

        val pos = channels.associate { (ch, groups) ->
            ch to groups.map { g ->
                val bands = Band.BRANCHES.associateWith { b ->
                    var h = g.branch(b)
                    for (s in f.current.slots(b)) if (working.get(s) != f.current.get(s)) h = h.times(workSlot(s)).div(origSlot(s))
                    val was = f.timing.get(b, ch)
                    val now = timing.get(b, ch)
                    shift(h, freqs, (now.delayMs - was.delayMs) / 1000.0, if (now.inverted != was.inverted) -1.0 else 1.0)
                }
                val present = Band.BRANCHES.filter { it in g.responses }
                val target = DoubleArray(n) { i ->
                    var num = 0.0
                    var den = 0.0
                    for (b in present) {
                        val h = g.branch(b)
                        num += h.re[i] * h.re[i] + h.im[i] * h.im[i]
                        val x = origChain.getValue(b)
                        den += x.re[i] * x.re[i] + x.im[i] * x.im[i]
                    }
                    num / max(den, 1e-30)
                }
                val measured = present.associateWith { b -> DoubleArray(n) { g.branch(b).abs(it) } }
                val peak = measured.mapValues { it.value.max() }
                val covered = present.associateWith { b -> val c = g.responses.getValue(b).coverageHz; BooleanArray(n) { freqs[it] >= c.start * 0.9999 && freqs[it] <= c.endInclusive * 1.0001 } }
                val trustLevel = Math.pow(10.0, TRUST_DB / 20)
                val trusted = present.associateWith { b -> BooleanArray(n) { covered.getValue(b)[it] && measured.getValue(b)[it] >= peak.getValue(b) * trustLevel } }
                Pos(g.key, g.validationOnly, bands, target, trusted, covered, measured, peak)
            }
        }

        // Candidate filters per slot: a 1/12-octave lattice around the current corner (k = 0).
        fun lattice(s: XoSlot): List<Pair<Int, Double>> {
            val cur = working.get(s).hz
            val l = f.limits(s)
            val seen = HashSet<Double>()
            return (-12..12).mapNotNull { k ->
                val hz = if (k == 0) cur else s.round(cur * Math.pow(2.0, k / 12.0))
                if (k != 0 && (hz < l.minHz - 1e-9 || hz > l.maxHz + 1e-9)) null
                else if (!seen.add(hz)) null else k to hz
            }
        }
        val loFreqs = lattice(loSlot)
        val upFreqs = lattice(upSlot)
        val curLo = working.get(loSlot)
        val curUp = working.get(upSlot)
        fun types(s: XoSlot, cur: XoFilter) = (f.limits(s).types + cur.type).sortedBy { it.ordinal }

        // The scored region: half an octave beyond the corners searched, either way.
        val allHz = loFreqs.map { it.second } + upFreqs.map { it.second }
        val band = max(allHz.min() / 2, freqs.first())..min(allHz.max() * 2, freqs.last())

        // Each slot candidate's ratio to the working filter, and whether the sweeps vouch for it.
        val negligible = Math.pow(10.0, NEGLIGIBLE_DB / 20)
        class SlotCand(val filter: XoFilter, val k: Int, val ratio: CVec, val reliable: Boolean)
        fun slotCands(s: XoSlot, lat: List<Pair<Int, Double>>, cur: XoFilter): List<SlotCand> {
            val work = workSlot(s)
            val orig = origSlot(s)
            return types(s, cur).flatMap { t -> lat.map { (k, hz) ->
                val fl = XoFilter(hz, t)
                val x = JdspFilters.response(fl, s.highpass, freqs, fs)
                val ratio = x.div(work)
                val fromOrig = x.div(orig)
                val reliable = fl == cur || pos.values.flatten().filter { !it.validationOnly }.all { p ->
                    val cov = p.covered[s.band] ?: return@all true
                    val tr = p.trusted.getValue(s.band)
                    val m = p.measured.getValue(s.band)
                    val pk = p.peak.getValue(s.band)
                    (0 until n).all { i ->
                        val r = fromOrig.abs(i)
                        when {
                            tr[i] || r <= 1.122 -> true
                            !cov[i] -> x.abs(i) <= negligible
                            else -> m[i] * r <= pk * negligible
                        }
                    }
                }
                SlotCand(fl, k, ratio, reliable)
            } }
        }
        val loCands = slotCands(loSlot, loFreqs, curLo)
        val upCands = slotCands(upSlot, upFreqs, curUp)
        fun split(a: SlotCand, b: SlotCand) = abs(log2(b.filter.hz / a.filter.hz))
        val curSplit = abs(log2(curUp.hz / curLo.hz))
        val pairs = ArrayList<Pair<SlotCand, SlotCand>>()
        var unreliable = 0
        for (a in loCands) for (b in upCands) {
            val isCurrent = a.filter == curLo && b.filter == curUp
            if (!isCurrent && split(a, b) > max(MAX_SPLIT_OCTAVES, curSplit) + 1e-9) continue
            if (!a.reliable || !b.reliable) { unreliable++; continue }
            pairs += a to b
        }
        val current = pairs.first { it.first.filter == curLo && it.second.filter == curUp }

        // Score evaluation on a subset of grid points.
        class Grid(val idx: IntArray, val half: Int, val inBand: BooleanArray)
        fun grid(stride: Int): Grid {
            val i0 = freqs.indexOfFirst { it >= band.start }
            val i1 = freqs.indexOfLast { it <= band.endInclusive }
            val ppo = (n - 1) / log2(freqs.last() / freqs.first()) / stride
            val half = (ppo / 24.0).roundToInt().coerceAtLeast(1)
            val idx = ((i0 - half * stride).coerceAtLeast(0)..(i1 + half * stride).coerceAtMost(n - 1) step stride).toList().toIntArray()
            return Grid(idx, half, BooleanArray(idx.size) { freqs[idx[it]] in band })
        }

        fun smooth(p: DoubleArray, half: Int): DoubleArray {
            val m = p.size
            val prefix = DoubleArray(m + 1)
            for (i in 0 until m) prefix[i + 1] = prefix[i] + p[i]
            return DoubleArray(m) { i ->
                val a = (i - half).coerceAtLeast(0)
                val b = (i + half).coerceAtMost(m - 1)
                (prefix[b + 1] - prefix[a]) / (b - a + 1)
            }
        }

        fun penalty(sum: DoubleArray, target: DoubleArray, g: Grid, exact: Boolean): Double {
            var acc = 0.0
            var k = 0
            for (i in sum.indices) if (g.inBand[i]) {
                val ratio = sum[i] / max(target[i], 1e-30)
                val d = 10 * (if (exact) log10(max(ratio, 1e-30)) else fastLog10(max(ratio, 1e-30)))
                acc += if (d > 0) d else -2 * d
                k++
            }
            return if (k > 0) acc / k else 0.0
        }

        fun delayCandidates(ch: Channel, stepMs: Double): List<BandSetting> {
            val cur = timing.get(moving, ch)
            val others = Band.BRANCHES.flatMap { b -> Channel.entries.map { c -> b to c } }.filter { it != moving to ch }.maxOf { (b, c) -> timing.get(b, c).delayMs }
            val minDelay = -(BandAlign.DELAY_MAX_MS - others).coerceAtLeast(0.0)
            val lo = Math.ceil(minDelay / stepMs - 1e-9).toInt()
            val hi = Math.floor(BandAlign.DELAY_MAX_MS / stepMs + 1e-9).toInt()
            val ds = ((lo..hi).map { (it * stepMs).roundTo(BandAlign.DELAY_STEP_MS) } + cur.delayMs.roundTo(BandAlign.DELAY_STEP_MS)).distinct()
            return ds.flatMap { d -> listOf(BandSetting(d, cur.inverted), BandSetting(d, !cur.inverted)) }
                .sortedBy { abs(it.delayMs - cur.delayMs) + if (it.inverted != cur.inverted) 1e-6 else 0.0 }
        }

        class Ctx(val g: Grid, val delays: Map<Channel, List<BandSetting>>, val cs: Map<Channel, Array<DoubleArray>>, val sn: Map<Channel, Array<DoubleArray>>,
                  val targets: Map<Channel, List<DoubleArray>>, val before: Map<Channel, List<Double>>, val exact: Boolean)

        fun sumPower(p: Pos, lo: SlotCand, up: SlotCand, idx: IntArray): Triple<DoubleArray, DoubleArray, DoubleArray> {
            // a = everything that doesn't move, b = the moving band; power = |a|²+|b|² + 2·Re(conj(a)·b·e^{-jθ}).
            val m = idx.size
            val aa = DoubleArray(m); val cr = DoubleArray(m); val ci = DoubleArray(m)
            val hMid = p.bands.getValue(Band.MID)
            val hMov = p.bands.getValue(moving)
            val hOther = p.bands.getValue(other)
            val midRatio = if (xo == Crossover.LOW_MID) up.ratio else lo.ratio
            val movRatio = if (xo == Crossover.LOW_MID) lo.ratio else up.ratio
            for (j in 0 until m) {
                val i = idx[j]
                val ar = hMid.re[i] * midRatio.re[i] - hMid.im[i] * midRatio.im[i] + hOther.re[i]
                val ai = hMid.re[i] * midRatio.im[i] + hMid.im[i] * midRatio.re[i] + hOther.im[i]
                val br = hMov.re[i] * movRatio.re[i] - hMov.im[i] * movRatio.im[i]
                val bi = hMov.re[i] * movRatio.im[i] + hMov.im[i] * movRatio.re[i]
                aa[j] = ar * ar + ai * ai + br * br + bi * bi
                cr[j] = ar * br + ai * bi
                ci[j] = ar * bi - ai * br
            }
            return Triple(aa, cr, ci)
        }

        fun context(stride: Int, stepMs: Double, exact: Boolean): Ctx {
            val g = grid(stride)
            val delays = channels.associate { (ch, _) -> ch to delayCandidates(ch, stepMs) }
            val cs = HashMap<Channel, Array<DoubleArray>>()
            val sn = HashMap<Channel, Array<DoubleArray>>()
            for ((ch, ds) in delays) {
                val cur = timing.get(moving, ch).delayMs
                cs[ch] = Array(ds.size) { k -> DoubleArray(g.idx.size) { j -> cos(2 * PI * freqs[g.idx[j]] * (ds[k].delayMs - cur) / 1000.0) } }
                sn[ch] = Array(ds.size) { k -> DoubleArray(g.idx.size) { j -> sin(2 * PI * freqs[g.idx[j]] * (ds[k].delayMs - cur) / 1000.0) } }
            }
            val targets = pos.mapValues { (_, ps) -> ps.map { p -> smooth(DoubleArray(g.idx.size) { p.target[g.idx[it]] }, g.half) } }
            val before = pos.mapValues { (ch, ps) ->
                ps.mapIndexed { pi, p ->
                    val (aa, cr, _) = sumPower(p, current.first, current.second, g.idx)
                    penalty(smooth(DoubleArray(aa.size) { aa[it] + 2 * cr[it] }, g.half), targets.getValue(ch)[pi], g, exact)
                }
            }
            return Ctx(g, delays, cs, sn, targets, before, exact)
        }

        // Seat-weighted improvement for one channel, NaN if a seat gets worse.
        fun channelScore(ch: Channel, imps: DoubleArray): Double {
            val ps = pos.getValue(ch)
            var total = 0.0
            var w = 0.0
            for (seat in Seat.entries) {
                val idx = ps.indices.filter { !ps[it].validationOnly && ps[it].key.seat == seat }
                if (idx.isEmpty()) continue
                val s = idx.sumOf { imps[it] } / idx.size
                if (s < -1e-9) return Double.NaN
                total += o.seatWeight(seat) * s
                w += o.seatWeight(seat)
            }
            return total / w
        }

        fun evaluate(c: Pair<SlotCand, SlotCand>, ctx: Ctx): Eval? {
            var total = 0.0
            val chosen = HashMap<Channel, BandSetting>()
            for ((ch, _) in channels) {
                val ps = pos.getValue(ch)
                val parts = ps.map { sumPower(it, c.first, c.second, ctx.g.idx) }
                val ds = ctx.delays.getValue(ch)
                val cs = ctx.cs.getValue(ch)
                val sn = ctx.sn.getValue(ch)
                val cur = timing.get(moving, ch)
                val m = ctx.g.idx.size
                val buf = DoubleArray(m)
                val imps = DoubleArray(ps.size)
                var best = Double.NaN
                var bestSetting: BandSetting? = null
                for (k in ds.indices) {
                    val sign = if (ds[k].inverted != cur.inverted) -2.0 else 2.0
                    for (pi in ps.indices) {
                        if (ps[pi].validationOnly) continue
                        val (aa, cr, ci) = parts[pi]
                        val ck = cs[k]
                        val sk = sn[k]
                        for (j in 0 until m) buf[j] = aa[j] + sign * (cr[j] * ck[j] + ci[j] * sk[j])
                        imps[pi] = ctx.before.getValue(ch)[pi] - penalty(smooth(buf, ctx.g.half), ctx.targets.getValue(ch)[pi], ctx.g, ctx.exact)
                    }
                    val sc = channelScore(ch, imps)
                    if (sc.isFinite() && (bestSetting == null || sc > best + 1e-9)) { best = sc; bestSetting = ds[k] }
                }
                if (bestSetting == null) return null
                total += best
                chosen[ch] = bestSetting
            }
            return Eval(Cand(c.first.filter, c.second.filter, c.first.k, c.second.k), total / channels.size, chosen)
        }

        fun evaluateAll(cands: List<Pair<SlotCand, SlotCand>>, ctx: Ctx): List<Eval> {
            val out = arrayOfNulls<Eval>(cands.size)
            IntStream.range(0, cands.size).parallel().forEach { out[it] = evaluate(cands[it], ctx) }
            return out.filterNotNull()
        }

        // Stage 1: every other lattice step (1/6 octave), a coarse delay step.
        val xoHz = sqrt(curLo.hz * curUp.hz)
        val coarseMs = (Math.floor(1000.0 / (12 * xoHz) / BandAlign.DELAY_STEP_MS) * BandAlign.DELAY_STEP_MS).coerceIn(BandAlign.DELAY_STEP_MS, 0.2)
        val stage1 = pairs.filter { (a, b) -> (a.k % 2 == 0 && b.k % 2 == 0) || (a === current.first && b === current.second) }
        val coarse = evaluateAll(stage1, context(stride = 2, stepMs = coarseMs, exact = false))
        // Stage 2: the best few, their 1/12-octave neighbours with the same slopes, and the current
        // filters; full resolution and 0.01 ms delays.
        val seeds = coarse.sortedByDescending { it.score }.distinctBy { it.cand.lower.type to it.cand.upper.type }.take(STAGE2_KEEP) +
            coarse.sortedByDescending { it.score }.take(STAGE2_KEEP)
        val stage2 = pairs.filter { (a, b) ->
            (a === current.first && b === current.second) || seeds.any { s ->
                s.cand.lower.type == a.filter.type && s.cand.upper.type == b.filter.type && abs(s.cand.lowerIdx - a.k) <= 1 && abs(s.cand.upperIdx - b.k) <= 1
            }
        }
        val fine = evaluateAll(stage2, context(stride = 1, stepMs = BandAlign.DELAY_STEP_MS, exact = true))
        // No change at all: the current filters, delays and polarity.
        val noChange = Eval(Cand(curLo, curUp, 0, 0), 0.0, channels.associate { (ch, _) -> ch to timing.get(moving, ch) })

        fun change(e: Eval): Double =
            abs(log2(e.cand.lower.hz / curLo.hz)) + abs(log2(e.cand.upper.hz / curUp.hz)) +
                (if (e.cand.lower.type != curLo.type) 0.25 else 0.0) + (if (e.cand.upper.type != curUp.type) 0.25 else 0.0) +
                e.delays.entries.sumOf { (ch, s) -> val c = timing.get(moving, ch); abs(s.delayMs - c.delayMs) * 0.1 + if (s.inverted != c.inverted) 0.05 else 0.0 }
        val bestScore = fine.maxOf { it.score }
        val winner = fine.filter { it.score >= bestScore - TIE_DB }.minBy { change(it) }
        val accepted = if (winner.score >= MIN_IMPROVEMENT_DB) winner else noChange
        val filtersChanged = accepted.cand.lower != curLo || accepted.cand.upper != curUp

        // Report curves on the full grid for the accepted option.
        val full = Grid(IntArray(n) { it }, ((n - 1) / log2(freqs.last() / freqs.first()) / 24.0).roundToInt().coerceAtLeast(1), BooleanArray(n) { freqs[it] in band })
        val accPair = pairs.first { it.first.filter == accepted.cand.lower && it.second.filter == accepted.cand.upper }
        val chResults = channels.map { (ch, _) ->
            val ps = pos.getValue(ch)
            val cur = timing.get(moving, ch)
            val setting = accepted.delays.getValue(ch)
            fun curve(p: Pos, c: Pair<SlotCand, SlotCand>, s: BandSetting): DoubleArray {
                val (aa, cr, ci) = sumPower(p, c.first, c.second, full.idx)
                val sign = if (s.inverted != cur.inverted) -2.0 else 2.0
                val dt = (s.delayMs - cur.delayMs) / 1000.0
                return smooth(DoubleArray(n) { j -> val th = 2 * PI * freqs[j] * dt; aa[j] + sign * (cr[j] * cos(th) + ci[j] * sin(th)) }, full.half)
            }
            val before = ps.map { curve(it, current, cur) }
            val after = ps.map { curve(it, accPair, setting) }
            val targets = ps.map { smooth(it.target, full.half) }
            val imps = DoubleArray(ps.size) { penalty(before[it], targets[it], full, true) - penalty(after[it], targets[it], full, true) }
            val design = ps.indices.filter { !ps[it].validationOnly }
            val seats = Seat.entries.filter { s -> design.any { ps[it].key.seat == s } }
            fun seatDb(seat: Seat, v: List<DoubleArray>): DoubleArray {
                val idx = design.filter { ps[it].key.seat == seat }
                return DoubleArray(n) { i -> 10 * log10(max(idx.sumOf { v[it][i] } / idx.size, 1e-30)) }
            }
            val ds = delayCandidates(ch, BandAlign.DELAY_STEP_MS)
            FinderChannel(
                channel = ch, moving = moving, current = cur, proposed = setting,
                scoreDb = channelScore(ch, imps).let { if (it.isFinite()) it.round(2) else 0.0 },
                seats = seats.map { AlignSeatCurve(it, design.count { i -> ps[i].key.seat == it }, seatDb(it, before), seatDb(it, after)) },
                targetDb = seats.map { seatDb(it, targets) },
                positions = ps.indices.map { AlignPositionScore(ps[it].key, ps[it].validationOnly, imps[it].round(2)) },
                delayAtLimit = setting != cur && (setting.delayMs <= ds.minOf { it.delayMs } + 1e-6 || setting.delayMs >= BandAlign.DELAY_MAX_MS - 1e-6),
            )
        }
        val alternatives = fine.filter { it !== accepted }.sortedByDescending { it.score }
            .distinctBy { it.cand.lower.type to it.cand.upper.type }
            .take(5).map { FinderOption(it.cand.lower, it.cand.upper, it.score.round(2)) }
        val edge = buildList {
            if (filtersChanged) {
                if (accepted.cand.lower != curLo && (accepted.cand.lowerIdx == loFreqs.minOf { it.first } || accepted.cand.lowerIdx == loFreqs.maxOf { it.first })) add(loSlot)
                if (accepted.cand.upper != curUp && (accepted.cand.upperIdx == upFreqs.minOf { it.first } || accepted.cand.upperIdx == upFreqs.maxOf { it.first })) add(upSlot)
            }
        }
        return FinderResult(
            crossover = xo, currentLower = curLo, currentUpper = curUp,
            lower = accepted.cand.lower, upper = accepted.cand.upper, changed = accepted !== noChange,
            scoreDb = if (accepted === noChange) 0.0 else accepted.score.round(2),
            bandHz = band, channels = chResults, alternatives = alternatives, atSearchEdge = edge,
            evaluated = stage1.size + stage2.size, unreliable = unreliable,
        )
    }

    private fun Double.roundTo(step: Double) = (this / step).roundToInt() * step

    /** log10 to about 1e-6, several times faster than the library call. */
    internal fun fastLog10(x: Double): Double {
        val bits = java.lang.Double.doubleToRawLongBits(x)
        val e = ((bits ushr 52) and 0x7ff).toInt() - 1023
        val m = java.lang.Double.longBitsToDouble((bits and 0x000fffffffffffffL) or 0x3ff0000000000000L) // 1..2
        // ln(m) via atanh series: ln(m) = 2·atanh((m-1)/(m+1)).
        val t = (m - 1) / (m + 1)
        val t2 = t * t
        val ln = 2 * t * (1 + t2 * (1.0 / 3 + t2 * (1.0 / 5 + t2 * (1.0 / 7 + t2 * (1.0 / 9 + t2 / 11)))))
        return (e * 0.6931471805599453 + ln) * 0.4342944819032518
    }
}
