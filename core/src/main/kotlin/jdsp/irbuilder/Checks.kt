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

/**
 * What a check run requires, which depends on the tool. The Phase IRs need Low, Mid and High at
 * both seats and both channels (the package covers all six outputs). Band Align needs only the
 * bands of its enabled pairs, and a missing seat or side just means that one isn't aligned.
 */
data class CheckScope(
    /** Each independent job and the bands it needs at a position: one triplet, or one per Band Align pair. */
    val units: List<Pair<String, Set<Band>>>,
    val strict: Boolean,
    val purpose: String,
    /** Enabled jobs left out because not one file has the band they move (e.g. no High sweeps). */
    val skipped: List<String> = emptyList(),
) {
    val requiredBands: Set<Band> get() = units.flatMap { it.second }.toSet()
    val crossovers: List<Crossover> get() = Crossover.entries.filter { xo -> units.any { xo.lower in it.second && xo.upper in it.second } }

    companion object {
        /**
         * Phase IRs: each crossover is corrected where both of its bands were measured, so like
         * Band Align it's judged per crossover. Anything without data is left as a pure delay.
         */
        val PHASE_IRS = phaseIrs(null)

        /** Stereo IR: one Combined sweep per channel/position is all it uses. */
        val STEREO = CheckScope(listOf("Stereo IR" to setOf(Band.COMBINED)), strict = false, purpose = "the Stereo IR")

        fun phaseIrs(measurements: List<Measurement>?): CheckScope {
            val (used, skipped) = Crossover.entries.partition { xo ->
                val outer = if (xo == Crossover.LOW_MID) Band.LOW else Band.HIGH
                measurements == null || measurements.any { it.band == outer }
            }
            return CheckScope(
                used.map { "${it.label} correction" to setOf(it.lower, it.upper) },
                strict = false, purpose = "the Phase IRs",
                skipped = skipped.map { "${it.label}: no ${(if (it == Crossover.LOW_MID) Band.LOW else Band.HIGH).label} sweeps" },
            )
        }

        /**
         * One unit per enabled pair, so a position with only one pair's bands still counts for that
         * pair. Given the [measurements], an enabled pair whose moving band has no files at all is
         * left out (and reported), so it doesn't impose its crossover's coverage on Mid.
         */
        /** One unit per crossover the Crossover Finder searches, like Band Align's pairs. */
        fun crossoverFinder(f: FinderOptions, measurements: List<Measurement>? = null): CheckScope {
            val (used, skipped) = f.crossovers.partition { xo ->
                val outer = if (xo == Crossover.LOW_MID) Band.LOW else Band.HIGH
                measurements == null || measurements.any { it.band == outer }
            }
            return CheckScope(
                used.map { "${it.label} search" to setOf(it.lower, it.upper) },
                strict = false, purpose = FINDER_PURPOSE,
                skipped = skipped.map { "${it.label}: no ${(if (it == Crossover.LOW_MID) Band.LOW else Band.HIGH).label} sweeps" },
            )
        }

        const val FINDER_PURPOSE = "the Crossover Finder"
        const val EQ_PURPOSE = "the EQ Wizard"

        /** EQ Wizard: each side's Combined sweeps, or its band solos summed when there are none. */
        fun eqWizard(measurements: List<Measurement>?): CheckScope =
            if (measurements == null || measurements.any { it.band == Band.COMBINED }) CheckScope(listOf("EQ" to setOf(Band.COMBINED)), strict = false, purpose = EQ_PURPOSE)
            else CheckScope(listOf("EQ" to Band.BRANCHES.toSet()), strict = false, purpose = EQ_PURPOSE)

        fun bandAlign(a: AlignOptions, measurements: List<Measurement>? = null): CheckScope {
            val enabled = AlignPair.entries.filter { if (it == AlignPair.LOW_MID) a.lowMid else a.midHigh }
            val (used, skipped) = enabled.partition { p -> measurements == null || measurements.any { it.band == p.moving } }
            return CheckScope(
                used.map { "${it.label} Band Align" to setOf(it.moving, Band.MID) },
                strict = false, purpose = "Band Align",
                skipped = skipped.map { "${it.label}: no ${it.moving.label} sweeps" },
            )
        }
    }
}

object Checks {
    /** Which bands each seat/position/channel has, straight from the list (no file reading). */
    fun completeness(measurements: List<Measurement>): Map<GroupKey, Set<Band>> =
        measurements.groupBy { GroupKey(it.seat, it.position.trim(), it.channel) }
            .mapValues { (_, ms) -> ms.map { it.band }.toSet() }
            .toSortedMap()

    fun run(set: MeasurementSet, scope: CheckScope = CheckScope.PHASE_IRS): CheckResult {
        val o = set.options
        val items = ArrayList<CheckItem>()
        fun err(t: String, d: String) { items += CheckItem(Severity.ERROR, t, d) }
        fun warn(t: String, d: String) { items += CheckItem(Severity.WARNING, t, d) }
        fun info(t: String, d: String) { items += CheckItem(Severity.INFO, t, d) }

        // Only what reading needs: the IR filter settings are the Phase IR design's to check, so
        // they never block the Band Align tool, which can't reach them.
        try { o.validateMeasurement() } catch (e: IllegalArgumentException) { err("Measurement settings", e.message ?: "Invalid settings") }
        if (set.measurements.isEmpty()) {
            err("No measurements", "Import software-band-solo sweeps for ${scope.purpose}.")
            return CheckResult(items, emptyList())
        }
        val finding = scope.purpose == CheckScope.FINDER_PURPOSE
        val aligning = scope.purpose == "Band Align" || finding
        // Phase IRs in Per-band mode with only Combined sweeps: those are the Stereo IR's input.
        val combinedOnly = !aligning && set.measurements.all { it.band == Band.COMBINED }
        // EQ Wizard without Combined sweeps and without full Low/Mid/High sets: most likely full-range
        // sweeps tagged with a band (a name without "combined" takes the default band).
        if (scope.purpose == CheckScope.EQ_PURPOSE && set.measurements.none { it.band == Band.COMBINED } &&
            set.measurements.groupBy { GroupKey(it.seat, it.position.trim(), it.channel) }.values.none { ms -> Band.BRANCHES.all { b -> ms.any { it.band == b } } }) {
            val tags = set.measurements.map { it.band }.distinct().joinToString("/") { it.label }
            err("No full-system sweeps for the EQ Wizard", "Your files are tagged $tags. If they are full-range sweeps (all bands playing), set their Band to Combined on the Measurements page. Otherwise import Low, Mid and High solos for each position so they can be summed.")
            return CheckResult(items, emptyList())
        }
        if (combinedOnly && scope.requiredBands.isEmpty()) {
            err("Only Combined sweeps: switch to Stereo mode", "Per-band IRs need Low/Mid/High band-solo sweeps. Combined sweeps (all bands playing) are what the Stereo IR uses: choose Convolver mode › Stereo (on this page or Design).")
            return CheckResult(items, emptyList())
        }
        for (s in scope.skipped) {
            if (finding) warn("$s, so it isn't searched", "Switch that crossover off on the Crossover Finder page, or import its sweeps.")
            else if (aligning) warn("$s, so it isn't aligned", "Switch that pair off on the Band Align page, or import its sweeps.")
            else warn("$s, so it isn't corrected", "Its IRs pass that crossover unchanged (pure delay). Import its sweeps to correct it.")
        }
        if (scope.requiredBands.isEmpty()) {
            err(if (finding) "Nothing to search" else if (aligning) "Nothing to align" else "Nothing to correct", when {
                !aligning -> "Import Low+Mid or Mid+High band-solo sweeps."
                scope.skipped.isEmpty() -> if (finding) "Switch on Low/Mid, Mid/High or both." else "Switch on Low against Mid, High against Mid, or both."
                else -> "None of the switched-on pairs has sweeps for its bands."
            })
            return CheckResult(items.sortedBy { it.severity.ordinal }, emptyList())
        }
        for (e in set.loadErrors) err("Cannot use ${MeasurementSet.fileName(e.measurement)}", e.message)

        // Timing references: each channel's sweeps must share one unchanged reference, or the
        // band-to-band phase the designer aligns is meaningless.
        // (Magnitude only, as for the EQ Wizard: no phase is compared, so no reference is needed.)
        if (!o.magnitudeOnly) for (ch in Channel.entries) {
            val refs = set.measurements.filter { it.channel == ch }.map { it.timingReference.trim() }.toSet()
            if (refs.size > 1) err("${ch.label}: more than one timing reference", "Found ${refs.joinToString()}. Every ${ch.label.lowercase()} sweep must use the same unchanged timing reference.")
            if (refs.any { it.isEmpty() }) err("${ch.label}: missing timing reference", "Enter the timing-reference ID used for each sweep.")
        }

        // Group completeness and consistency.
        val needed = Band.BRANCHES.filter { it in scope.requiredBands }
        for ((key, ms) in set.measurements.groupBy { GroupKey(it.seat, it.position.trim(), it.channel) }) {
            val bands = ms.map { it.band }.toSet()
            for ((unit, unitBands) in scope.units) {
                val missing = Band.BRANCHES.filter { it in unitBands && it !in bands }
                if (missing.isEmpty()) continue
                val what = "Missing ${missing.joinToString { it.label }} software-band-solo sweep(s)"
                if (scope.strict) err("${key.label}: incomplete", "$what.")
                else warn("${key.label}: not used for $unit", "$what for it.")
            }
            if (ms.map { it.validationOnly }.toSet().size > 1) err("${key.label}: mixed validation flag", "Mark all bands at a position validation-only, or none.")
            val cal = ms.filter { it.band != Band.COMBINED }.map { it.calibrationDb }.toSet()
            if (cal.size > 1) warn("${key.label}: calibration offsets differ", "Bands at one position normally share a calibration offset; different offsets change their relative level and the computed band sum.")
        }
        fun usable(g: LoadedGroup, bands: Set<Band>) = g.has(bands) && !g.validationOnly
        if (scope.strict) {
            val bands = scope.requiredBands
            for (seat in Seat.entries) for (ch in Channel.entries) {
                if (set.groups.none { it.key.seat == seat && it.key.channel == ch && usable(it, bands) }) {
                    err("No design data: ${seat.label} ${ch.label}", "Need at least one complete Low/Mid/High triplet that is not validation-only.")
                }
            }
        } else if (scope.units.none { (_, bands) -> set.groups.any { usable(it, bands) } }) {
            err(if (finding) "Nothing to search" else if (scope.purpose == "Band Align") "Nothing to align" else "Nothing to correct", "No position has the sweeps for " + scope.units.joinToString(" or ") { (u, b) -> "$u (" + Band.BRANCHES.filter { it in b }.joinToString("/") { it.label } + ")" } + " that aren't validation-only.")
        } else {
            // Judged per pair, the same way Band Align picks positions for each pair.
            for ((unit, bands) in scope.units) for (ch in Channel.entries) {
                val bandList = Band.BRANCHES.filter { it in bands }.joinToString("/") { it.label }
                val seats = Seat.entries.filter { st -> set.groups.any { it.key.seat == st && it.key.channel == ch && usable(it, bands) } }
                when {
                    seats.isEmpty() -> warn("${ch.label} side: no $unit", "No $bandList sweeps for the ${ch.label.lowercase()} side." + if (aligning) "" else " Its IRs pass that crossover unchanged.")
                    seats.size == 1 -> warn("${ch.label} side $unit: ${seats.single().label} seat only", "The other seat has no $bandList sweeps, so it isn't checked for getting worse.")
                }
            }
        }

        val freqs = set.freqs
        val combinedOk = ArrayList<Double>()
        for (g in set.groups) {
            val kinds = g.responses.values.map { it.kind }.toSet()
            if (kinds.size > 1) warn("${g.key.label}: mixed file types", "REW text and impulse WAVs at the same position must share one calibration and time origin.")
            for ((band, r) in g.responses) {
                // Coarse smoothing only matters where phase gets summed.
                if (r.heavilySmoothed && r.hasPhase && !(o.magnitudeOnly && band == Band.COMBINED)) warn("${g.key.label} ${band.label}: smoothed too coarsely (${r.smoothing})", "Phase smoothed coarser than 1/12 octave can't be summed reliably. Re-export from REW with 1/48 octave or no smoothing.")
                val p = r.impulsePeakIndex
                if (p != null && p < 2 && r.kind == SourceKind.IMPULSE_WAV) warn("${g.key.label} ${band.label}: impulse peak at sample $p", "The WAV may have been trimmed to its own peak, losing the shared time origin. Export impulses with the timing-reference offset preserved.")
            }
            // Bands should sit in frequency order; otherwise the tags are probably swapped.
            val present = Band.BRANCHES.filter { it in g.responses }
            val centroids = present.map { energyCentroidHz(g.branch(it), freqs) }
            if (present.size >= 2 && centroids.zipWithNext().any { (a, b) -> a >= b }) {
                warn("${g.key.label}: band order looks wrong", "Energy centres: " + present.zip(centroids).joinToString { (b, c) -> "${b.label} %.0f Hz".format(c) } + ". Check the band tags.")
            }
            for (xo in Crossover.entries) {
                if (xo.lower !in g.responses || xo.upper !in g.responses) continue
                val hz = xo.hz(o)
                val a = levelNear(g.branch(xo.lower), freqs, hz)
                val b = levelNear(g.branch(xo.upper), freqs, hz)
                if (abs(a - b) > 15) warn("${g.key.label}: ${xo.label} levels far apart", "${xo.lower.label} %.1f dB vs ${xo.upper.label} %.1f dB at %.0f Hz. Check the crossover frequency you entered, band tags, gains or mutes.".format(a, b, hz))
            }
            // A Combined sweep plays every band, so it can only be checked against all three.
            // (Only with phase: without it the two can't be compared.)
            if (g.isComplete && g.responses.values.all { it.hasPhase }) g.responses[Band.COMBINED]?.let { combined ->
                val sum = g.branchSum()
                val c = combined.response
                var num = 0.0
                var den = 0.0
                for (i in freqs.indices) {
                    if (freqs[i] !in combined.coverageHz) continue
                    num += (c.re[i] - sum.re[i]).let { it * it } + (c.im[i] - sum.im[i]).let { it * it }
                    den += c.re[i] * c.re[i] + c.im[i] * c.im[i]
                }
                val rel = sqrt(num / maxOf(den, 1e-24))
                if (rel > 0.25) warn("${g.key.label}: combined sweep ≠ band sum", "Relative complex error %.2f. Timing, gain, routing or dynamics changed between sweeps; predictions will be unreliable.".format(rel))
                else combinedOk += rel
            }
        }

        // Export smoothing from REW stacks with the app's own measurement smoothing, and files
        // smoothed differently don't match each other.
        val rewSmoothing = set.groups.flatMap { it.responses.values }.filter { it.kind == SourceKind.REW_TEXT }
        val labels = rewSmoothing.groupingBy { it.smoothing?.trim()?.lowercase() ?: "not stated" }.eachCount()
        if (labels.size > 1) {
            warn("Files were exported with different smoothing", labels.entries.joinToString { "${it.key}: ${it.value}" } +
                ". Bands smoothed differently don't line up exactly. Re-export them all the same way, ideally 1/48 octave or none.")
        }
        val app = o.measurementSmoothing
        if (app > 0) {
            rewSmoothing.mapNotNull { it.presmoothingOctaveDenominator }.filter { it <= 24 }.groupingBy { it }.eachCount().forEach { (n, count) ->
                val total = 1 / kotlin.math.sqrt(1.0 / (n * n) + 1.0 / (app * app))
                warn("$count file(s) already smoothed 1/$n octave in REW",
                    "The app adds its own 1/$app on top, about 1/%.0f octave in total. Export from REW at 1/48 octave or no smoothing, or set the app's smoothing to None.".format(total))
            }
        }

        val phaseless = set.groups.flatMap { g -> g.responses.filterValues { !it.hasPhase }.keys.map { g to it } }
        if (phaseless.isNotEmpty()) {
            info("${phaseless.size} file(s) read as magnitude only", "No phase column, or no REW delay note. That's fine for the EQ, which uses magnitude only.")
            for ((g, _) in phaseless.distinctBy { it.first.key }) {
                if (Band.COMBINED !in g.responses && g.responses.keys.any { it in Band.BRANCHES }) {
                    warn("${g.key.label}: band solos without phase", "Bands can only be summed with their phase, so this position isn't used. Import its Combined sweep (all bands playing), or re-export the solos with phase.")
                }
            }
        }
        if (combinedOk.isNotEmpty()) info("Combined sweeps match the band sum", "${combinedOk.size} position/channel(s); worst relative complex error %.2f.".format(combinedOk.max()))

        // Positions far from their seat's typical level suggest a gain change or a moved mic.
        for (seat in Seat.entries) for (ch in Channel.entries) {
            val gs = set.groups.filter { it.key.seat == seat && it.key.channel == ch && it.has(needed) }
            if (gs.size < 3 || needed.isEmpty()) continue
            val curves = gs.map { g -> g.branchSum().let { s -> DoubleArray(freqs.size) { db(s.abs(it)) } } }
            val median = DoubleArray(freqs.size) { i -> curves.map { it[i] }.sorted()[curves.size / 2] }
            for ((g, c) in gs.zip(curves)) {
                val offset = c.indices.map { c[it] - median[it] }.average()
                if (abs(offset) > 6) warn("${g.key.label}: level outlier", "%+.1f dB from the seat's median position. Check source gain and mic placement.".format(offset))
            }
        }

        // Phase consistency: one shared filter can only help if positions agree on the band offset.
        val phases = ArrayList<CrossoverPhase>()
        for (ch in Channel.entries) for (xo in scope.crossovers) {
            val bySeat = Seat.entries.mapNotNull { seat ->
                val gs = set.groups.filter { it.key.seat == seat && it.key.channel == ch && it.has(listOf(xo.lower, xo.upper)) && !it.validationOnly && it.responses.values.all { r -> r.hasPhase } }
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

        val usableAny = { g: LoadedGroup -> scope.units.any { g.has(it.second) } }
        val design = set.groups.count { usableAny(it) && !it.validationOnly }
        val held = set.groups.count { usableAny(it) && it.validationOnly }
        info("Measurement set", "$design usable position/channel set(s) for ${scope.purpose}, $held validation-only, ${set.measurements.size} file(s). Analysis range %.0f–%.0f Hz.".format(freqs.first(), freqs.last()))
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
