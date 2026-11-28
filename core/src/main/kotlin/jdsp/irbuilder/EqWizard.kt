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

/**
 * Which J_DSP Parametric EQ bank a band goes in: Pre EQ (before the band split, every band alike)
 * or one software band's own EQ (Low, Mid or High: only that branch).
 */
@Serializable
enum class EqBank(val label: String, val fileName: String, val band: Band?) {
    PRE("Pre EQ", "input_eq_wizard.txt", null),
    LOW("Low EQ", "low_eq_wizard.txt", Band.LOW),
    MID("Mid EQ", "mid_eq_wizard.txt", Band.MID),
    HIGH("High EQ", "high_eq_wizard.txt", Band.HIGH);

    companion object {
        val BANDS = listOf(LOW, MID, HIGH)
    }
}

/** One J_DSP PEQ band: peaking (PK), as J_DSP's Parametric EQ runs it. */
@Serializable
data class EqBand(val hz: Double, val gainDb: Double, val q: Double, val side: EqSide = EqSide.BOTH, val bank: EqBank = EqBank.PRE)

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
    /** Filters per software band (Low, Mid, High) per side. */
    val maxBands: Int = 4,
    /** Largest boost a band may give; boosting into cancellation wastes headroom. */
    val maxBoostDb: Double = 4.0,
    val maxCutDb: Double = 12.0,
    /** Narrowest band (highest Q) the wizard may use for a cut; boosts stay at Q ≤ 3. */
    val maxQ: Double = 8.0,
    /** Band EQ: one set of bands for both sides (L+R), fitted to their average; else per side. */
    val linked: Boolean = false,
    /** A custom house curve, used instead of the built-in one (bass lift, treble tilt) when [useCurve]. */
    val curve: List<CurvePoint> = emptyList(),
    val useCurve: Boolean = false,
    /**
     * Pre EQ: only the correction both channels need alike (the factory DSP's own colouring), as
     * L+R bands. Off: nothing goes to Pre EQ.
     */
    val preEq: Boolean = true,
    /** Pre EQ filters (L+R, so each takes one of the EQ's 16). */
    val preBands: Int = 6,
    /** The rest (what differs between the sides, or a band's own error) goes to the band EQs. */
    val bandEq: Boolean = true,
    /** Where Low/Mid and Mid/High cross: only used to split the bands when no band solos were swept. */
    val lowMidHz: Double = 150.0,
    val midHighHz: Double = 3500.0,
) {
    fun validate() {
        require(listOf(minHz, maxHz, bassLiftDb, trebleTiltDb, maxBoostDb, maxCutDb, maxQ, lowMidHz, midHighHz).all { it.isFinite() }) { "EQ settings must be finite numbers" }
        require(minHz >= 20 && maxHz <= 20000 && maxHz >= minHz * 4) { "Fit range must be within 20 Hz–20 kHz and span at least two octaves" }
        require(bassLiftDb in -6.0..15.0) { "Bass lift must be −6 to +15 dB" }
        require(trebleTiltDb in -12.0..6.0) { "Treble tilt must be −12 to +6 dB" }
        require(preEq || bandEq) { "Turn on Pre EQ, band EQ or both" }
        require(preBands in 1..16) { "Pre EQ filters must be 1 to 16 (J_DSP's limit per EQ)" }
        require(maxBands in 1..16) { "Band EQ filters must be 1 to 16 per band (J_DSP's limit per EQ)" }
        require(!(maxBands > 8 && !linked)) { "Per-side band EQ uses each EQ twice: at most 8 filters per side to fit J_DSP's 16" }
        require(maxBoostDb in 0.0..12.0 && maxCutDb in 0.0..30.0) { "Boost must be 0–12 dB and cut 0–30 dB" }
        require(maxQ in 1.0..30.0) { "Max Q must be 1 to 30" }
        require(lowMidHz >= 20 && midHighHz > lowMidHz * 2 && midHighHz <= 16000) { "Band split: Low/Mid from 20 Hz, Mid/High more than an octave above it and at most 16 kHz" }
        require(!useCurve || curve.size >= 2) { "A custom house curve needs at least two points: import one or add points" }
        require(curve.all { it.hz.isFinite() && it.db.isFinite() && it.hz in 10.0..24000.0 && it.db in -40.0..40.0 }) { "House curve points must be 10 Hz–24 kHz and −40 to +40 dB" }
        require(curve.map { it.hz }.toSet().size == curve.size) { "House curve points need different frequencies" }
    }
}

/** One side's (channel's) fit: Pre EQ plus that side's band EQs. */
class EqSideResult(
    val side: EqSide,
    /** Seat-weighted average response, 1/6-octave smoothed, dB. */
    val measuredDb: DoubleArray,
    val targetDb: DoubleArray,
    /** What the EQ should do: target − measured, with boosts held back (see [EqWizard]). */
    val wantedDb: DoubleArray,
    /** What all the EQ together does to this side's sum (Pre EQ, and each band EQ weighted by its band's share), dB. */
    val eqDb: DoubleArray,
    /** Per seat: the average after the EQ, dB. */
    val seatsAfterDb: Map<Seat, DoubleArray>,
    val seatsBeforeDb: Map<Seat, DoubleArray>,
    /** Every band that acts on this side: Pre EQ's, then this side's (or L+R) band EQs. */
    val bands: List<EqBand>,
    /** RMS deviation from the target over the fit range, before and after, dB. */
    val rmsBeforeDb: Double,
    val rmsAfterDb: Double,
    /** Pre EQ's part of [eqDb] alone, dB. */
    val preDb: DoubleArray = DoubleArray(eqDb.size),
)

class EqResult(
    val freqs: DoubleArray,
    val options: EqOptions,
    val sides: List<EqSideResult>,
    /** Pre EQ's preamp: minus the largest boost its bands give. */
    val preampDb: Double,
    val warnings: List<String>,
    /** Per band EQ bank: minus the largest boost its bands give (0 when they only cut). */
    val bankPreampDb: Map<EqBank, Double> = emptyMap(),
    /** What Pre EQ was asked for: the part of the correction both sides share, dB (empty if none). */
    val sharedDb: DoubleArray = DoubleArray(0),
) {
    /** Every band once, Pre EQ first, then Low, Mid, High. */
    val bands: List<EqBand> get() = sides.flatMap { it.bands }.distinct().sortedWith(compareBy({ it.bank.ordinal }, { it.side.ordinal }, { it.hz }))
    fun bank(b: EqBank) = bands.filter { it.bank == b }
    fun preamp(b: EqBank) = if (b == EqBank.PRE) preampDb else bankPreampDb[b] ?: 0.0
}

/**
 * EQ Wizard: J_DSP Parametric EQ filters (PK) that bring the system's response toward a target at
 * both seats, after Resonalyze's EQ Wizard idea (github.com/DIMOSUS/Resonalyze, MIT).
 *
 * The response is each side's Combined sweep (or the sum of its band solos), power-averaged over
 * the positions of each seat, then the seats weighted; smoothed to 1/6 octave. Measured through
 * the tune as it is, so the bands are corrections on top of the current EQ: append them.
 * The target is a house curve (a bass lift and a treble tilt) levelled to each side's response
 * (the median difference over the fit range).
 *
 * The correction is split between J_DSP's banks:
 * - **Pre EQ** gets only what both channels need alike: where left and right both want a cut (or
 *   both a boost), the smaller of the two, as L+R bands. That's the factory DSP's own colouring,
 *   common to the whole system; anything one side needs and the other doesn't stays out of it.
 * - **Low / Mid / High EQ** get what's left per side, each only where its software band carries
 *   the sound (≥ 80 % of the power, read from the band solos, or from the crossover frequencies
 *   when only Combined sweeps were taken). The crossover regions, where two bands share the
 *   sound, are left to Band Align and the phase IRs.
 *
 * Boosts are held back: never more than the max boost, and never more than the same response
 * smoothed over a whole octave lacks, so a narrow dip (cancellation or a seat-specific null) isn't
 * filled with a boost it would only waste headroom on. Bands are placed one at a time on the
 * largest remaining error, then all refined together; the predicted EQ uses J_DSP's own biquad,
 * each band EQ weighted by its band's share of the power.
 */
object EqWizard {
    private const val TOLERANCE_DB = 0.75

    /** A software band "carries" a frequency when it has at least this share of the power. */
    internal const val OWN_SHARE = 0.8

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
        fun power(r: CVec) = DoubleArray(n) { i -> r.re[i] * r.re[i] + r.im[i] * r.im[i] }
        // Per side and seat: power average over positions, of the sum and of each band solo.
        val perSeat = HashMap<Pair<Channel, Seat>, DoubleArray>()
        val bandPerSeat = HashMap<Pair<Channel, Seat>, Map<Band, DoubleArray>>()
        for (ch in Channel.entries) for (seat in Seat.entries) {
            val gs = set.groups.filter { it.key.channel == ch && it.key.seat == seat && !it.validationOnly }
            val rs = gs.mapNotNull(::response)
            if (rs.isEmpty()) continue
            perSeat[ch to seat] = DoubleArray(n) { i -> rs.sumOf { r -> r.re[i] * r.re[i] + r.im[i] * r.im[i] } / rs.size }
            // Band shares need magnitude only, so solos without phase count here.
            val solos = gs.filter { it.has(Band.BRANCHES) }
            if (solos.isNotEmpty()) bandPerSeat[ch to seat] = Band.BRANCHES.associateWith { b ->
                val ps = solos.map { power(it.branch(b)) }
                DoubleArray(n) { i -> ps.sumOf { it[i] } / ps.size }
            }
        }
        require(perSeat.isNotEmpty()) { (listOf("No Combined or band-solo sweeps to equalise") + warnings).joinToString(". ") }
        fun weighted(keys: List<Pair<Channel, Seat>>, of: (Pair<Channel, Seat>) -> DoubleArray?): DoubleArray? {
            val ks = keys.filter { of(it) != null }
            if (ks.isEmpty()) return null
            val w = ks.map { set.options.seatWeight(it.second) }
            val arrs = ks.map { of(it)!! }
            return DoubleArray(n) { i -> arrs.indices.sumOf { k -> w[k] * arrs[k][i] } / w.sum() }
        }
        fun seatAverage(keys: List<Pair<Channel, Seat>>) = weighted(keys) { perSeat[it] } ?: DoubleArray(n)
        val channels = Channel.entries.filter { c -> Seat.entries.any { (c to it) in perSeat } }
        for (seat in Seat.entries) if (perSeat.keys.none { it.second == seat }) warnings += "No ${seat.label.lowercase()} seat measurements: the EQ is fitted to the other seat only"

        class Side(val ch: Channel, val power: DoubleArray, val inFit: BooleanArray, val measured: DoubleArray, val target: DoubleArray, val wanted: DoubleArray, val shares: Map<Band, DoubleArray>)
        val sides = channels.map { ch ->
            val keys = Seat.entries.map { ch to it }
            val power = seatAverage(keys)
            // Only what was swept: a band-limited sweep reads as silence outside its range.
            val covered = BooleanArray(n) { power[it] > 0 }
            val inFit = BooleanArray(n) { f[it] in o.minHz..o.maxHz && covered[it] }
            require(inFit.any { it }) { "No measured data in the fit range ${o.minHz.toInt()}–${o.maxHz.toInt()} Hz" }
            val measured = toDb(powerSmooth(power, f, 1.0 / 6))
            val wide = toDb(powerSmooth(power, f, 1.0))
            // Level the house curve to the response: the median of their difference over the
            // fit range (the grid is log-spaced, so each octave counts alike). Only the curve's
            // shape matters, and a median isn't moved by one side's own peak, so that peak isn't
            // read as the side being louder (each side keeps its own level: balance isn't EQ).
            val offset = f.indices.filter { inFit[it] }.map { measured[it] - houseCurve(f[it], o) }.sorted().let { d -> if (d.size % 2 == 1) d[d.size / 2] else (d[d.size / 2 - 1] + d[d.size / 2]) / 2 }
            val target = DoubleArray(n) { offset + houseCurve(f[it], o) }
            val wanted = DoubleArray(n) { i ->
                if (!inFit[i]) 0.0 else {
                    val d = target[i] - measured[i]
                    if (d > 0) minOf(d, o.maxBoostDb, max(0.0, target[i] - wide[i])) else max(d, -o.maxCutDb)
                }
            }
            val bandPower = Band.BRANCHES.associateWith { b -> weighted(keys) { k -> bandPerSeat[k]?.get(b) } }
            val shares = if (bandPower.values.all { it != null }) {
                val sm = bandPower.mapValues { (_, p) -> powerSmooth(p!!, f, 1.0 / 6) }
                Band.BRANCHES.associateWith { b -> DoubleArray(n) { i -> val t = Band.BRANCHES.sumOf { sm.getValue(it)[i] }; if (t > 0) sm.getValue(b)[i] / t else 0.0 } }
            } else crossoverShares(f, o)
            Side(ch, power, inFit, measured, target, wanted, shares)
        }
        val unsplit = sides.filter { s -> bandPerSeat.keys.none { it.first == s.ch } }
        if (o.bandEq && unsplit.isNotEmpty())
            warnings += "No complete band solos for the ${unsplit.joinToString(" or ") { it.ch.label.lowercase() }} side: its bands are split at the Low/Mid and Mid/High frequencies set here (${o.lowMidHz.roundToInt()} and ${o.midHighHz.roundToInt()} Hz)"

        // Pre EQ: only what both channels want alike, the smaller of the two.
        val both = sides.size == 2
        val shared = DoubleArray(n)
        val sharedFit = BooleanArray(n)
        if (both) for (i in 0 until n) {
            val a = sides[0]; val b = sides[1]
            if (!a.inFit[i] || !b.inFit[i]) continue
            sharedFit[i] = true
            val wa = a.wanted[i]; val wb = b.wanted[i]
            if (wa * wb > 0) shared[i] = if (wa > 0) min(wa, wb) else max(wa, wb)
        }
        val pre = when {
            !o.preEq -> emptyList()
            !both -> { warnings += "Only one side was measured: Pre EQ takes only what both sides need alike, so it's left empty${if (o.bandEq) " and the band EQs do it all" else ""}"; emptyList() }
            !sharedFit.any { it } -> emptyList()
            else -> fit(f, shared, sharedFit, o, EqSide.BOTH, fs, maxBands = o.preBands).map { it.copy(bank = EqBank.PRE) }
        }
        val preDb = eqDb(pre, f, fs)

        // Band EQs: the rest, per side (or for both), each band where it carries the sound.
        fun ownedBy(s: Side, b: Band) = BooleanArray(n) { s.inFit[it] && s.shares.getValue(b)[it] >= OWN_SHARE }
        val bandBands = ArrayList<EqBand>()
        if (o.bandEq) {
            val groups: List<Pair<EqSide, List<Side>>> =
                if (o.linked) listOf(EqSide.BOTH to sides)
                else sides.map { (if (it.ch == Channel.L) EqSide.LEFT else EqSide.RIGHT) to listOf(it) }
            for ((side, ss) in groups) for (bank in EqBank.BANDS) {
                val b = bank.band!!
                val owned = ss.map { ownedBy(it, b) }
                val inFit = BooleanArray(n) { i -> owned.all { it[i] } }
                if (inFit.count { it } < 3) continue
                val residual = DoubleArray(n) { i -> if (!inFit[i]) 0.0 else ss.sumOf { it.wanted[i] - preDb[i] } / ss.size }
                val idx = f.indices.filter { inFit[it] }
                bandBands += fit(f, residual, inFit, o, side, fs, hzRange = f[idx.first()]..f[idx.last()]).map { it.copy(bank = bank) }
            }
        }

        val results = sides.map { s ->
            val side = if (s.ch == Channel.L) EqSide.LEFT else EqSide.RIGHT
            val mine = bandBands.filter { it.side == side || it.side == EqSide.BOTH }
            val gain = totalGain(f, fs, pre, mine, s.shares)
            val eq = DoubleArray(n) { 10 * log10(gain[it]) }
            fun rms(after: Boolean): Double {
                val idx = f.indices.filter { s.inFit[it] }
                return sqrt(idx.sumOf { i -> (s.measured[i] + (if (after) eq[i] else 0.0) - s.target[i]).pow(2) } / idx.size)
            }
            val seats = Seat.entries.filter { (s.ch to it) in perSeat }
            val before = seats.associateWith { st -> toDb(powerSmooth(perSeat.getValue(s.ch to st), f, 1.0 / 6)) }
            EqSideResult(
                side, s.measured, s.target, s.wanted, eq,
                seatsAfterDb = seats.associateWith { st -> before.getValue(st).let { m -> DoubleArray(n) { m[it] + eq[it] } } },
                seatsBeforeDb = before,
                bands = pre + mine.sortedWith(compareBy({ it.bank.ordinal }, { it.hz })),
                rmsBeforeDb = rms(false).round(2), rmsAfterDb = rms(true).round(2),
                preDb = preDb,
            )
        }
        // Headroom: the largest boost anywhere in each bank, taken off its preamp (J_DSP allows −30..+12 dB).
        val wideGrid = geomspace(20.0, min(20000.0, fs * 0.45), 400)
        fun preampFor(bs: List<EqBand>): Double {
            if (bs.isEmpty()) return 0.0
            val peak = listOf(EqSide.LEFT, EqSide.RIGHT).maxOf { sd -> eqDb(bs.filter { it.side == sd || it.side == EqSide.BOTH }, wideGrid, fs).max() }
            return if (peak > 0) -(Math.ceil(peak * 2) / 2).coerceAtMost(30.0) else 0.0
        }
        val bankPreamp = EqBank.BANDS.associateWith { k -> preampFor(bandBands.filter { it.bank == k }) }
        return EqResult(f, o, results, preampFor(pre), warnings.distinct(), bankPreamp, if (both) shared else DoubleArray(0))
    }

    /**
     * Each band's share of the power when only the sum was swept: Linkwitz-Riley 4th-order
     * crossovers at [EqOptions.lowMidHz] and [EqOptions.midHighHz] (J_DSP's usual), by magnitude.
     */
    internal fun crossoverShares(f: DoubleArray, o: EqOptions): Map<Band, DoubleArray> {
        fun lp(hz: Double, fc: Double) = (1 / (1 + (hz / fc).pow(4))).pow(2)
        fun hp(hz: Double, fc: Double) = (1 / (1 + (fc / hz).pow(4))).pow(2)
        val p = Band.BRANCHES.associateWith { b ->
            DoubleArray(f.size) { i ->
                when (b) {
                    Band.LOW -> lp(f[i], o.lowMidHz)
                    Band.MID -> hp(f[i], o.lowMidHz) * lp(f[i], o.midHighHz)
                    else -> hp(f[i], o.midHighHz)
                }
            }
        }
        return p.mapValues { (_, v) -> DoubleArray(f.size) { i -> v[i] / Band.BRANCHES.sumOf { p.getValue(it)[i] } } }
    }

    /**
     * What the EQ does to a side's sum, as a power gain: Pre EQ on everything, and each band EQ
     * on its band's share of the power.
     */
    internal fun totalGain(f: DoubleArray, fs: Double, pre: List<EqBand>, bands: List<EqBand>, shares: Map<Band, DoubleArray>): DoubleArray {
        val preDb = eqDb(pre, f, fs)
        val per = EqBank.BANDS.associateWith { k -> eqDb(bands.filter { it.bank == k }, f, fs) }
        return DoubleArray(f.size) { i ->
            val mix = EqBank.BANDS.sumOf { k -> shares.getValue(k.band!!)[i] * 10.0.pow(per.getValue(k)[i] / 10) }
            10.0.pow(preDb[i] / 10) * max(mix, 1e-12)
        }
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

    internal fun fit(
        f: DoubleArray, wanted: DoubleArray, inFit: BooleanArray, o: EqOptions, side: EqSide, fs: Double,
        maxBands: Int = o.maxBands, hzRange: ClosedFloatingPointRange<Double> = o.minHz..o.maxHz,
    ): List<EqBand> {
        val idx = f.indices.filter { inFit[it] }
        val bands = ArrayList<EqBand>()
        fun clampBand(b: EqBand): EqBand {
            val g = b.gainDb.coerceIn(-o.maxCutDb, o.maxBoostDb)
            val qMax = if (g > 0) min(3.0, o.maxQ) else o.maxQ
            return b.copy(hz = b.hz.coerceIn(max(o.minHz, hzRange.start), min(o.maxHz, hzRange.endInclusive)), gainDb = g, q = b.q.coerceIn(0.3, qMax))
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
        repeat(maxBands) {
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
     * Equalizer APO text for one bank, the way J_DSP's Parametric EQ imports it ("Preamp", then
     * "Channel:" sections of "Filter n: ON PK Fc … Hz Gain … dB Q …"). The file name says which
     * bank it's for: "input" routes it to Pre EQ.
     */
    fun apoText(r: EqResult, bank: EqBank = EqBank.PRE): String = apoText(r.bank(bank), r.preamp(bank))

    fun apoText(bands: List<EqBand>, preampDb: Double): String {
        val sb = StringBuilder()
        sb.append("Preamp: ").append(fmt(preampDb)).append(" dB\n")
        var k = 1
        for (side in EqSide.entries) {
            val bs = bands.filter { it.side == side }
            if (bs.isEmpty()) continue
            sb.append("Channel: ").append(side.apo).append('\n')
            for (b in bs) sb.append("Filter ${k++}: ON PK Fc ${fmt(b.hz)} Hz Gain ${fmt(b.gainDb)} dB Q ${fmt(b.q)}\n")
        }
        return sb.toString()
    }

    private fun fmt(v: Double) = java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    val EXPORT_FILE = EqBank.PRE.fileName
}
