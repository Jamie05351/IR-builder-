package jdsp.irbuilder

import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max

/**
 * Where each driver path (speakers, factory crossover, cabin) plays usefully, read from the
 * band-solo sweeps with J_DSP's own crossover divided out, after Resonalyze's band estimation
 * (github.com/DIMOSUS/Resonalyze, MIT, docs/tech/crossover-auto-setup.md).
 *
 * Per band: the seat-averaged power with J_DSP's filters removed, only where those filters are
 * within [RELIABLE_DB] of their passband and the sweep covered the point (further out, dividing
 * them out would just lift noise); smoothed over 1/3 octave. The reference is the 85th
 * percentile of that curve (robust to room dips and single peaks); the usable band is the most
 * prominent run within [EDGE_DB] of it, bridging dips up to half an octave. An edge where the data
 * simply ran out is marked open: the driver may well reach further.
 */
object BandEstimate {
    const val RELIABLE_DB = -24.0
    const val EDGE_DB = 8.0
    const val MAX_GAP_OCTAVES = 0.5

    data class Range(val lowHz: Double, val highHz: Double, val lowOpen: Boolean, val highOpen: Boolean)

    fun estimate(set: MeasurementSet, current: JdspCrossovers): Map<Band, Range> {
        val f = set.freqs
        val n = f.size
        val fs = set.options.sampleRate.toDouble()
        val out = LinkedHashMap<Band, Range>()
        for (b in Band.BRANCHES) {
            val groups = set.groups.filter { !it.validationOnly && b in it.responses }
            if (groups.isEmpty()) continue
            val x = JdspFilters.response(current.chain(b, fs), f, fs)
            val reliable = Math.pow(10.0, RELIABLE_DB / 20)
            val sum = DoubleArray(n)
            val count = IntArray(n)
            for (g in groups) {
                val cov = g.responses.getValue(b).coverageHz
                val h = g.branch(b)
                for (i in 0 until n) {
                    val xm = x.abs(i)
                    if (xm < reliable || f[i] < cov.start || f[i] > cov.endInclusive) continue
                    sum[i] += (h.abs(i) / xm).let { it * it }
                    count[i]++
                }
            }
            val known = BooleanArray(n) { count[it] > 0 }
            if (known.count { it } < 8) continue
            val raw = DoubleArray(n) { if (known[it]) sum[it] / count[it] else 0.0 }
            val sm = SumLoss.smooth(raw, known, ((n - 1) / log2(f.last() / f.first()) / 6).toInt().coerceAtLeast(1))
            val db = DoubleArray(n) { if (known[it]) 10 * log10(max(sm[it], 1e-30)) else Double.NaN }
            val sorted = db.filter { !it.isNaN() }.sorted()
            val ref = sorted[(sorted.size * 0.85).toInt().coerceAtMost(sorted.size - 1)]
            val above = BooleanArray(n) { known[it] && db[it] >= ref - EDGE_DB }
            // Runs of points above the threshold, bridging gaps up to half an octave.
            val runs = ArrayList<IntRange>()
            var start = -1
            var last = -1
            for (i in 0 until n) if (above[i]) {
                if (start < 0) start = i
                else if (log2(f[i] / f[last]) > MAX_GAP_OCTAVES) { runs += start..last; start = i }
                last = i
            }
            if (start >= 0) runs += start..last
            val best = runs.maxByOrNull { r -> r.filter { above[it] }.sumOf { db[it] - (ref - EDGE_DB) } } ?: continue
            val firstKnown = known.indexOfFirst { it }
            val lastKnown = known.indexOfLast { it }
            // Within 1/12 octave of where the trustworthy data ends, the edge can't be told apart
            // from the data simply stopping.
            out[b] = Range(
                f[best.first], f[best.last],
                lowOpen = log2(f[best.first] / f[firstKnown]) < 1.0 / 12,
                highOpen = log2(f[lastKnown] / f[best.last]) < 1.0 / 12,
            )
        }
        return out
    }

    /**
     * Finder limits from [ranges]: a highpass no lower than its driver path's usable low edge, a
     * lowpass no higher than its high edge (each kept within J_DSP's range, and at least as wide
     * as the current corner); the other side and the slopes stay as they are. An open edge sets
     * nothing, since the data ran out before the driver did.
     */
    fun suggestLimits(f: FinderOptions, ranges: Map<Band, Range>): FinderOptions {
        var out = f
        for (s in XoSlot.entries) {
            val r = ranges[s.band] ?: continue
            val l = f.limits(s)
            val cur = f.current.get(s).hz
            val next = if (s.highpass) {
                if (r.lowOpen) continue
                val lo = s.round(r.lowHz).coerceAtMost(s.round(cur))
                l.copy(minHz = lo, maxHz = max(l.maxHz, lo))
            } else {
                if (r.highOpen) continue
                val hi = s.round(r.highHz).coerceAtLeast(s.round(cur))
                l.copy(maxHz = hi, minHz = minOf(l.minHz, hi))
            }
            out = out.withLimits(s, next)
        }
        return out
    }
}
