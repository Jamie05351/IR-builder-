package jdsp.irbuilder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Frequency-dependent windowing (FDW) of an impulse response, after Resonalyze's FDW bank
 * (github.com/DIMOSUS/Resonalyze, MIT, docs/tech/phase-and-group-delay.md).
 *
 * At each frequency the response is read through a window [cycles] periods long that starts at
 * the direct sound: long enough at low frequencies to hold the bass, short enough at mid and high
 * frequencies to leave the cabin's reflections out, so the phase is the direct sound's. Windows
 * are evaluated at three centres per octave and blended by complex-linear interpolation over log
 * frequency. Every spectrum keeps the impulse's absolute time base (sample [timeZero] is t = 0),
 * so relative timing between files survives.
 */
object Fdw {
    /** Shortest window after the onset, seconds (8 cycles reach it at 10 kHz). */
    const val FLOOR_S = 0.0008
    /** Longest window, seconds. */
    const val MAX_GATE_S = 0.5
    /** Lead-in before the onset, rising over a half-Hann, seconds. */
    const val PRE_S = 0.001
    private const val CENTRES_PER_OCTAVE = 3.0

    /**
     * The direct sound's onset: the first sample, at most 10 ms before the peak, whose magnitude
     * reaches 20 dB below the peak. A bass-only response with a slow rise starts earlier than its
     * peak; a full-range one starts within a fraction of a millisecond of it.
     */
    fun onset(x: DoubleArray, sampleRate: Int): Int {
        var peak = 0
        for (i in x.indices) if (abs(x[i]) > abs(x[peak])) peak = i
        val threshold = abs(x[peak]) * 0.1
        val from = max(0, peak - (0.010 * sampleRate).toInt())
        for (i in from..peak) if (abs(x[i]) >= threshold) return i
        return peak
    }

    /**
     * [x]'s spectrum on [freqs] through windows of [cycles] periods (0 = one window as long as
     * the whole impulse after the lead-in). Phase is referenced to sample [timeZero].
     */
    fun spectrum(x: DoubleArray, sampleRate: Int, timeZero: Double, onset: Int, cycles: Double, freqs: DoubleArray): CVec {
        val fs = sampleRate.toDouble()
        // Off: the whole impulse as it is, no lead-in cut and no taper.
        if (cycles <= 0) return windowed(x, fs, timeZero, 0, 0, onset, x.size - onset, freqs, taper = false)
        val pre = min((PRE_S * fs).toInt(), onset)
        val start = onset - pre
        val available = (x.size - onset) / fs
        // A very short impulse may not even hold the floor: then the window is all there is.
        fun windowSeconds(f: Double) = (cycles / f).coerceIn(min(FLOOR_S, available), min(MAX_GATE_S, available))

        // Bank centres from the bottom to the top of the grid, three per octave; neighbours whose
        // windows come out the same length share one evaluation.
        val lo = freqs.first()
        val hi = freqs.last()
        val count = max(2, (Math.log(hi / lo) / ln(2.0) * CENTRES_PER_OCTAVE).toInt() + 2)
        val centres = DoubleArray(count) { lo * Math.pow(hi / lo, it / (count - 1.0)) }
        val cache = HashMap<Int, CVec>()
        val out = CVec(freqs.size)
        // Each centre's spectrum is needed only at the grid points it blends into.
        val spectra = arrayOfNulls<CVec>(count)
        fun spectrumFor(k: Int): CVec {
            spectra[k]?.let { return it }
            val lengthSamples = (windowSeconds(centres[k]) * fs).toInt().coerceAtLeast(8)
            val s = cache.getOrPut(lengthSamples) { windowed(x, fs, timeZero, start, pre, onset, lengthSamples, freqs) }
            spectra[k] = s
            return s
        }
        for (i in freqs.indices) {
            val f = freqs[i]
            var k = 0
            while (k < count - 2 && centres[k + 1] <= f) k++
            val a = (ln(f / centres[k]) / ln(centres[k + 1] / centres[k])).coerceIn(0.0, 1.0)
            val s0 = spectrumFor(k)
            val s1 = spectrumFor(k + 1)
            out.re[i] = (1 - a) * s0.re[i] + a * s1.re[i]
            out.im[i] = (1 - a) * s0.im[i] + a * s1.im[i]
        }
        return out
    }

    /** One window's spectrum on [freqs]: lead-in, flat to half its length, half-Hann down to its end. */
    private fun windowed(x: DoubleArray, fs: Double, timeZero: Double, start: Int, pre: Int, onset: Int, length: Int, freqs: DoubleArray, taper: Boolean = true): CVec {
        val end = min(x.size, onset + length).coerceAtLeast(start + 1)
        val seg = DoubleArray(end - start)
        val flat = length / 2
        for (n in seg.indices) {
            val i = start + n
            val w = when {
                !taper -> 1.0
                i < onset -> if (pre > 0) 0.5 * (1 - cos(PI * (n + 0.5) / pre)) else 1.0
                i - onset < flat -> 1.0
                else -> {
                    val t = (i - onset - flat).toDouble() / max(1, length - flat)
                    0.5 * (1 + cos(PI * t.coerceIn(0.0, 1.0)))
                }
            }
            seg[n] = x[i] * w
        }
        // Zero-padded FFT, derotated to the onset before interpolating bins (a rapidly rotating
        // phase would otherwise interpolate into false attenuation), then placed on t = 0.
        val nfft = nextPowerOfTwo(max(1 shl 14, seg.size * 4))
        val spec = Fft.rfft(seg, nfft)
        val bins = Fft.rfftFreqs(nfft, fs)
        val toOnset = (onset - start) / fs
        val flatPhase = spec.delayed(bins, -toOnset)
        val re = interp(freqs, bins, flatPhase.re)
        val im = interp(freqs, bins, flatPhase.im)
        return CVec(re, im).delayed(freqs, (onset - timeZero) / fs)
    }
}
