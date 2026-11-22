package jdsp.irbuilder

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin

/** One J_DSP band output's delay (Gains & Delay page) and polarity. */
@Serializable
data class BandSetting(val delayMs: Double = 0.0, val inverted: Boolean = false)

/** The six band delays and polarities J_DSP had when the sweeps were taken. */
@Serializable
data class JdspTiming(
    val lowL: BandSetting = BandSetting(),
    val lowR: BandSetting = BandSetting(),
    val midL: BandSetting = BandSetting(),
    val midR: BandSetting = BandSetting(),
    val highL: BandSetting = BandSetting(),
    val highR: BandSetting = BandSetting(),
) {
    fun get(band: Band, ch: Channel): BandSetting = when (band) {
        Band.LOW -> if (ch == Channel.L) lowL else lowR
        Band.MID -> if (ch == Channel.L) midL else midR
        Band.HIGH -> if (ch == Channel.L) highL else highR
        Band.COMBINED -> error("no setting for Combined")
    }

    fun with(band: Band, ch: Channel, s: BandSetting): JdspTiming = when (band) {
        Band.LOW -> if (ch == Channel.L) copy(lowL = s) else copy(lowR = s)
        Band.MID -> if (ch == Channel.L) copy(midL = s) else copy(midR = s)
        Band.HIGH -> if (ch == Channel.L) copy(highL = s) else copy(highR = s)
        Band.COMBINED -> error("no setting for Combined")
    }

    fun validate() {
        for (b in Band.BRANCHES) for (c in Channel.entries) {
            val d = get(b, c).delayMs
            require(d.isFinite() && d in 0.0..BandAlign.DELAY_MAX_MS + 1e-9) {
                "${b.label} ${c.name} delay must be 0 to ${BandAlign.DELAY_MAX_MS} ms (J_DSP's range)"
            }
        }
    }
}

/** Band Align settings saved with the project. */
@Serializable
data class AlignOptions(
    val timing: JdspTiming = JdspTiming(),
    val lowMid: Boolean = true,
    val midHigh: Boolean = true,
    /** Design the phase IRs on the measurements as they will be with the proposals applied. */
    val useInDesign: Boolean = true,
)

/** Which band is moved against Mid, and at which crossover it is scored. */
enum class AlignPair(val label: String, val moving: Band, val crossover: Crossover) {
    LOW_MID("Low/Mid", Band.LOW, Crossover.LOW_MID),
    MID_HIGH("Mid/High", Band.HIGH, Crossover.MID_HIGH),
}

/** A seat's power-averaged band sum over its positions, smoothed, dB on the design grid. */
class AlignSeatCurve(val seat: Seat, val positions: Int, val beforeDb: DoubleArray, val afterDb: DoubleArray)

/** Mean change in the scored band at one position, dB (cuts counted double). */
data class AlignPositionScore(val key: GroupKey, val validationOnly: Boolean, val scoreDb: Double)

class AlignResult(
    val channel: Channel,
    val pair: AlignPair,
    val current: BandSetting,
    /** Null when nothing in range beats the current setting by [BandAlign.MIN_IMPROVEMENT_DB]. */
    val proposal: BandSetting?,
    /** Seat-weighted mean change over the scored band, dB (cuts counted double). */
    val scoreDb: Double,
    val bandHz: ClosedFloatingPointRange<Double>,
    val seats: List<AlignSeatCurve>,
    val positions: List<AlignPositionScore>,
    /** The best delay is at an end of the searchable range: it may want more than the range allows. */
    val atRangeLimit: Boolean,
)

/** One J_DSP setting to change, for the list the user types in. */
data class SettingChange(val band: Band, val channel: Channel, val from: BandSetting, val to: BandSetting) {
    val label: String get() = "${band.label} ${channel.name}"
}

class BandAlignReport(
    val freqs: DoubleArray,
    val results: List<AlignResult>,
    /** Every delay is made this much later so a negative proposal can play; 0 when none is negative. */
    val sharedOffsetMs: Double,
    /** The new J_DSP timing, or null when the proposals don't fit J_DSP's delay range together. */
    val newTiming: JdspTiming?,
    val changes: List<SettingChange>,
    val error: String?,
    val before: JdspTiming,
) {
    val hasChanges get() = newTiming != null && changes.isNotEmpty()

    /**
     * The measurements as they will be once [changes] are made: each moved band shifted in time
     * and sign-flipped exactly, Combined sweeps dropped (they were taken with the old timing).
     * The shared offset delays every band equally, so it changes nothing relative and is left out.
     */
    fun alignedSet(set: MeasurementSet): MeasurementSet {
        if (!hasChanges) return set
        val t = newTiming!!
        val groups = set.groups.map { g ->
            val responses = LinkedHashMap<Band, LoadedResponse>()
            for ((band, r) in g.responses) {
                if (band == Band.COMBINED) continue
                val old = before.get(band, g.key.channel)
                val new = t.get(band, g.key.channel)
                val dt = (new.delayMs - sharedOffsetMs - old.delayMs) / 1000.0
                val sign = if (new.inverted != old.inverted) -1.0 else 1.0
                responses[band] = if (dt == 0.0 && sign == 1.0) r else LoadedResponse(
                    shift(r.response, set.freqs, dt, sign), r.kind, r.impulsePeakIndex, r.coverageHz, r.smoothing, r.restoredDelayMs,
                )
            }
            LoadedGroup(g.key, g.validationOnly, responses, g.measurements.filterKeys { it != Band.COMBINED })
        }
        return MeasurementSet(set.options, set.freqs, groups, set.loadErrors, set.measurements)
    }
}

/**
 * Band Align, ported from J_DSP (`dsp/bandalign/BandAlignSolver.kt`) and extended: per side, the
 * delay and polarity of Low against Mid (scored around the Low/Mid crossover) and of High against
 * Mid (around Mid/High) that make the bands add up best at every seat together.
 *
 * Measurements are taken through the tune as it is, so a candidate is just the measured band
 * moved by the difference from the current setting: an exact time shift and sign, no crossover
 * model. Each candidate's three-band sum is power-smoothed over ±1/24 octave and scored over the
 * crossover region with cuts counted double, per position then per seat. A candidate that leaves
 * any seat worse off is rejected, so one seat is never traded for another. Delays run 0–2.8 ms in
 * 0.01 ms steps (J_DSP's Gains & Delay range), and below 0 as far as the other delays leave room:
 * a negative answer becomes 0 with every other delay made later by the same amount.
 */
object BandAlign {
    const val DELAY_MAX_MS = 2.8
    const val DELAY_STEP_MS = 0.01
    const val MIN_IMPROVEMENT_DB = 0.5

    /** The scored region: 2.5× below to 2.5× above the crossover, within the design grid. */
    fun scoreBand(xoHz: Double, freqs: DoubleArray) = maxOf(xoHz / 2.5, freqs.first())..minOf(xoHz * 2.5, freqs.last())

    fun solve(set: MeasurementSet, align: AlignOptions): BandAlignReport {
        val o = set.options
        val f = set.freqs
        align.timing.validate()
        val pairs = AlignPair.entries.filter { if (it == AlignPair.LOW_MID) align.lowMid else align.midHigh }
        val results = ArrayList<AlignResult>()
        // Low first, then High with Low's proposal applied, so each sees the other's final timing.
        var working = align.timing
        for (pair in pairs) {
            for (ch in Channel.entries) {
                val groups = set.groups.filter { it.key.channel == ch && it.isComplete }
                if (groups.none { !it.validationOnly }) continue
                val r = solvePair(set, groups, pair, ch, align.timing, working, o)
                results += r
                r.proposal?.let { working = working.with(pair.moving, ch, it) }
            }
        }
        val proposals = results.mapNotNull { r -> r.proposal?.let { Triple(r.pair.moving, r.channel, it) } }
        val offset = (-(proposals.minOfOrNull { it.third.delayMs } ?: 0.0)).coerceAtLeast(0.0)
        var newTiming: JdspTiming? = align.timing
        var error: String? = null
        if (proposals.isNotEmpty()) {
            var t = align.timing
            for ((band, ch, p) in proposals) t = t.with(band, ch, p)
            if (offset > 0) for (b in Band.BRANCHES) for (c in Channel.entries) {
                val s = t.get(b, c)
                t = t.with(b, c, s.copy(delayMs = s.delayMs + offset))
            }
            for (b in Band.BRANCHES) for (c in Channel.entries) {
                val s = t.get(b, c)
                t = t.with(b, c, s.copy(delayMs = s.delayMs.roundTo(DELAY_STEP_MS)))
            }
            if (Band.BRANCHES.any { b -> Channel.entries.any { c -> t.get(b, c).delayMs > DELAY_MAX_MS + 1e-6 } }) {
                newTiming = null
                error = "The proposals don't fit J_DSP's 0–$DELAY_MAX_MS ms delay range together. Align one pair at a time, or lower the largest delays first."
            } else newTiming = t
        }
        val changes = if (newTiming == null) emptyList() else Band.BRANCHES.flatMap { b ->
            Channel.entries.mapNotNull { c ->
                val from = align.timing.get(b, c)
                val to = newTiming.get(b, c)
                if (abs(from.delayMs - to.delayMs) > 1e-6 || from.inverted != to.inverted) SettingChange(b, c, from, to) else null
            }
        }
        return BandAlignReport(f, results, offset.roundTo(DELAY_STEP_MS), newTiming, changes, error, align.timing)
    }

    private fun solvePair(
        set: MeasurementSet,
        groups: List<LoadedGroup>,
        pair: AlignPair,
        ch: Channel,
        original: JdspTiming,
        working: JdspTiming,
        o: DesignOptions,
    ): AlignResult {
        val f = set.freqs
        val n = f.size
        val band = scoreBand(pair.crossover.hz(o), f)
        val inBand = BooleanArray(n) { f[it] in band }
        val current = original.get(pair.moving, ch).let { it.copy(delayMs = it.delayMs.roundTo(DELAY_STEP_MS)) }
        val moving = Band.BRANCHES.indexOf(pair.moving)
        // Everything but the moving band, with earlier proposals (Low, when solving High) applied.
        val rest = groups.map { g ->
            val sum = CVec(n)
            Band.BRANCHES.forEachIndexed { bi, b ->
                if (bi == moving) return@forEachIndexed
                val old = original.get(b, ch)
                val now = working.get(b, ch)
                val h = shift(g.branch(b), f, (now.delayMs - old.delayMs) / 1000.0, if (now.inverted != old.inverted) -1.0 else 1.0)
                for (i in 0 until n) { sum.re[i] += h.re[i]; sum.im[i] += h.im[i] }
            }
            sum
        }
        val movingBranch = groups.map { it.branch(pair.moving) }
        val ppo = (n - 1) / log2(f.last() / f.first())
        val half = (ppo / 24.0).toInt().coerceAtLeast(1)

        fun sumDb(gi: Int, s: BandSetting): DoubleArray {
            val dt = (s.delayMs - current.delayMs) / 1000.0
            val sign = if (s.inverted != current.inverted) -1.0 else 1.0
            val h = movingBranch[gi]
            val r = rest[gi]
            val power = DoubleArray(n) { i ->
                val a = -2 * PI * f[i] * dt
                val c = cos(a) * sign
                val sn = sin(a) * sign
                val re = h.re[i] * c - h.im[i] * sn + r.re[i]
                val im = h.re[i] * sn + h.im[i] * c + r.im[i]
                re * re + im * im
            }
            return DoubleArray(n) { i ->
                var acc = 0.0
                var k = 0
                for (j in (i - half).coerceAtLeast(0)..(i + half).coerceAtMost(n - 1)) { acc += power[j]; k++ }
                10 * log10((acc / k).coerceAtLeast(1e-30))
            }
        }

        val before = groups.indices.map { sumDb(it, current) }
        fun positionScore(gi: Int, after: DoubleArray): Double {
            var sum = 0.0
            var k = 0
            for (i in 0 until n) if (inBand[i]) {
                val d = after[i] - before[gi][i]
                sum += if (d > 0) d else 2 * d
                k++
            }
            return if (k > 0) sum / k else 0.0
        }
        val design = groups.indices.filter { !groups[it].validationOnly }
        val seats = Seat.entries.filter { s -> design.any { groups[it].key.seat == s } }
        fun score(after: List<DoubleArray>): Double {
            var total = 0.0
            var weights = 0.0
            for (seat in seats) {
                val idx = design.filter { groups[it].key.seat == seat }
                val seatScore = idx.map { positionScore(it, after[it]) }.average()
                if (seatScore < 0) return Double.NaN
                total += o.seatWeight(seat) * seatScore
                weights += o.seatWeight(seat)
            }
            return total / weights
        }

        val others = Band.BRANCHES.flatMap { b -> Channel.entries.map { c -> b to c } }
            .filter { it != pair.moving to ch }
            .maxOf { (b, c) -> working.get(b, c).delayMs }
        val minDelay = -(DELAY_MAX_MS - others).coerceAtLeast(0.0).roundTo(DELAY_STEP_MS)
        val steps = (DELAY_MAX_MS / DELAY_STEP_MS).roundToInt()
        val firstStep = (minDelay / DELAY_STEP_MS).roundToInt()
        val candidates = buildList {
            for (k in firstStep..steps) for (inv in listOf(current.inverted, !current.inverted)) add(BandSetting((k * DELAY_STEP_MS).roundTo(DELAY_STEP_MS), inv))
        }.sortedBy { abs(it.delayMs - current.delayMs) + if (it.inverted != current.inverted) 1e-6 else 0.0 }

        var best: Pair<BandSetting, Double>? = null
        for (c in candidates) {
            val after = groups.indices.map { sumDb(it, c) }
            val sc = score(after)
            if (sc.isFinite() && (best == null || sc > best.second + 1e-9)) best = c to sc
        }
        val winner = best?.takeIf { it.second >= MIN_IMPROVEMENT_DB && it.first != current }
        val chosen = winner?.first ?: current
        val after = groups.indices.map { sumDb(it, chosen) }
        fun seatCurve(seat: Seat, values: List<DoubleArray>): DoubleArray {
            val idx = design.filter { groups[it].key.seat == seat }
            return DoubleArray(n) { i -> 10 * log10(idx.map { Math.pow(10.0, values[it][i] / 10) }.average()) }
        }
        return AlignResult(
            channel = ch, pair = pair, current = current, proposal = winner?.first,
            scoreDb = winner?.second ?: 0.0, bandHz = band,
            seats = seats.map { s -> AlignSeatCurve(s, design.count { groups[it].key.seat == s }, seatCurve(s, before), seatCurve(s, after)) },
            positions = groups.indices.map { AlignPositionScore(groups[it].key, groups[it].validationOnly, positionScore(it, after[it]).round(2)) },
            atRangeLimit = winner != null && (winner.first.delayMs <= minDelay + 1e-6 || winner.first.delayMs >= DELAY_MAX_MS - 1e-6),
        )
    }

    private fun Double.roundTo(step: Double) = (this / step).roundToInt() * step
}

/** [h] delayed by [dt] seconds and multiplied by [sign]. */
internal fun shift(h: CVec, freqs: DoubleArray, dt: Double, sign: Double): CVec {
    val out = CVec(h.size)
    for (i in 0 until h.size) {
        val a = -2 * PI * freqs[i] * dt
        val c = cos(a) * sign
        val s = sin(a) * sign
        out.re[i] = h.re[i] * c - h.im[i] * s
        out.im[i] = h.re[i] * s + h.im[i] * c
    }
    return out
}
