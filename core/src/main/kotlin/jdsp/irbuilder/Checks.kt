package jdsp.irbuilder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

enum class Severity { ERROR, WARNING, INFO }

data class CheckItem(val severity: Severity, val title: String, val detail: String)

/** Relative phase of two bands across the positions of one seat/channel, at one crossover. */
data class CrossoverPhase(
    val seat: Seat,
    val channel: Channel,
    val crossover: Crossover,
    /** Circular mean of arg(upper / lower) at the crossover, degrees. */
    val meanDeg: Double,
    /** Circular standard deviation across positions, degrees. */
    val spreadDeg: Double,
    val positions: Int,
)

enum class Crossover(val label: String, val lower: Band, val upper: Band) {
    LOW_MID("Low/Mid", Band.LOW, Band.MID),
    MID_HIGH("Mid/High", Band.MID, Band.HIGH);

    fun hz(o: DesignOptions) = if (this == LOW_MID) o.lowMidHz else o.midHighHz
}

class CheckResult(val items: List<CheckItem>, val crossoverPhases: List<CrossoverPhase>) {
    val errors get() = items.count { it.severity == Severity.ERROR }
    val warnings get() = items.count { it.severity == Severity.WARNING }
    val canDesign get() = errors == 0
}

object Checks {
    /** Which bands each seat/position/channel has, straight from the list (no file reading). */
    fun completeness(measurements: List<Measurement>): Map<GroupKey, Set<Band>> =
        measurements.groupBy { GroupKey(it.seat, it.position.trim(), it.channel) }
            .mapValues { (_, ms) -> ms.map { it.band }.toSet() }
            .toSortedMap()

    fun run(set: MeasurementSet): CheckResult {
        val o = set.options
        val items = ArrayList<CheckItem>()
        fun err(t: String, d: String) { items += CheckItem(Severity.ERROR, t, d) }
        fun warn(t: String, d: String) { items += CheckItem(Severity.WARNING, t, d) }
        fun info(t: String, d: String) { items += CheckItem(Severity.INFO, t, d) }

        try { o.validate() } catch (e: IllegalArgumentException) { err("Design settings", e.message ?: "Invalid settings") }
        if (set.measurements.isEmpty()) {
            err("No measurements", "Import software-band-solo sweeps for both seats and both channels.")
            return CheckResult(items, emptyList())
        }
        for (e in set.loadErrors) err("Cannot use ${MeasurementSet.fileName(e.measurement)}", e.message)

        // Timing references: each channel's sweeps must share one unchanged reference, or the
        // band-to-band phase the designer aligns is meaningless.
        for (ch in Channel.entries) {
            val refs = set.measurements.filter { it.channel == ch }.map { it.timingReference.trim() }.toSet()
            if (refs.size > 1) err("${ch.label}: more than one timing reference", "Found ${refs.joinToString()}. Every ${ch.label.lowercase()} sweep must use the same unchanged timing reference.")
            if (refs.any { it.isEmpty() }) err("${ch.label}: missing timing reference", "Enter the timing-reference ID used for each sweep.")
        }

        // Group completeness and consistency.
        for ((key, ms) in set.measurements.groupBy { GroupKey(it.seat, it.position.trim(), it.channel) }) {
            val bands = ms.map { it.band }.toSet()
            val missing = Band.BRANCHES.filter { it !in bands }
            if (missing.isNotEmpty()) err("${key.label}: incomplete", "Missing ${missing.joinToString { it.label }} software-band-solo sweep(s).")
            if (ms.map { it.validationOnly }.toSet().size > 1) err("${key.label}: mixed validation flag", "Mark all bands at a position validation-only, or none.")
            val cal = ms.filter { it.band != Band.COMBINED }.map { it.calibrationDb }.toSet()
            if (cal.size > 1) warn("${key.label}: calibration offsets differ", "Bands at one position normally share a calibration offset; different offsets change their relative level and the computed band sum.")
        }
        for (seat in Seat.entries) for (ch in Channel.entries) {
            if (set.groups.none { it.key.seat == seat && it.key.channel == ch && it.isComplete && !it.validationOnly }) {
                err("No design data: ${seat.label} ${ch.label}", "Need at least one complete Low/Mid/High triplet that is not validation-only.")
            }
        }

        val freqs = set.freqs
        val combinedOk = ArrayList<Double>()
        for (g in set.groups) {
            val kinds = g.responses.values.map { it.kind }.toSet()
            if (kinds.size > 1) warn("${g.key.label}: mixed file types", "REW text and impulse WAVs at the same position must share one calibration and time origin.")
            for ((band, r) in g.responses) {
                val p = r.impulsePeakIndex
                if (p != null && p < 2) warn("${g.key.label} ${band.label}: impulse peak at sample $p", "The WAV may have been trimmed to its own peak, losing the shared time origin. Export impulses with the timing-reference offset preserved.")
            }
            if (!g.isComplete) continue
            // Bands should sit in frequency order; otherwise the tags are probably swapped.
            val centroids = Band.BRANCHES.map { energyCentroidHz(g.branch(it), freqs) }
            if (!(centroids[0] < centroids[1] && centroids[1] < centroids[2])) {
                warn("${g.key.label}: band order looks wrong", "Energy centres: Low %.0f Hz, Mid %.0f Hz, High %.0f Hz. Check the band tags.".format(centroids[0], centroids[1], centroids[2]))
            }
            for (xo in Crossover.entries) {
                val hz = xo.hz(o)
                val a = levelNear(g.branch(xo.lower), freqs, hz)
                val b = levelNear(g.branch(xo.upper), freqs, hz)
                if (abs(a - b) > 15) warn("${g.key.label}: ${xo.label} levels far apart", "${xo.lower.label} %.1f dB vs ${xo.upper.label} %.1f dB at %.0f Hz. Check the crossover frequency you entered, band tags, gains or mutes.".format(a, b, hz))
            }
            g.responses[Band.COMBINED]?.let { combined ->
                val sum = g.branchSum()
                val c = combined.response
                var num = 0.0
                var den = 0.0
                for (i in freqs.indices) {
                    num += (c.re[i] - sum.re[i]).let { it * it } + (c.im[i] - sum.im[i]).let { it * it }
                    den += c.re[i] * c.re[i] + c.im[i] * c.im[i]
                }
                val rel = sqrt(num / maxOf(den, 1e-24))
                if (rel > 0.25) warn("${g.key.label}: combined sweep ≠ band sum", "Relative complex error %.2f. Timing, gain, routing or dynamics changed between sweeps; predictions will be unreliable.".format(rel))
                else combinedOk += rel
            }
        }

        if (combinedOk.isNotEmpty()) info("Combined sweeps match the band sum", "${combinedOk.size} position/channel(s); worst relative complex error %.2f.".format(combinedOk.max()))

        // Positions far from their seat's typical level suggest a gain change or a moved mic.
        for (seat in Seat.entries) for (ch in Channel.entries) {
            val gs = set.groups.filter { it.key.seat == seat && it.key.channel == ch && it.isComplete }
            if (gs.size < 3) continue
            val curves = gs.map { g -> g.branchSum().let { s -> DoubleArray(freqs.size) { db(s.abs(it)) } } }
            val median = DoubleArray(freqs.size) { i -> curves.map { it[i] }.sorted()[curves.size / 2] }
            for ((g, c) in gs.zip(curves)) {
                val offset = c.indices.map { c[it] - median[it] }.average()
                if (abs(offset) > 6) warn("${g.key.label}: level outlier", "%+.1f dB from the seat's median position. Check source gain and mic placement.".format(offset))
            }
        }

        // Phase consistency: one shared filter can only help if positions agree on the band offset.
        val phases = ArrayList<CrossoverPhase>()
        for (ch in Channel.entries) for (xo in Crossover.entries) {
            val bySeat = Seat.entries.mapNotNull { seat ->
                val gs = set.groups.filter { it.key.seat == seat && it.key.channel == ch && it.isComplete && !it.validationOnly }
                if (gs.isEmpty()) return@mapNotNull null
                val angles = gs.map { relativePhase(it.branch(xo.lower), it.branch(xo.upper), freqs, xo.hz(o)) }
                val (mean, spread) = circularStats(angles)
                CrossoverPhase(seat, ch, xo, Math.toDegrees(mean), Math.toDegrees(spread), gs.size)
            }
            phases += bySeat
            for (p in bySeat) {
                if (p.positions > 1 && p.spreadDeg > 60) warn("${p.seat.label} ${ch.label} ${xo.label}: inconsistent phase between positions", "Band phase offset varies by %.0f° across positions; a fixed filter cannot suit them all. Check the mic positions and timing reference.".format(p.spreadDeg))
                if (abs(p.meanDeg) > 135) info("${p.seat.label} ${ch.label} ${xo.label}: bands near opposite polarity", "Mean offset %.0f°. Consider J_DSP's polarity control before relying on phase correction.".format(p.meanDeg))
            }
            if (bySeat.size == 2) {
                val d = abs(Math.toDegrees(wrap(Math.toRadians(bySeat[0].meanDeg - bySeat[1].meanDeg))))
                if (d > 90) info("${ch.label} ${xo.label}: seats disagree", "Driver and passenger band offsets differ by %.0f°. The shared correction is a compromise set by the seat weights.".format(d))
            }
        }

        val design = set.groups.count { it.isComplete && !it.validationOnly }
        val held = set.groups.count { it.isComplete && it.validationOnly }
        info("Measurement set", "$design design position/channel triplet(s), $held validation-only, ${set.measurements.size} file(s). Analysis range %.0f–%.0f Hz.".format(freqs.first(), freqs.last()))
        return CheckResult(items.sortedBy { it.severity.ordinal }, phases)
    }

    internal fun energyCentroidHz(h: CVec, freqs: DoubleArray): Double {
        var w = 0.0
        var acc = 0.0
        for (i in freqs.indices) {
            val e = h.re[i] * h.re[i] + h.im[i] * h.im[i]
            w += e
            acc += e * log2(freqs[i])
        }
        return Math.pow(2.0, acc / maxOf(w, 1e-30))
    }

    /** Mean level in dB within ±1/6 octave of [hz]. */
    internal fun levelNear(h: CVec, freqs: DoubleArray, hz: Double): Double {
        val idx = freqs.indices.filter { abs(log2(freqs[it] / hz)) <= 1.0 / 6 }
        return idx.map { db(h.abs(it)) }.average()
    }

    /** Circular mean of arg(upper/lower) within ±1/6 octave of [hz], radians. */
    internal fun relativePhase(lower: CVec, upper: CVec, freqs: DoubleArray, hz: Double): Double {
        var x = 0.0
        var y = 0.0
        for (i in freqs.indices) {
            if (abs(log2(freqs[i] / hz)) > 1.0 / 6) continue
            val a = atan2(upper.im[i], upper.re[i]) - atan2(lower.im[i], lower.re[i])
            x += cos(a)
            y += sin(a)
        }
        return atan2(y, x)
    }

    /** Circular mean and standard deviation, radians. */
    internal fun circularStats(angles: List<Double>): Pair<Double, Double> {
        val x = angles.sumOf { cos(it) } / angles.size
        val y = angles.sumOf { sin(it) } / angles.size
        val r = hypot(x, y).coerceIn(1e-12, 1.0)
        return atan2(y, x) to sqrt(-2 * ln(r)).coerceAtMost(PI)
    }
}
