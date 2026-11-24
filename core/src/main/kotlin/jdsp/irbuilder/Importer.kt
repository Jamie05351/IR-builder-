package jdsp.irbuilder

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** A decoded mono WAV. */
class WavData(val sampleRate: Int, val samples: DoubleArray)

/** What kind of file a measurement was read from; affects some quality checks. */
enum class SourceKind { REW_TEXT, IMPULSE_WAV, REW_IMPULSE_TEXT }

/** A measurement resampled onto the design grid, with the time origin of the sweep kept. */
class LoadedResponse(
    val response: CVec,
    val kind: SourceKind,
    /** An impulse's direct-sound onset, in samples after its t = 0; null for frequency-response text. */
    val impulsePeakIndex: Int? = null,
    /** Lowest and highest frequency the file itself covers. */
    val coverageHz: ClosedFloatingPointRange<Double>,
    /** REW's smoothing note ("1/48 octave", "Psychoacoustic", ...), if the header had one. */
    val smoothing: String? = null,
    /** The delay REW took out of the phase and this reader put back, ms (REW text only). */
    val restoredDelayMs: Double = 0.0,
    /**
     * False for a magnitude-only read (no phase column, or no way to know the delay REW took out
     * of it): the response then has zero phase, which can't be summed with other bands.
     */
    val hasPhase: Boolean = true,
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

    /** REW's fixed 1/N-octave export smoothing, or null for none / not stated / variable. */
    val presmoothingOctaveDenominator: Int?
        get() = smoothing?.let { Regex("""1/(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
}

object Importer {
    const val MAX_FILE_BYTES = 128L * 1024 * 1024

    /**
     * Reads a REW frequency/SPL/phase export or a calibrated mono impulse WAV onto [freqs], then
     * applies 1/[smoothing]-octave complex smoothing (0 = none). [freqs] must be log-spaced.
     */
    fun read(
        m: Measurement, freqs: DoubleArray, sampleRate: Int, smoothing: Int = 0,
        /** The span this file must cover; null means the whole grid. */
        required: ClosedFloatingPointRange<Double>? = null,
        /** Impulses only: frequency-dependent window length in cycles (0 = the whole impulse). */
        windowCycles: Int = 0,
        /** Accept magnitude-only REW text (see [DesignOptions.magnitudeOnly]). */
        magnitudeOnly: Boolean = false,
    ): LoadedResponse = silenceOutsideSweep(readSmoothed(m, freqs, sampleRate, smoothing, required, windowCycles, freqs, magnitudeOnly), freqs)

    /** A band-limited sweep says nothing beyond its ends: the band is taken as silent there. */
    private fun silenceOutsideSweep(r: LoadedResponse, freqs: DoubleArray): LoadedResponse {
        if (freqs.all { it in r.coverageHz }) return r
        val h = r.response.copy()
        for (i in freqs.indices) if (freqs[i] < r.coverageHz.start * 0.9999 || freqs[i] > r.coverageHz.endInclusive * 1.0001) {
            h.re[i] = 0.0
            h.im[i] = 0.0
        }
        return LoadedResponse(h, r.kind, r.impulsePeakIndex, r.coverageHz, r.smoothing, r.restoredDelayMs, r.hasPhase)
    }

    private fun readSmoothed(m: Measurement, freqs: DoubleArray, sampleRate: Int, smoothing: Int, required: ClosedFloatingPointRange<Double>?, windowCycles: Int, grid: DoubleArray, magnitudeOnly: Boolean): LoadedResponse {
        // Silence outside the sweep first: the reader holds a band-limited sweep's end values
        // across the rest of the grid, and that flat-phase filler would otherwise outvote the real
        // data in the arrival estimate the smoothing depends on.
        val r = silenceOutsideSweep(readRaw(m, freqs, sampleRate, required ?: freqs.first()..freqs.last(), windowCycles, magnitudeOnly), grid)
        if (smoothing <= 0) return r
        // Start from the delay the file itself states: REW's header note (restored = exported
        // delayed by it) or an impulse's peak. Only the small remainder is estimated.
        return LoadedResponse(complexSmooth(r.response, freqs, smoothing, delayHintSeconds(r, sampleRate)), r.kind, r.impulsePeakIndex, r.coverageHz, r.smoothing, r.restoredDelayMs, r.hasPhase)
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
     * The arrival delay a file states itself: REW's header note (restored = exported delayed by it)
     * or an impulse's peak. Estimating only the small remainder from the log-spaced grid avoids
     * aliasing a long delay.
     */
    fun delayHintSeconds(r: LoadedResponse, sampleRate: Int): Double = when (r.kind) {
        SourceKind.REW_TEXT -> r.restoredDelayMs / 1000.0
        SourceKind.IMPULSE_WAV, SourceKind.REW_IMPULSE_TEXT -> (r.impulsePeakIndex ?: 0).toDouble() / sampleRate
    }

    /** [h]'s arrival delay: [hintSeconds] plus the remainder estimated from the grid. */
    internal fun bulkDelaySeconds(h: CVec, freqs: DoubleArray, hintSeconds: Double): Double =
        hintSeconds + bulkDelaySeconds(h.delayed(freqs, -hintSeconds), freqs)

    /**
     * The response's arrival time from the local group delay between neighbouring grid points.
     *
     * Not weighted by energy: in a car the bass is 20 dB louder and has a long group delay (modes,
     * the subsonic), which would pull the estimate many milliseconds late. Instead each grid point
     * counts the same (equal weight per octave on the log grid), only where the response is within
     * 30 dB of its loudest third-octave, so a band's stopband noise doesn't vote. Frequencies from
     * 300 Hz up decide when the response has them, since that's where the direct sound sets the
     * timing; a pure bass response uses what it has. A first pass uses only steps short enough to
     * read a long delay unambiguously (±[COARSE_RANGE_S]); the second refines on everything.
     */
    internal fun bulkDelaySeconds(h: CVec, freqs: DoubleArray): Double {
        val n = freqs.size
        if (n < 3) return 0.0
        val power = DoubleArray(n) { h.re[it] * h.re[it] + h.im[it] * h.im[it] }
        val ppo = (n - 1) / kotlin.math.log2(freqs.last() / freqs.first())
        val half = (ppo / 6).toInt().coerceAtLeast(1)
        val third = DoubleArray(n) { i ->
            var acc = 0.0
            var k = 0
            for (j in (i - half).coerceAtLeast(0)..(i + half).coerceAtMost(n - 1)) { acc += power[j]; k++ }
            acc / k
        }
        val gate = (third.maxOrNull() ?: return 0.0) * 1e-3
        val voting = (0 until n - 1).filter { power[it] > 0 && power[it + 1] > 0 && third[it] >= gate }
        if (voting.isEmpty()) return 0.0
        val upper = voting.filter { freqs[it] >= DIRECT_SOUND_HZ }
        val use = if (upper.size >= voting.size / 5 && upper.size >= 8) upper else voting
        fun localTau(c: CVec, i: Int): Double {
            val re = c.re[i + 1] * c.re[i] + c.im[i + 1] * c.im[i]
            val im = c.im[i + 1] * c.re[i] - c.re[i + 1] * c.im[i]
            return -kotlin.math.atan2(im, re) / (2 * PI * (freqs[i + 1] - freqs[i]))
        }
        fun median(c: CVec, idx: List<Int>): Double? {
            if (idx.isEmpty()) return null
            val t = idx.map { localTau(c, it) }.sorted()
            return t[t.size / 2]
        }
        // Coarse: only steps that can tell ±COARSE_RANGE_S apart.
        val coarseIdx = use.filter { 1.0 / (2 * (freqs[it + 1] - freqs[it])) >= COARSE_RANGE_S }
        val coarse = median(h, coarseIdx) ?: 0.0
        val rest = h.delayed(freqs, -coarse)
        return coarse + (median(rest, use) ?: 0.0)
    }

    private const val DIRECT_SOUND_HZ = 300.0
    private const val COARSE_RANGE_S = 0.02

    private fun readRaw(m: Measurement, freqs: DoubleArray, sampleRate: Int, required: ClosedFloatingPointRange<Double>, windowCycles: Int, magnitudeOnly: Boolean): LoadedResponse {
        require(m.position.isNotBlank()) { "Position is empty" }
        require(magnitudeOnly || m.timingReference.isNotBlank()) { "Timing-reference ID is empty" }
        require(m.calibrationDb.isFinite() && abs(m.calibrationDb) <= 300) { "Calibration offset must be finite and within ±300 dB" }
        val file = File(m.path)
        require(file.isFile) { "File not found: ${m.path}" }
        require(file.length() <= MAX_FILE_BYTES) { "${file.name}: file exceeds 128 MiB" }
        return when (file.extension.lowercase()) {
            "wav" -> readImpulse(file, m, freqs, sampleRate, windowCycles)
            "txt", "csv", "frd" -> {
                val text = decode(file.readBytes())
                if (text.lineSequence().take(5).any { rewImpulseBanner.containsMatchIn(it) }) readRewImpulseText(file, text, m, freqs, sampleRate, windowCycles)
                else readRewText(file, m, freqs, required, magnitudeOnly)
            }
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
    /**
     * A frequency/SPL row without phase, or null: "20.0 85.3", "20,0;85,3", "20,0 85,3", and CSV
     * "20.0,85.3". A comma is only taken as the column separator when the line has a decimal
     * point, since "20,5" alone is one number written with a decimal comma.
     */
    internal fun twoNumbers(line: String): List<Double>? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val spaced = t.split(spaceOrSemicolon).map { it.trimEnd(',') }.filter { it.isNotEmpty() }
        val parts = when {
            spaced.size == 2 -> spaced.map { it.replace(',', '.') }
            spaced.size == 1 && '.' in t -> t.split(',').map { it.trim() }
            else -> return null
        }
        if (parts.size != 2) return null
        return parts.map { it.toDoubleOrNull() ?: return null }
    }

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

    private fun readRewText(file: File, m: Measurement, freqs: DoubleArray, required: ClosedFloatingPointRange<Double>, magnitudeOnly: Boolean): LoadedResponse {
        val f = ArrayList<Double>()
        val spl = ArrayList<Double>()
        val deg = ArrayList<Double>()
        var delayMs = 0.0
        var smoothing: String? = null
        var sawHeader = false
        var sawDelay = false
        var fromRew = false
        var phaseMissing = false
        var singleNumbers = 0
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
                if (line.trim().replace(',', '.').toDoubleOrNull() != null) singleNumbers++
                // Two numbers and nothing else: an export without the phase column.
                val two = twoNumbers(line)
                if (two != null) {
                    phaseMissing = true
                    // Magnitude only: frequency and SPL are all that's needed.
                    if (magnitudeOnly) {
                        val (freq, db) = two
                        if (freq.isFinite() && db.isFinite() && freq > 0.0 && (f.isEmpty() || freq > f.last())) { f += freq; spl += db; deg += 0.0 }
                    }
                }
                continue
            }
            val (freq, db, ph) = Triple(row[0], row[1], row[2])
            // 0 Hz (linear-spaced exports) has no log position; repeated rows are skipped.
            if (!freq.isFinite() || !db.isFinite() || !ph.isFinite() || freq <= 0.0) continue
            if (f.isNotEmpty() && freq <= f.last()) continue
            f += freq; spl += db; deg += ph
        }
        require(!(f.isEmpty() && singleNumbers >= 16)) {
            "${file.name}: looks like an impulse response exported as text (one value per line). Use File › Export › Measurement as text (frequency, SPL, phase), or export the impulse response as a WAV."
        }
        require(magnitudeOnly || !(phaseMissing && f.isEmpty())) { "${file.name}: a phase column is required; the band sum depends on relative phase" }
        require(f.size >= 16) { "${file.name}: no frequency / SPL / phase rows. Export from REW with File › Export › Measurement as text" }
        // Without a phase column, or without knowing the delay REW took out of it, the phase is
        // unusable: a magnitude-only read keeps the magnitude and drops the phase.
        val hasPhase = !phaseMissing && (sawDelay || fromRew)
        // REW takes the measurement's delay out of the phase and records it only in the header.
        require(magnitudeOnly || sawHeader) {
            "${file.name}: has no REW header, which holds the delay REW took out of the phase. Re-export with \"Do not include any comments or headers\" unticked."
        }
        // REW writes the note only when it took a delay out, so its own header without one means
        // 0 ms. Any other header without one can't say what was taken out: refused, not guessed.
        require(magnitudeOnly || sawDelay || fromRew) {
            "${file.name}: its header has no \"Delay ... ms\" note, so the delay REW took out of the phase is unknown. Export it from REW with File › Export › Measurement as text."
        }
        require(f.first() <= required.start * 1.0001 && f.last() >= required.endInclusive * 0.9999) {
            "${file.name}: covers %.0f–%.0f Hz, but its ${m.band.label} band needs at least %.0f–%.0f Hz (an octave either side of its crossovers). Sweep or export a wider range.".format(f.first(), f.last(), required.start, required.endInclusive)
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
        val phase = DoubleArray(fa.size) { if (hasPhase) unwrapped[it] - 2 * PI * fa[it] * delay else 0.0 }
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
        return LoadedResponse(out, SourceKind.REW_TEXT, null, fa.first()..fa.last(), smoothing, if (hasPhase) delayMs else 0.0, hasPhase)
    }

    /**
     * True for a recording of the measurement sweep (or any long signal) rather than an impulse
     * response. Two things must both hold, so a slowly building, long-ringing impulse isn't caught:
     *  - its energy is spread out: under 35% in the 100 ms after its loudest sample, and over 5%
     *    in the 200 ms before it (an impulse arrives at once);
     *  - its frequency rises with time: across at least four octave bands that stand out from the
     *    background, spanning three octaves or more, each band is loudest later than the one below
     *    it, 0.4 s or more from first to last. An impulse's bands all peak within a moment of
     *    each other, however long a resonance rings.
     */
    internal fun looksLikeSweepRecording(x: DoubleArray, sampleRate: Int): Boolean {
        if (x.size < sampleRate / 2) return false
        var peak = 0
        for (i in x.indices) if (abs(x[i]) > abs(x[peak])) peak = i
        var total = 0.0
        for (v in x) total += v * v
        if (total <= 0) return false
        fun energy(from: Int, to: Int): Double {
            var e = 0.0
            for (i in max(0, from) until min(x.size, to)) e += x[i] * x[i]
            return e / total
        }
        val main = energy(peak - (0.005 * sampleRate).toInt(), peak + (0.1 * sampleRate).toInt())
        val before = energy(peak - (0.2 * sampleRate).toInt(), peak - (0.005 * sampleRate).toInt())
        if (main >= 0.35 || before <= 0.05) return false
        return frequencyRisesWithTime(x, sampleRate)
    }

    /** See [looksLikeSweepRecording]: octave bands from 31.5 Hz, each one's loudest moment. */
    private fun frequencyRisesWithTime(x: DoubleArray, sampleRate: Int): Boolean {
        val w = 4096
        val hop = 1024
        val frames = (x.size - w) / hop
        if (frames < 4) return false
        val centres = (0 until 10).map { 31.5 * (1 shl it) }.filter { it * 1.414 < sampleRate / 2.0 }
        val binHz = sampleRate.toDouble() / w
        val bins = centres.map { c -> (Math.ceil(c / 1.414 / binHz).toInt()).coerceAtLeast(1) until (c * 1.414 / binHz).toInt().coerceAtMost(w / 2) }
        val win = DoubleArray(w) { 0.5 - 0.5 * cos(2 * PI * it / w) }
        val e = Array(centres.size) { DoubleArray(frames) }
        val seg = DoubleArray(w)
        for (j in 0 until frames) {
            for (k in 0 until w) seg[k] = x[j * hop + k] * win[k]
            val s = Fft.rfft(seg, w)
            for (b in centres.indices) {
                var acc = 0.0
                for (i in bins[b]) acc += s.re[i] * s.re[i] + s.im[i] * s.im[i]
                e[b][j] = acc
            }
        }
        // Bands that stand 15 dB clear of their own background, and when each is loudest.
        val loud = centres.indices.mapNotNull { b ->
            val sorted = e[b].sorted()
            val median = sorted[sorted.size / 2] + 1e-30
            val top = e[b].indices.maxBy { e[b][it] }
            if (e[b][top] >= 30 * median) b to top * hop / sampleRate.toDouble() else null
        }
        // Longest run, in frequency order, whose loudest moments never go back in time.
        val n = loud.size
        if (n < 4) return false
        val len = IntArray(n) { 1 }
        val prev = IntArray(n) { -1 }
        for (i in 0 until n) for (j in 0 until i) if (loud[j].second <= loud[i].second && len[j] + 1 > len[i]) { len[i] = len[j] + 1; prev[i] = j }
        val end = len.indices.maxBy { len[it] }
        var start = end
        while (prev[start] >= 0) start = prev[start]
        return len[end] >= 4 && loud[end].first - loud[start].first >= 3 && loud[end].second - loud[start].second >= 0.4
    }

    private fun readImpulse(file: File, m: Measurement, freqs: DoubleArray, sampleRate: Int, windowCycles: Int): LoadedResponse {
        val wav = readWav(file)
        require(!looksLikeSweepRecording(wav.samples, wav.sampleRate)) {
            "${file.name}: this is a recording of the sweep itself, not an impulse response: its sound is spread over seconds instead of arriving at once. " +
                "Measure in REW and export the result: File › Export › Impulse response as WAV, or File › Export › Measurement as text."
        }
        require(wav.sampleRate == sampleRate) { "${file.name}: WAV is ${wav.sampleRate} Hz but the project is $sampleRate Hz" }
        val x = wav.samples
        require(x.size in 16..(1 shl 22)) { "${file.name}: impulse must be 16 to 4194304 samples" }
        require(x.all { it.isFinite() } && x.maxOf { abs(it) } > 1e-12) { "${file.name}: impulse is silent or non-finite" }
        if (windowCycles > 0) {
            val onset = Fdw.onset(x, sampleRate)
            val gain = 10.0.pow(m.calibrationDb / 20)
            val h = Fdw.spectrum(x, sampleRate, 0.0, onset, windowCycles.toDouble(), freqs)
            for (i in freqs.indices) { h.re[i] *= gain; h.im[i] *= gain }
            return LoadedResponse(h, SourceKind.IMPULSE_WAV, onset, 0.0..sampleRate / 2.0)
        }
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

    private val rewImpulseBanner = Regex("""Impulse Response data saved by REW""", RegexOption.IGNORE_CASE)
    private val headerValue = Regex("""^\s*(-?[0-9][0-9.,eE+-]*)\s*//\s*(.+?)\s*$""")

    /**
     * REW's "Impulse response as text" export (header lines "value // label", then one sample per
     * line after "* Data start"). It carries the absolute time base: "Start time (seconds)" is the
     * time of the first sample, so t = 0 (the timing reference) sits at -start × rate. A normalised
     * export is multiplied back by its stated peak, and REW's "Data offset (dB)" is applied so the
     * level matches REW's SPL. A windowed or minimum-phase export is refused: it isn't the response.
     */
    private fun readRewImpulseText(file: File, text: String, m: Measurement, freqs: DoubleArray, sampleRate: Int, windowCycles: Int): LoadedResponse {
        val values = HashMap<String, Double>()
        val samples = ArrayList<Double>()
        var normalised = false
        var data = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (data) {
                val v = line.replace(',', '.').toDoubleOrNull()
                require(v != null && v.isFinite()) { "${file.name}: a sample can't be read: \"${line.take(40)}\"" }
                samples += v
                continue
            }
            if (line.startsWith("* Data start", ignoreCase = true)) { data = true; continue }
            if (line.startsWith("*")) {
                val note = line.trimStart('*').trim()
                val negated = note.contains(" not ", ignoreCase = true)
                when {
                    note.startsWith("IR is", true) && (note.contains("normalised", true) || note.contains("normalized", true)) -> normalised = !negated
                    note.startsWith("IR window", true) && note.contains("applied", true) && !negated ->
                        throw IllegalArgumentException("${file.name}: REW's IR window was applied to this export. Export the impulse response again with the window off.")
                    note.startsWith("IR is", true) && note.contains("min phase", true) && !negated ->
                        throw IllegalArgumentException("${file.name}: this is the minimum-phase version, without its timing. Export the impulse response itself.")
                }
                continue
            }
            headerValue.find(line)?.let { mt -> mt.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { values[mt.groupValues[2].lowercase()] = it } }
        }
        require(samples.size >= 16) { "${file.name}: the impulse export holds no samples" }
        val interval = values["sample interval (seconds)"]
        require(interval != null && interval > 0) { "${file.name}: the header states no sample interval" }
        val rate = Math.round(1.0 / interval).toInt()
        require(rate == sampleRate) { "${file.name}: impulse is $rate Hz but the project is $sampleRate Hz" }
        val startTime = values["start time (seconds)"]
            ?: throw IllegalArgumentException("${file.name}: the header states no start time, so the impulse can't be placed in time")
        values["response length"]?.let { require(Math.round(it).toInt() == samples.size) { "${file.name}: the header declares ${Math.round(it)} samples but the file holds ${samples.size}; it was truncated or edited" } }
        val x = samples.toDoubleArray()
        if (normalised) {
            val peak = values["peak value before normalisation"]
            require(peak != null && peak > 0) { "${file.name}: a normalised export without its peak value can't be restored; export with normalisation off" }
            for (i in x.indices) x[i] *= peak
        }
        require(x.maxOf { abs(it) } > 1e-15) { "${file.name}: impulse is silent" }
        val timeZero = -startTime * rate
        val onset = Fdw.onset(x, rate)
        val gain = 10.0.pow(((values["data offset (db)"] ?: 0.0) + m.calibrationDb) / 20)
        val h = Fdw.spectrum(x, rate, timeZero, onset, windowCycles.toDouble(), freqs)
        for (i in freqs.indices) { h.re[i] *= gain; h.im[i] *= gain }
        return LoadedResponse(h, SourceKind.REW_IMPULSE_TEXT, Math.round(onset - timeZero).toInt(), 0.0..rate / 2.0)
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
