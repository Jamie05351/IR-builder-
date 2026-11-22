package jdsp.irbuilder

import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sum loss, after Resonalyze (github.com/DIMOSUS/Resonalyze, MIT,
 * docs/tech/virtual-dsp-analysis.md): how far the bands' real complex sum falls short of the
 * same bands added perfectly in phase, 20·log10(|ΣH| / Σ|H|), never above 0 dB. It measures
 * cancellation only, the dips EQ can't fill.
 *
 * The ratio is taken per point first and only then smoothed (a power mean over ±1/24 octave):
 * smoothing the bands first lifts a steep slope toward its passband while the sum barely moves,
 * drawing a false dip at every corner. Points are left out where the in-phase sum is more than
 * [LEVEL_GATE_DB] below its loudest within an octave (two noise floors' phase arithmetic), or
 * where every band is more than [PRESENCE_DB] below its own peak.
 */
object SumLoss {
    const val LEVEL_GATE_DB = 25.0
    const val PRESENCE_DB = 40.0
    const val SMOOTHING_OCTAVES = 1.0 / 24

    /** Average and worst (smoothed) loss over a region, dB; both ≤ 0. */
    data class Reading(val averageDb: Double, val worstDb: Double)

    /** Points of [freqs] that carry a meaningful loss for [bands]. */
    fun valid(bands: List<CVec>, freqs: DoubleArray): BooleanArray {
        val n = freqs.size
        val amp = DoubleArray(n) { i -> bands.sumOf { it.abs(i) } }
        val peaks = bands.map { b -> (0 until n).maxOfOrNull { b.abs(it) } ?: 0.0 }
        val ppo = (n - 1) / log2(freqs.last() / freqs.first())
        val octave = ppo.roundToInt().coerceAtLeast(1)
        val level = Math.pow(10.0, -LEVEL_GATE_DB / 20)
        val presence = Math.pow(10.0, -PRESENCE_DB / 20)
        return BooleanArray(n) { i ->
            var local = 0.0
            for (j in (i - octave).coerceAtLeast(0)..(i + octave).coerceAtMost(n - 1)) local = max(local, amp[j])
            amp[i] > 0 && amp[i] >= local * level && bands.indices.any { b -> peaks[b] > 0 && bands[b].abs(i) >= peaks[b] * presence }
        }
    }

    /** Per-point |ΣH|² / (Σ|H|)², before smoothing. */
    fun ratio(bands: List<CVec>, n: Int): DoubleArray = DoubleArray(n) { i ->
        var re = 0.0; var im = 0.0; var amp = 0.0
        for (b in bands) { re += b.re[i]; im += b.im[i]; amp += b.abs(i) }
        if (amp <= 0) 1.0 else (re * re + im * im) / (amp * amp)
    }

    /** The smoothed loss curve, dB (NaN where not [valid]). */
    fun curveDb(bands: List<CVec>, freqs: DoubleArray, valid: BooleanArray = valid(bands, freqs)): DoubleArray {
        val r = ratio(bands, freqs.size)
        val sm = smooth(r, valid, halfWidth(freqs))
        return DoubleArray(freqs.size) { if (valid[it]) 10 * log10(max(sm[it], 1e-12)) else Double.NaN }
    }

    /** Average and worst loss over [region]. */
    fun read(bands: List<CVec>, freqs: DoubleArray, region: ClosedFloatingPointRange<Double>): Reading? =
        readCurve(curveDb(bands, freqs), freqs, region)

    fun readCurve(curve: DoubleArray, freqs: DoubleArray, region: ClosedFloatingPointRange<Double>): Reading? {
        val pts = freqs.indices.filter { freqs[it] in region && !curve[it].isNaN() }
        if (pts.isEmpty()) return null
        return Reading(pts.sumOf { curve[it] } / pts.size, pts.minOf { curve[it] })
    }

    internal fun halfWidth(freqs: DoubleArray) =
        ((freqs.size - 1) / log2(freqs.last() / freqs.first()) * SMOOTHING_OCTAVES).roundToInt().coerceAtLeast(1)

    /** Mean of [x] over ±[half] points, counting only [valid] ones. */
    internal fun smooth(x: DoubleArray, valid: BooleanArray, half: Int): DoubleArray {
        val n = x.size
        val sum = DoubleArray(n + 1)
        val cnt = IntArray(n + 1)
        for (i in 0 until n) {
            sum[i + 1] = sum[i] + if (valid[i]) x[i] else 0.0
            cnt[i + 1] = cnt[i] + if (valid[i]) 1 else 0
        }
        return DoubleArray(n) { i ->
            val a = (i - half).coerceAtLeast(0)
            val b = (i + half).coerceAtMost(n - 1)
            val c = cnt[b + 1] - cnt[a]
            if (c == 0) x[i] else (sum[b + 1] - sum[a]) / c
        }
    }
}
