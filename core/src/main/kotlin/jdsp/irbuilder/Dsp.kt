package jdsp.irbuilder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin

/** A complex spectrum stored as parallel real/imaginary arrays. */
class CVec(val re: DoubleArray, val im: DoubleArray) {
    constructor(n: Int) : this(DoubleArray(n), DoubleArray(n))

    init { require(re.size == im.size) }

    val size get() = re.size
    fun abs(i: Int) = hypot(re[i], im[i])
    fun arg(i: Int) = atan2(im[i], re[i])
    fun copy() = CVec(re.copyOf(), im.copyOf())

    /** Bin-by-bin product. */
    fun times(o: CVec): CVec {
        val out = CVec(size)
        for (i in 0 until size) {
            out.re[i] = re[i] * o.re[i] - im[i] * o.im[i]
            out.im[i] = re[i] * o.im[i] + im[i] * o.re[i]
        }
        return out
    }

    /** Bin-by-bin quotient (a zero divisor bin gives zero). */
    fun div(o: CVec): CVec {
        val out = CVec(size)
        for (i in 0 until size) {
            val d = o.re[i] * o.re[i] + o.im[i] * o.im[i]
            if (d <= 0.0) continue
            out.re[i] = (re[i] * o.re[i] + im[i] * o.im[i]) / d
            out.im[i] = (im[i] * o.re[i] - re[i] * o.im[i]) / d
        }
        return out
    }

    /** Multiplies every bin by exp(-j·2π·f·delay). */
    fun delayed(freqs: DoubleArray, delaySeconds: Double): CVec {
        val out = CVec(size)
        for (i in 0 until size) {
            val a = -2 * PI * freqs[i] * delaySeconds
            val c = cos(a)
            val s = sin(a)
            out.re[i] = re[i] * c - im[i] * s
            out.im[i] = re[i] * s + im[i] * c
        }
        return out
    }
}

internal fun db(x: Double) = 20 * log10(max(x, 1e-12))

internal fun geomspace(start: Double, stop: Double, n: Int): DoubleArray {
    val a = ln(start)
    val b = ln(stop)
    return DoubleArray(n) { exp(a + (b - a) * it / (n - 1)) }
}

internal fun isPowerOfTwo(n: Int) = n > 0 && n and (n - 1) == 0

internal fun nextPowerOfTwo(n: Int): Int {
    var p = 1
    while (p < n) p = p shl 1
    return p
}

/** Iterative radix-2 complex FFT, double precision, with a precomputed twiddle table. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(im.size == n && isPowerOfTwo(n)) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val half = n / 2
        val cosT = DoubleArray(max(half, 1)) { cos(2 * PI * it / n) }
        val sinT = DoubleArray(max(half, 1)) { (if (inverse) 1.0 else -1.0) * sin(2 * PI * it / n) }
        var len = 2
        while (len <= n) {
            val h = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until h) {
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val a = i + k
                    val b = a + h
                    val vr = re[b] * wr - im[b] * wi
                    val vi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Real FFT of [x] zero-padded (or truncated) to [n]; returns n/2+1 bins. */
    fun rfft(x: DoubleArray, n: Int): CVec {
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        System.arraycopy(x, 0, re, 0, minOf(x.size, n))
        transform(re, im, inverse = false)
        return CVec(re.copyOf(n / 2 + 1), im.copyOf(n / 2 + 1))
    }

    /** Inverse of [rfft]: Hermitian-extends the n/2+1 bins and returns n real samples. */
    fun irfft(spectrum: CVec, n: Int): DoubleArray {
        require(spectrum.size == n / 2 + 1)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (k in 0..n / 2) {
            re[k] = spectrum.re[k]
            im[k] = spectrum.im[k]
        }
        im[0] = 0.0
        im[n / 2] = 0.0
        for (k in 1 until n / 2) {
            re[n - k] = spectrum.re[k]
            im[n - k] = -spectrum.im[k]
        }
        transform(re, im, inverse = true)
        return DoubleArray(n) { re[it] / n }
    }

    fun rfftFreqs(n: Int, sampleRate: Double) = DoubleArray(n / 2 + 1) { it * sampleRate / n }
}

/** Linear interpolation like numpy.interp: [xp] increasing, constant extrapolation unless overridden. */
internal fun interp(x: DoubleArray, xp: DoubleArray, fp: DoubleArray, left: Double = fp.first(), right: Double = fp.last()): DoubleArray {
    val out = DoubleArray(x.size)
    var j = 0
    for (i in x.indices) {
        val v = x[i]
        if (v < xp[0]) { out[i] = left; continue }
        if (v > xp[xp.size - 1]) { out[i] = right; continue }
        if (j > 0 && v < xp[j]) j = 0
        while (j < xp.size - 2 && xp[j + 1] < v) j++
        val x0 = xp[j]
        val x1 = xp[minOf(j + 1, xp.size - 1)]
        out[i] = if (x1 == x0) fp[j] else fp[j] + (fp[minOf(j + 1, fp.size - 1)] - fp[j]) * (v - x0) / (x1 - x0)
    }
    return out
}

/** numpy.unwrap for radians. */
internal fun unwrap(p: DoubleArray): DoubleArray {
    val out = p.copyOf()
    var correction = 0.0
    for (i in 1 until p.size) {
        val d = p[i] - p[i - 1]
        var m = ((d + PI) % (2 * PI) + 2 * PI) % (2 * PI) - PI
        if (m == -PI && d > 0) m = PI
        if (abs(d) >= PI) correction += m - d
        out[i] = p[i] + correction
    }
    return out
}

/** scipy.ndimage.gaussian_filter1d, mode "reflect", truncate 4.0. */
internal fun gaussianSmooth(x: DoubleArray, sigma: Double): DoubleArray {
    if (sigma <= 0.0 || x.size < 2) return x.copyOf()
    val radius = (4.0 * sigma + 0.5).toInt()
    val w = DoubleArray(2 * radius + 1) { exp(-0.5 * ((it - radius) * (it - radius)) / (sigma * sigma)) }
    val sum = w.sum()
    for (i in w.indices) w[i] /= sum
    val n = x.size
    fun at(idx: Int): Double {
        var i = idx
        while (i < 0 || i >= n) {
            i = if (i < 0) -i - 1 else 2 * n - i - 1
        }
        return x[i]
    }
    return DoubleArray(n) { i ->
        var acc = 0.0
        for (k in w.indices) acc += w[k] * at(i + k - radius)
        acc
    }
}

/** scipy.signal.windows.tukey(m, alpha), symmetric. */
internal fun tukey(m: Int, alpha: Double): DoubleArray {
    if (alpha <= 0) return DoubleArray(m) { 1.0 }
    val w = DoubleArray(m) { 1.0 }
    val width = (alpha * (m - 1) / 2.0).toLong().toInt()
    for (n in 0..width) w[n] = 0.5 * (1 + cos(PI * (-1 + 2.0 * n / alpha / (m - 1))))
    for (n in m - width - 1 until m) w[n] = 0.5 * (1 + cos(PI * (-2.0 / alpha + 1 + 2.0 * n / alpha / (m - 1))))
    return w
}

/** Wraps an angle to (-π, π]. */
internal fun wrap(a: Double): Double {
    var x = (a + PI) % (2 * PI)
    if (x < 0) x += 2 * PI
    return x - PI
}

internal fun Double.round(digits: Int): Double {
    val f = Math.pow(10.0, digits.toDouble())
    return (this * f).roundToLong() / f
}
