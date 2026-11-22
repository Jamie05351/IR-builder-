package jdsp.irbuilder

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** A decoded mono WAV. */
class WavData(val sampleRate: Int, val samples: DoubleArray)

/** What kind of file a measurement was read from; affects some quality checks. */
enum class SourceKind { REW_TEXT, IMPULSE_WAV }

/** A measurement resampled onto the design grid, with the time origin of the sweep kept. */
class LoadedResponse(
    val response: CVec,
    val kind: SourceKind,
    /** Peak sample of an impulse WAV (the time-origin sanity check); null for REW text. */
    val impulsePeakIndex: Int? = null,
    /** Lowest and highest frequency the file itself covers. */
    val coverageHz: ClosedFloatingPointRange<Double>,
    /** REW's smoothing note ("1/48 octave", "Psychoacoustic", ...), if the header had one. */
    val smoothing: String? = null,
    /** The delay REW took out of the phase and this reader put back, ms (REW text only). */
    val restoredDelayMs: Double = 0.0,
) {
    /**
     * Smoothed too coarsely to sum phase: coarser than 1/12 octave, or one of REW's variable
     * smoothings (Psychoacoustic, ERB, Var). Same rule as J_DSP's Band Align.
     */
    val heavilySmoothed: Boolean
        get() {
            val s = smoothing ?: return false
            val m = Regex("""1/(\d+)""").find(s)
                ?: return !s.trim().lowercase().let { it == "none" || it.startsWith("no ") }
            return (m.groupValues[1].toIntOrNull() ?: 48) < 12
        }
}

object Importer {
    const val MAX_FILE_BYTES = 128L * 1024 * 1024

    /**
     * Reads a REW frequency/SPL/phase export or a calibrated mono impulse WAV onto [freqs], then
     * applies 1/[smoothing]-octave complex smoothing (0 = none). [freqs] must be log-spaced.
     */
    fun read(m: Measurement, freqs: DoubleArray, sampleRate: Int, smoothing: Int = 0): LoadedResponse {
        val r = readRaw(m, freqs, sampleRate)
        if (smoothing <= 0) return r
        // Start from the delay the file itself states: REW's header note (restored = exported
        // delayed by it) or an impulse's peak. Only the small remainder is estimated.
        val hint = when (r.kind) {
            SourceKind.REW_TEXT -> r.restoredDelayMs / 1000.0
            SourceKind.IMPULSE_WAV -> (r.impulsePeakIndex ?: 0).toDouble() / sampleRate
        }
        return LoadedResponse(complexSmooth(r.response, freqs, smoothing, hint), r.kind, r.impulsePeakIndex, r.coverageHz, r.smoothing, r.restoredDelayMs)
    }

    /**
     * 1/[n]-octave complex (vector) smoothing, the kind band sums need: magnitude and phase are
     * averaged together. The response's bulk delay is taken out first and put back after, so the
     * phase rotation of the arrival time doesn't cancel itself out at high frequencies. Gaussian
     * window with a full width at half maximum of 1/n octave, reflected at the grid ends.
     */
    fun complexSmooth(h: CVec, freqs: DoubleArray, n: Int, delayHintSeconds: Double = 0.0): CVec {
        val delay = delayHintSeconds + bulkDelaySeconds(h.delayed(freqs, -delayHintSeconds), freqs)
        val flat = h.delayed(freqs, -delay)
        val ppo = (freqs.size - 1) / kotlin.math.log2(freqs.last() / freqs.first())
        val sigma = ppo / n / 2.3548
        val sm = CVec(gaussianSmooth(flat.re, sigma), gaussianSmooth(flat.im, sigma))
        return sm.delayed(freqs, delay)
    }

    /**
     * The response's arrival time: the energy-weighted median of the local group delay between
     * neighbouring grid points. Robust to the dips and reflections a car measurement has.
     */
    internal fun bulkDelaySeconds(h: CVec, freqs: DoubleArray): Double {
        val taus = ArrayList<Pair<Double, Double>>(freqs.size)
        for (i in 0 until freqs.size - 1) {
            // arg(H[i+1]·conj(H[i])) is the phase step between neighbours, free of wrapping.
            val re = h.re[i + 1] * h.re[i] + h.im[i + 1] * h.im[i]
            val im = h.im[i + 1] * h.re[i] - h.re[i + 1] * h.im[i]
            val w = h.abs(i) * h.abs(i + 1)
            if (w <= 0.0) continue
            taus += -kotlin.math.atan2(im, re) / (2 * PI * (freqs[i + 1] - freqs[i])) to w
        }
        if (taus.isEmpty()) return 0.0
        taus.sortBy { it.first }
        val half = taus.sumOf { it.second } / 2
        var acc = 0.0
        for ((tau, w) in taus) {
            acc += w
            if (acc >= half) return tau
        }
        return taus.last().first
    }

    private fun readRaw(m: Measurement, freqs: DoubleArray, sampleRate: Int): LoadedResponse {
        require(m.position.isNotBlank()) { "Position is empty" }
        require(m.timingReference.isNotBlank()) { "Timing-reference ID is empty" }
        require(m.calibrationDb.isFinite() && abs(m.calibrationDb) <= 300) { "Calibration offset must be finite and within ±300 dB" }
        val file = File(m.path)
        require(file.isFile) { "File not found: ${m.path}" }
        require(file.length() <= MAX_FILE_BYTES) { "${file.name}: file exceeds 128 MiB" }
        return when (file.extension.lowercase()) {
            "wav" -> readImpulse(file, m, freqs, sampleRate)
            "txt", "csv", "frd" -> readRewText(file, m, freqs)
            "mdat" -> throw IllegalArgumentException("${file.name}: export the measurement from REW as text (File › Export › Measurement as text); .mdat is not read directly")
            else -> throw IllegalArgumentException("${file.name}: use REW frequency/SPL/phase text (.txt/.csv/.frd) or a mono impulse .wav")
        }
    }

    // REW text conventions, ported from J_DSP's Band Align reader (dsp/bandalign/RewMeasurement.kt)
    // so both apps read an export the same way.
    private val delayNote = Regex("""Delay\s+(-?[0-9]+(?:[.,][0-9]+)?)\s*ms""")
    private val rewBanner = Regex("""Measurement data measured by REW""", RegexOption.IGNORE_CASE)
    private val smoothingNote = Regex("""^\*\s*Smoothing:\s*(.+)$""")
    private val spaceOrSemicolon = Regex("""[\s;]+""")

    /**
     * A data row's frequency, SPL and phase, or null for anything else. Takes tab/space, CSV,
     * semicolon-separated, and a comma as the decimal point: "20.0 85.3 120.1",
     * "20.0,85.3,120.1", "20,0;85,3;120,1" and "20,0 85,3 120,1" all read the same.
     */
    internal fun rowNumbers(line: String): DoubleArray? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val spaced = t.split(spaceOrSemicolon).map { it.trimEnd(',') }.filter { it.isNotEmpty() }
        val parts = if (spaced.size >= 3) spaced.map { it.replace(',', '.') } else t.split(',').map { it.trim() }
        if (parts.size < 3) return null
        val values = parts.take(3).map { it.toDoubleOrNull() }
        return if (values.any { it == null }) null else DoubleArray(3) { values[it]!! }
    }

    /** UTF-8 unless a byte-order mark or UTF-16's zero bytes say otherwise; BOM dropped. */
    internal fun decode(bytes: ByteArray): String {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        val text = when {
            at(0) == 0xFF && at(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            at(0) == 0xFE && at(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> {
                val sample = bytes.size.coerceAtMost(512)
                val oddZeros = (1 until sample step 2).count { bytes[it].toInt() == 0 }
                val evenZeros = (0 until sample step 2).count { bytes[it].toInt() == 0 }
                when {
                    sample >= 4 && oddZeros > sample / 4 -> String(bytes, Charsets.UTF_16LE)
                    sample >= 4 && evenZeros > sample / 4 -> String(bytes, Charsets.UTF_16BE)
                    else -> String(bytes, Charsets.UTF_8)
                }
            }
        }
        return text.removePrefix("\uFEFF")
    }

    private fun readRewText(file: File, m: Measurement, freqs: DoubleArray): LoadedResponse {
        val f = ArrayList<Double>()
        val spl = ArrayList<Double>()
        val deg = ArrayList<Double>()
        var delayMs = 0.0
        var smoothing: String? = null
        var sawHeader = false
        var sawDelay = false
        var fromRew = false
        var phaseMissing = false
        for (line in decode(file.readBytes()).lineSequence()) {
            if (line.startsWith("*")) {
                sawHeader = true
                if (rewBanner.containsMatchIn(line)) fromRew = true
                delayNote.find(line)?.let { d ->
                    sawDelay = true
                    delayMs = d.groupValues[1].replace(',', '.').toDoubleOrNull()
                        ?: throw IllegalArgumentException("${file.name}: couldn't read the delay in its header note")
                }
                smoothingNote.find(line.trim())?.let { smoothing = it.groupValues[1].trim() }
                continue
            }
            val row = rowNumbers(line)
            if (row == null) {
                // Two numbers and nothing else: an export without the phase column.
                val two = line.trim().split(spaceOrSemicolon).filter { it.isNotEmpty() }
                if (two.size == 2 && two.all { it.replace(',', '.').toDoubleOrNull() != null }) phaseMissing = true
                continue
            }
            val (freq, db, ph) = Triple(row[0], row[1], row[2])
            // 0 Hz (linear-spaced exports) has no log position; repeated rows are skipped.
            if (!freq.isFinite() || !db.isFinite() || !ph.isFinite() || freq <= 0.0) continue
            if (f.isNotEmpty() && freq <= f.last()) continue
            f += freq; spl += db; deg += ph
        }
        require(!(phaseMissing && f.isEmpty())) { "${file.name}: a phase column is required; the band sum depends on relative phase" }
        require(f.size >= 16) { "${file.name}: no frequency / SPL / phase rows. Export from REW with File › Export › Measurement as text" }
        // REW takes the measurement's delay out of the phase and records it only in the header.
        require(sawHeader) {
            "${file.name}: has no REW header, which holds the delay REW took out of the phase. Re-export with \"Do not include any comments or headers\" unticked."
        }
        // REW writes the note only when it took a delay out, so its own header without one means
        // 0 ms. Any other header without one can't say what was taken out: refused, not guessed.
        require(sawDelay || fromRew) {
            "${file.name}: its header has no \"Delay ... ms\" note, so the delay REW took out of the phase is unknown. Export it from REW with File › Export › Measurement as text."
        }
        require(f.first() <= freqs.first() * 1.0001 && f.last() >= freqs.last() * 0.9999) {
            "${file.name}: covers %.1f–%.0f Hz but the design needs %.1f–%.0f Hz".format(f.first(), f.last(), freqs.first(), freqs.last())
        }
        require(spl.max() > -180) { "${file.name}: response appears silent" }
        require(spl.all { abs(it) <= 500 }) { "${file.name}: magnitude values exceed ±500 dB" }
        val fa = f.toDoubleArray()
        // REW can export wrapped or already-unwrapped phase; only unwrap the former, so a long
        // real delay is not folded back into a short one.
        val rad = DoubleArray(deg.size) { deg[it] * PI / 180 }
        val unwrapped = if (deg.all { abs(it) <= 180.000001 }) unwrap(rad) else rad
        // Put back the delay REW removed (J_DSP's convention: phase − 2π·f·delay).
        val delay = delayMs / 1000.0
        val phase = DoubleArray(fa.size) { unwrapped[it] - 2 * PI * fa[it] * delay }
        val logF = DoubleArray(fa.size) { ln(fa[it]) }
        val logGrid = DoubleArray(freqs.size) { ln(freqs[it]) }
        val mag = interp(logGrid, logF, spl.toDoubleArray())
        // Phase is interpolated on a linear axis: a delay's phase is linear in frequency, so this
        // is exact for the bulk delay that dominates the unwrapped phase.
        val ph = interp(freqs, fa, phase)
        val out = CVec(freqs.size)
        for (i in freqs.indices) {
            val a = 10.0.pow((mag[i] + m.calibrationDb) / 20)
            out.re[i] = a * cos(ph[i])
            out.im[i] = a * sin(ph[i])
        }
        return LoadedResponse(out, SourceKind.REW_TEXT, null, fa.first()..fa.last(), smoothing, delayMs)
    }

    private fun readImpulse(file: File, m: Measurement, freqs: DoubleArray, sampleRate: Int): LoadedResponse {
        val wav = readWav(file)
        require(wav.sampleRate == sampleRate) { "${file.name}: WAV is ${wav.sampleRate} Hz but the project is $sampleRate Hz" }
        val x = wav.samples
        require(x.size in 16..(1 shl 22)) { "${file.name}: impulse must be 16 to 4194304 samples" }
        require(x.all { it.isFinite() } && x.maxOf { abs(it) } > 1e-12) { "${file.name}: impulse is silent or non-finite" }
        val n = maxOf(1 shl 18, nextPowerOfTwo(x.size))
        val spec = Fft.rfft(x, n)
        val fftF = Fft.rfftFreqs(n, sampleRate.toDouble())
        // Take the bulk delay out before interpolating complex bins, then put it back: interpolating
        // rapidly rotating bins directly would invent attenuation even for a pure delay.
        var peak = 0
        for (i in x.indices) if (abs(x[i]) > abs(x[peak])) peak = i
        val delay = peak.toDouble() / sampleRate
        val derot = spec.delayed(fftF, -delay)
        val re = interp(freqs, fftF, derot.re)
        val im = interp(freqs, fftF, derot.im)
        val gain = 10.0.pow(m.calibrationDb / 20)
        for (i in re.indices) { re[i] *= gain; im[i] *= gain }
        val out = CVec(re, im).delayed(freqs, delay)
        return LoadedResponse(out, SourceKind.IMPULSE_WAV, peak, 0.0..sampleRate / 2.0)
    }

    /** Reads PCM 8/16/24/32-bit or float 32/64-bit WAV, including WAVE_FORMAT_EXTENSIBLE. Mono only. */
    fun readWav(file: File): WavData {
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") {
            "${file.name}: not a RIFF/WAVE file"
        }
        var pos = 12
        var format = -1
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = b.getInt(pos + 4).toLong() and 0xffffffffL
            val start = pos + 8
            val end = minOf(bytes.size.toLong(), start + size).toInt()
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "${file.name}: invalid format chunk" }
                    format = b.getShort(start).toInt() and 0xffff
                    channels = b.getShort(start + 2).toInt()
                    rate = b.getInt(start + 4)
                    bits = b.getShort(start + 14).toInt()
                    if (format == 0xFFFE && size >= 26) format = b.getShort(start + 24).toInt() and 0xffff
                }
                "data" -> { dataStart = start; dataSize = end - start }
            }
            pos = (start + size + (size and 1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        require(format >= 0 && dataStart >= 0) { "${file.name}: missing fmt or data chunk" }
        require(channels == 1) { "${file.name}: use a mono impulse WAV (found $channels channels)" }
        val bytesPer = bits / 8
        require(bytesPer > 0) { "${file.name}: invalid bit depth" }
        val count = dataSize / bytesPer
        val out = DoubleArray(count)
        for (i in 0 until count) {
            val p = dataStart + i * bytesPer
            out[i] = when {
                format == 1 && bits == 8 -> ((bytes[p].toInt() and 0xff) - 128) / 128.0
                format == 1 && bits == 16 -> b.getShort(p) / 32768.0
                format == 1 && bits == 24 -> {
                    val v = (bytes[p].toInt() and 0xff) or ((bytes[p + 1].toInt() and 0xff) shl 8) or (bytes[p + 2].toInt() shl 16)
                    v / 8388608.0
                }
                format == 1 && bits == 32 -> b.getInt(p) / 2147483648.0
                format == 3 && bits == 32 -> b.getFloat(p).toDouble()
                format == 3 && bits == 64 -> b.getDouble(p)
                else -> throw IllegalArgumentException("${file.name}: unsupported WAV format $format / $bits-bit")
            }
        }
        return WavData(rate, out)
    }
}
