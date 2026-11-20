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
)

object Importer {
    const val MAX_FILE_BYTES = 128L * 1024 * 1024

    /** Reads a REW frequency/SPL/phase export or a calibrated mono impulse WAV. */
    fun read(m: Measurement, freqs: DoubleArray, sampleRate: Int): LoadedResponse {
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

    private fun readRewText(file: File, m: Measurement, freqs: DoubleArray): LoadedResponse {
        val rows = ArrayList<DoubleArray>()
        val split = Regex("[\\s,;]+")
        file.readText(Charsets.UTF_8).removePrefix("﻿").lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("#") || line.startsWith("//") || line.startsWith(";")) return@forEach
            val parts = line.split(split).filter { it.isNotEmpty() }
            val nums = parts.take(3).map { it.toDoubleOrNull() }
            if (nums.isEmpty() || nums.any { it == null }) return@forEach
            require(nums.size >= 3) { "${file.name}: a phase column is required; the band sum depends on relative phase" }
            rows += DoubleArray(3) { nums[it]!! }
        }
        require(rows.size >= 8) { "${file.name}: need at least eight frequency/SPL/phase rows" }
        require(rows.all { r -> r.all { it.isFinite() } }) { "${file.name}: contains non-finite values" }
        val f = DoubleArray(rows.size) { rows[it][0] }
        val spl = DoubleArray(rows.size) { rows[it][1] }
        val deg = DoubleArray(rows.size) { rows[it][2] }
        require(f.all { it > 0 } && (1 until f.size).all { f[it] > f[it - 1] }) { "${file.name}: frequencies must be positive and strictly increasing" }
        require(f.first() <= freqs.first() * 1.0001 && f.last() >= freqs.last() * 0.9999) {
            "${file.name}: covers %.1f–%.0f Hz but the design needs %.1f–%.0f Hz".format(f.first(), f.last(), freqs.first(), freqs.last())
        }
        require(spl.max() > -180) { "${file.name}: response appears silent" }
        require(spl.all { abs(it) <= 500 }) { "${file.name}: magnitude values exceed ±500 dB" }
        // REW can export wrapped or already-unwrapped phase; only unwrap the former, so a long
        // real delay is not folded back into a short one.
        val rad = DoubleArray(deg.size) { deg[it] * PI / 180 }
        val phase = if (deg.all { abs(it) <= 180.000001 }) unwrap(rad) else rad
        val logF = DoubleArray(f.size) { ln(f[it]) }
        val logGrid = DoubleArray(freqs.size) { ln(freqs[it]) }
        val mag = interp(logGrid, logF, spl)
        // Phase is interpolated on a linear axis: a delay's phase is linear in frequency, so this
        // is exact for the bulk delay that dominates the unwrapped phase.
        val ph = interp(freqs, f, phase)
        val out = CVec(freqs.size)
        for (i in freqs.indices) {
            val a = 10.0.pow((mag[i] + m.calibrationDb) / 20)
            out.re[i] = a * cos(ph[i])
            out.im[i] = a * sin(ph[i])
        }
        return LoadedResponse(out, SourceKind.REW_TEXT, null, f.first()..f.last())
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
