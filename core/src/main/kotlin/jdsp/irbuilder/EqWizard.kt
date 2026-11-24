package jdsp.irbuilder

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Which side(s) a J_DSP PEQ band applies to (its L+R / L / R choice; "ALL" in APO text). */
@Serializable
enum class EqSide(val apo: String, val label: String) { BOTH("ALL", "L+R"), LEFT("L", "L"), RIGHT("R", "R") }

/** One J_DSP PEQ band: peaking (PK), as J_DSP's Parametric EQ runs it. */
@Serializable
data class EqBand(val hz: Double, val gainDb: Double, val q: Double, val side: EqSide = EqSide.BOTH)

/** One point of a house curve: level relative to the rest of the curve, dB. */
@Serializable
data class CurvePoint(val hz: Double, val db: Double)

/**
 * A house curve as points, joined straight in log frequency and held flat beyond its ends. Only
 * its shape matters: the wizard levels it to the measured response.
 */
object HouseCurve {
    fun at(points: List<CurvePoint>, hz: Double): Double {
        if (points.isEmpty()) return 0.0
        val p = points.sortedBy { it.hz }
        if (hz <= p.first().hz) return p.first().db
        if (hz >= p.last().hz) return p.last().db
        val k = p.indexOfFirst { it.hz >= hz }
        val a = p[k - 1]
        val b = p[k]
        return a.db + (b.db - a.db) * log2(hz / a.hz) / log2(b.hz / a.hz)
    }

    /**
     * Reads a house curve or target file: REW's house curve format ("freq dB" per line, "*"
     * comments) and most others: tab, space, comma or semicolon separated, a comma as the decimal
     * point, extra columns (phase) ignored, "#", ";" and "//" comments, a header row skipped.
     */
    fun parse(text: String): List<CurvePoint> {
        val out = ArrayList<CurvePoint>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore("//").trim()
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("#") || line.startsWith(";")) continue
            val nums = twoNumbers(line) ?: continue
            val hz = nums.first
            val db = nums.second
            if (!hz.isFinite() || !db.isFinite() || hz <= 0) continue
            if (out.isNotEmpty() && hz <= out.last().hz) continue
            out += CurvePoint(hz, db)
        }
        require(out.size >= 2) { "No frequency / dB rows found. Use one \"frequency dB\" pair per line." }
        require(out.size <= 2000) { "The curve has ${out.size} points; keep it under 2000" }
        return out
    }

    /** The first two numbers of a row: ";"-separated, whitespace-separated, or CSV. */
    private fun twoNumbers(line: String): Pair<Double, Double>? {
        val fields = when {
            ';' in line -> line.split(';')
            line.trim().split(Regex("\\s+")).size >= 2 -> line.trim().split(Regex("\\s+")).map { it.trimEnd(',') }
            else -> line.split(',')
        }.map { it.trim().replace(',', '.') }.filter { it.isNotEmpty() }
        if (fields.size < 2) return null
        val a = fields[0].toDoubleOrNull() ?: return null
        val b = fields[1].toDoubleOrNull() ?: return null
        return a to b
    }

    fun text(points: List<CurvePoint>): String =
        "* House curve from J_DSP IR Builder (frequency Hz, level dB)\n" + points.joinToString("") { "%.2f %.2f\n".format(java.util.Locale.ROOT, it.hz, it.db) }

    /** The built-in curve as editable points: a starting point for a custom one. */
    fun fromBuiltIn(o: EqOptions): List<CurvePoint> =
        listOf(20.0, 40.0, 63.0, 100.0, 160.0, 250.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0).map { CurvePoint(it, (EqWizard.builtInCurve(it, o) * 10).roundToInt() / 10.0) }
}

/** EQ Wizard settings saved with the project. */
@Serializable
data class EqOptions(
    /** Fit between these frequencies; outside them the EQ is left alone. */
    val minHz: Double = 30.0,
    val maxHz: Double = 12000.0,
    /** House curve: lift below ~100 Hz (low shelf shape), dB. */
    val bassLiftDb: Double = 6.0,
    /** House curve: tilt from 1 kHz to 10 kHz, dB (negative is a falling treble). */
    val trebleTiltDb: Double = -3.0,
    /** Bands per side (J_DSP's Pre EQ bank holds 16 in all). */
    val maxBands: Int = 8,
    /** Largest boost a band may give; boosting into cancellation wastes headroom. */
    val maxBoostDb: Double = 4.0,
    val maxCutDb: Double = 12.0,
    /** Narrowest band (highest Q) the wizard may use for a cut; boosts stay at Q ≤ 3. */
    val maxQ: Double = 8.0,
    /** One set of bands for both sides (L+R), fitted to their average; else per side. */
    val linked: Boolean = false,
    /** A custom house curve, used instead of the built-in one (bass lift, treble tilt) when [useCurve]. */
    val curve: List<CurvePoint> = emptyList(),
    val useCurve: Boolean = false,
) {
    fun validate() {
        require(listOf(minHz, maxHz, bassLiftDb, trebleTiltDb, maxBoostDb, maxCutDb, maxQ).all { it.isFinite() }) { "EQ settings must be finite numbers" }
        require(minHz >= 20 && maxHz <= 20000 && maxHz >= minHz * 4) { "Fit range must be within 20 Hz–20 kHz and span at least two octaves" }
        require(bassLiftDb in -6.0..15.0) { "Bass lift must be −6 to +15 dB" }
        require(trebleTiltDb in -12.0..6.0) { "Treble tilt must be −12 to +6 dB" }
        require(maxBands in 1..16) { "Bands per side must be 1 to 16 (J_DSP's limit per bank)" }
        require(!(maxBands > 8 && !linked)) { "Per-side EQ uses the bank twice: at most 8 bands per side to fit J_DSP's 16" }
        require(maxBoostDb in 0.0..12.0 && maxCutDb in 0.0..30.0) { "Boost must be 0–12 dB and cut 0–30 dB" }
        require(maxQ in 1.0..30.0) { "Max Q must be 1 to 30" }
        require(!useCurve || curve.size >= 2) { "A custom house curve needs at least two points: import one or add points" }
        require(curve.all { it.hz.isFinite() && it.db.isFinite() && it.hz in 10.0..24000.0 && it.db in -40.0..40.0 }) { "House curve points must be 10 Hz–24 kHz and −40 to +40 dB" }
        require(curve.map { it.hz }.toSet().size == curve.size) { "House curve points need different frequencies" }
    }
}

/** One side's fit. */
class EqSideResult(
    val side: EqSide,
    /** Seat-weighted average response, 1/6-octave smoothed, dB. */
    val measuredDb: DoubleArray,
    val targetDb: DoubleArray,
    /** What the EQ should do: target − measured, with boosts held back (see [EqWizard]). */
    val wantedDb: DoubleArray,
    val eqDb: DoubleArray,
    /** Per seat: the average after the EQ, dB. */
    val seatsAfterDb: Map<Seat, DoubleArray>,
    val seatsBeforeDb: Map<Seat, DoubleArray>,
    val bands: List<EqBand>,
    /** RMS deviation from the target over the fit range, before and after, dB. */
    val rmsBeforeDb: Double,
    val rmsAfterDb: Double,
)

class EqResult(val freqs: DoubleArray, val options: EqOptions, val sides: List<EqSideResult>, val preampDb: Double, val warnings: List<String>) {
    val bands: List<EqBand> get() = sides.flatMap { it.bands }
}

/**
 * EQ Wizard: J_DSP Parametric EQ bands (PK) that bring the system's response toward a target at
 * both seats, after Resonalyze's EQ Wizard idea (github.com/DIMOSUS/Resonalyze, MIT).
 *
 * The response is each side's Combined sweep (or the sum of its band solos), power-averaged over
 * the positions of each seat, then the seats weighted; smoothed to 1/6 octave. Measured through
 * the tune as it is, so the bands are corrections on top of the current EQ: append them to Pre EQ.
 * The target is a house curve (a bass lift and a treble tilt) levelled to the response between
 * 300 Hz and 3 kHz.
 *
 * Boosts are held back: never more than the max boost, and never more than the same response
 * smoothed over a whole octave lacks, so a narrow dip (cancellation or a seat-specific null) isn't
 * filled with a boost it would only waste headroom on. Bands are placed one at a time on the
 * largest remaining error, then all refined together; the predicted EQ uses J_DSP's own biquad.
 */
object EqWizard {
    private const val TOLERANCE_DB = 0.75

    fun solve(set: MeasurementSet, o: EqOptions): EqResult {
        o.validate()
        val f = set.freqs
        val n = f.size
        val fs = set.options.sampleRate.toDouble()
        val warnings = ArrayList<String>()
        fun response(g: LoadedGroup): CVec? = when {
            Band.COMBINED in g.responses -> g.responses.getValue(Band.COMBINED).response
            // Bands only add up with their phase; without it the sum would be guesswork.
            g.responses.any { (b, r) -> b in Band.BRANCHES && !r.hasPhase } -> {
                warnings += "${g.key.label}: its band solos have no phase, so they can't be summed; import its Combined sweep instead"
                null
            }
            g.has(Band.BRANCHES) -> g.branchSum()
            g.responses.keys.any { it in Band.BRANCHES } -> { warnings += "${g.key.label}: not every band was swept, so its sum is partial"; g.branchSum() }
            else -> null
        }
        // Per side and seat: power average over positions.
        val perSeat = HashMap<Pair<Channel, Seat>, DoubleArray>()
        for (ch in Channel.entries) for (seat in Seat.entries) {
            val rs = set.groups.filter { it.key.channel == ch && it.key.seat == seat && !it.validationOnly }.mapNotNull(::response)
            if (rs.isEmpty()) continue
            perSeat[ch to seat] = DoubleArray(n) { i -> rs.sumOf { r -> r.re[i] * r.re[i] + r.im[i] * r.im[i] } / rs.size }
        }
        require(perSeat.isNotEmpty()) { (listOf("No Combined or band-solo sweeps to equalise") + warnings).joinToString(". ") }
        fun seatAverage(keys: List<Pair<Channel, Seat>>): DoubleArray {
            val ks = keys.filter { it in perSeat }
            val w = ks.map { set.options.seatWeight(it.second) }
            return DoubleArray(n) { i -> ks.indices.sumOf { k -> w[k] * perSeat.getValue(ks[k])[i] } / w.sum() }
        }
        val sides: List<Pair<EqSide, List<Channel>>> =
            if (o.linked) listOf(EqSide.BOTH to Channel.entries.filter { c -> Seat.entries.any { (c to it) in perSeat } })
            else Channel.entries.filter { c -> Seat.entries.any { (c to it) in perSeat } }.map { (if (it == Channel.L) EqSide.LEFT else EqSide.RIGHT) to listOf(it) }
        for (seat in Seat.entries) if (perSeat.keys.none { it.second == seat }) warnings += "No ${seat.label.lowercase()} seat measurements: the EQ is fitted to the other seat only"

        val results = sides.map { (side, chs) ->
            val power = seatAverage(chs.flatMap { c -> Seat.entries.map { c to it } })
            // Only what was swept: a band-limited sweep reads as silence outside its range.
            val covered = BooleanArray(n) { power[it] > 0 }
            val inFit = BooleanArray(n) { f[it] in o.minHz..o.maxHz && covered[it] }
            require(inFit.any { it }) { "No measured data in the fit range ${o.minHz.toInt()}–${o.maxHz.toInt()} Hz" }
            val measured = toDb(powerSmooth(power, f, 1.0 / 6))
            val wide = toDb(powerSmooth(power, f, 1.0))
            // Level the house curve to the response over 300 Hz–3 kHz where that was measured and
            // is in the fit range, else over the measured fit range itself.
            val refIdx = f.indices.filter { inFit[it] && f[it] in 300.0..3000.0 }.ifEmpty { f.indices.filter { inFit[it] } }
            val offset = refIdx.map { measured[it] - houseCurve(f[it], o) }.average()
            val target = DoubleArray(n) { offset + houseCurve(f[it], o) }
            val wanted = DoubleArray(n) { i ->
                if (!inFit[i]) 0.0 else {
                    val d = target[i] - measured[i]
                    if (d > 0) minOf(d, o.maxBoostDb, max(0.0, target[i] - wide[i])) else max(d, -o.maxCutDb)
                }
            }
            val bands = fit(f, wanted, inFit, o, side, fs)
            val eq = eqDb(bands, f, fs)
            fun rms(after: Boolean): Double {
                val idx = f.indices.filter { inFit[it] }
                return sqrt(idx.sumOf { i -> (measured[i] + (if (after) eq[i] else 0.0) - target[i]).pow(2) } / idx.size)
            }
            val seats = Seat.entries.filter { s -> chs.any { (it to s) in perSeat } }
            EqSideResult(
                side, measured, target, wanted, eq,
                seatsAfterDb = seats.associateWith { s -> toDb(powerSmooth(seatAverage(chs.map { it to s }), f, 1.0 / 6)).let { m -> DoubleArray(n) { m[it] + eq[it] } } },
                seatsBeforeDb = seats.associateWith { s -> toDb(powerSmooth(seatAverage(chs.map { it to s }), f, 1.0 / 6)) },
                bands = bands, rmsBeforeDb = rms(false).round(2), rmsAfterDb = rms(true).round(2),
            )
        }
        // Headroom: the largest boost anywhere, taken off the preamp (J_DSP allows −30..+12 dB).
        val peak = results.maxOf { r -> eqDb(r.bands, geomspace(20.0, min(20000.0, fs * 0.45), 400), fs).max() }
        val preamp = if (peak > 0) -(Math.ceil(peak * 2) / 2).coerceAtMost(30.0) else 0.0
        return EqResult(f, o, results, preamp, warnings.distinct())
    }

    /** The house curve's shape, dB: the custom curve if there is one, else the built-in one. */
    fun houseCurve(hz: Double, o: EqOptions): Double = if (o.useCurve && o.curve.size >= 2) HouseCurve.at(o.curve, hz) else builtInCurve(hz, o)

    /** The built-in shape, dB: a low-shelf lift below ~100 Hz and a log tilt from 1 to 10 kHz. */
    fun builtInCurve(hz: Double, o: EqOptions): Double {
        val shelf = o.bassLiftDb / (1 + (hz / 100.0).pow(2))
        val tilt = if (hz <= 1000) 0.0 else o.trebleTiltDb * log10(hz / 1000.0)
        return shelf + tilt
    }

    /** J_DSP's peaking biquad (BiquadUtils.computeCoefficients, PEAKING), magnitude in dB. */
    fun peakDb(b: EqBand, hz: Double, fs: Double): Double {
        val a = 10.0.pow(b.gainDb / 40.0)
        val w = 2.0 * PI * b.hz / fs
        val alpha = sin(w) / (2.0 * b.q)
        val c = cos(w)
        val b0 = 1.0 + alpha * a; val b1 = -2.0 * c; val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a; val a1 = -2.0 * c; val a2 = 1.0 - alpha / a
        val o = 2.0 * PI * hz / fs
        val cw = cos(o); val c2 = cos(2 * o); val sw = sin(o); val s2 = sin(2 * o)
        val nr = b0 + b1 * cw + b2 * c2; val ni = -(b1 * sw + b2 * s2)
        val dr = a0 + a1 * cw + a2 * c2; val di = -(a1 * sw + a2 * s2)
        return 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    fun eqDb(bands: List<EqBand>, f: DoubleArray, fs: Double) = DoubleArray(f.size) { i -> bands.sumOf { peakDb(it, f[i], fs) } }

    internal fun fit(f: DoubleArray, wanted: DoubleArray, inFit: BooleanArray, o: EqOptions, side: EqSide, fs: Double): List<EqBand> {
        val idx = f.indices.filter { inFit[it] }
        val bands = ArrayList<EqBand>()
        fun clampBand(b: EqBand): EqBand {
            val g = b.gainDb.coerceIn(-o.maxCutDb, o.maxBoostDb)
            val qMax = if (g > 0) min(3.0, o.maxQ) else o.maxQ
            return b.copy(hz = b.hz.coerceIn(o.minHz, o.maxHz), gainDb = g, q = b.q.coerceIn(0.3, qMax))
        }
        // How far the EQ misses what's wanted: mean square, dB².
        fun fitError(bs: List<EqBand>): Double {
            var acc = 0.0
            for (i in idx) {
                var e = 0.0
                for (b in bs) e += peakDb(b, f[i], fs)
                val d = wanted[i] - e
                acc += d * d
            }
            return acc / idx.size
        }
        // What the refinement minimises: the fit error, plus a charge for bands that work against
        // each other (see [overlapPenalty]) so a boost and a cut don't stack on one spot.
        fun cost(bs: List<EqBand>) = fitError(bs) + overlapPenalty(bs)
        repeat(o.maxBands) {
            val eq = eqDb(bands, f, fs)
            val residual = DoubleArray(f.size) { if (inFit[it]) wanted[it] - eq[it] else 0.0 }
            // The largest error, averaged over ±1/12 octave so one spike can't claim a band.
            val sm = powerSmoothSigned(residual, f, 1.0 / 6)
            val peak = idx.maxByOrNull { abs(sm[it]) } ?: return@repeat
            if (abs(sm[peak]) < TOLERANCE_DB) return@repeat
            val g = sm[peak]
            var lo = peak
            var hi = peak
            while (lo > 0 && inFit[lo - 1] && sm[lo - 1] * g > 0 && abs(sm[lo - 1]) > abs(g) / 2) lo--
            while (hi < f.size - 1 && inFit[hi + 1] && sm[hi + 1] * g > 0 && abs(sm[hi + 1]) > abs(g) / 2) hi++
            val bw = max(log2(f[hi] / f[lo]), 1.0 / 6)
            val q = sqrt(2.0.pow(bw)) / (2.0.pow(bw) - 1)
            bands += clampBand(EqBand(f[peak], g, q, side))
            refine(bands, ::cost, ::clampBand)
        }
        // Drop bands that barely help: while removing one costs less than PRUNE_RMS_DB of fit
        // (RMS), remove the least useful and re-fit the rest.
        while (bands.size > 1) {
            val base = sqrt(fitError(bands))
            val (k, loss) = bands.indices.map { k -> k to sqrt(fitError(bands.filterIndexed { j, _ -> j != k })) - base }.minBy { it.second }
            if (loss >= PRUNE_RMS_DB) break
            bands.removeAt(k)
            refine(bands, ::cost, ::clampBand)
        }
        return bands.map { b -> b.copy(hz = b.hz.roundToInt().toDouble(), gainDb = (b.gainDb * 10).roundToInt() / 10.0, q = (b.q * 100).roundToInt() / 100.0) }
            .filter { abs(it.gainDb) >= 0.2 }
            .sortedBy { it.hz }
    }

    /** Least fit improvement (RMS, dB) a band must give to be kept. */
    private const val PRUNE_RMS_DB = 0.1

    /**
     * Charge for bands working against each other: a boost and a cut within half an octave (they
     * mostly cancel, wasting bands and headroom), and same-direction bands within a sixth of an
     * octave (one band would do). Scaled by the smaller band's gain and how close they are.
     */
    internal fun overlapPenalty(bs: List<EqBand>): Double {
        var pen = 0.0
        for (a in bs.indices) for (b in a + 1 until bs.size) {
            val x = bs[a]
            val y = bs[b]
            val d = abs(log2(x.hz / y.hz))
            val g = min(abs(x.gainDb), abs(y.gainDb))
            if (x.gainDb * y.gainDb < 0) { if (d < 0.5) pen += 0.15 * g * g * (1 - d / 0.5) }
            else if (d < 1.0 / 6) pen += 0.05 * g * g * (1 - d * 6)
        }
        return pen
    }

    /** Coordinate descent on every band's frequency, gain and Q (log steps for f and Q). */
    private fun refine(bands: MutableList<EqBand>, cost: (List<EqBand>) -> Double, clamp: (EqBand) -> EqBand) {
        var best = cost(bands)
        var stepF = 0.08
        var stepG = 1.0
        var stepQ = 0.15
        repeat(40) {
            var improved = false
            for (k in bands.indices) {
                val trials = listOf<(EqBand) -> EqBand>(
                    { it.copy(hz = it.hz * 2.0.pow(stepF)) }, { it.copy(hz = it.hz / 2.0.pow(stepF)) },
                    { it.copy(gainDb = it.gainDb + stepG) }, { it.copy(gainDb = it.gainDb - stepG) },
                    { it.copy(q = it.q * 2.0.pow(stepQ)) }, { it.copy(q = it.q / 2.0.pow(stepQ)) },
                )
                for (t in trials) {
                    val old = bands[k]
                    bands[k] = clamp(t(old))
                    val c = cost(bands)
                    if (c < best - 1e-9) { best = c; improved = true } else bands[k] = old
                }
            }
            if (!improved) { stepF /= 2; stepG /= 2; stepQ /= 2 }
            if (stepG < 0.02) return
        }
    }

    /** Power mean over ±[octaves]/2. */
    private fun powerSmooth(p: DoubleArray, f: DoubleArray, octaves: Double): DoubleArray {
        val half = ((f.size - 1) / log2(f.last() / f.first()) * octaves / 2).roundToInt().coerceAtLeast(1)
        return SumLoss.smooth(p, BooleanArray(p.size) { p[it] > 0 }, half)
    }

    private fun powerSmoothSigned(x: DoubleArray, f: DoubleArray, octaves: Double): DoubleArray {
        val half = ((f.size - 1) / log2(f.last() / f.first()) * octaves / 2).roundToInt().coerceAtLeast(1)
        return SumLoss.smooth(x, BooleanArray(x.size) { true }, half)
    }

    private fun toDb(p: DoubleArray) = DoubleArray(p.size) { 10 * log10(max(p[it], 1e-30)) }

    /**
     * Equalizer APO text, the way J_DSP's Parametric EQ imports it ("Preamp", then "Channel:"
     * sections of "Filter n: ON PK Fc … Hz Gain … dB Q …"). Name the file input_….txt so J_DSP
     * routes it to Pre EQ.
     */
    fun apoText(r: EqResult): String {
        val sb = StringBuilder()
        sb.append("Preamp: ").append(fmt(r.preampDb)).append(" dB\n")
        var k = 1
        for (side in EqSide.entries) {
            val bs = r.bands.filter { it.side == side }
            if (bs.isEmpty()) continue
            sb.append("Channel: ").append(side.apo).append('\n')
            for (b in bs) sb.append("Filter ${k++}: ON PK Fc ${fmt(b.hz)} Hz Gain ${fmt(b.gainDb)} dB Q ${fmt(b.q)}\n")
        }
        return sb.toString()
    }

    private fun fmt(v: Double) = java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    const val EXPORT_FILE = "input_eq_wizard.txt"
}
