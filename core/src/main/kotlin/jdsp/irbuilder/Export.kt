package jdsp.irbuilder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the package J_DSP's convolver page imports (`BandIrPackage` / `BandIrManifest` in
 * SiphonDSP_J): format "siphondsp-band-ir" version 1, three stereo float32 WAVs, every file at
 * the ZIP root, names matching `[A-Za-z0-9_.-]+`.
 */
object Exporter {
    const val PACKAGE_NAME = "band-ir-package.zip"
    const val GENERATOR = "J_DSP IR Builder 1.0 (phase-only)"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** Stereo IEEE-float WAV with a plain 16-byte fmt chunk (format tag 3), as J_DSP requires. */
    fun stereoFloatWav(sampleRate: Int, left: FloatArray, right: FloatArray): ByteArray {
        require(left.size == right.size)
        val frames = left.size
        val data = frames * 8
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data).put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        b.putShort(3).putShort(2).putInt(sampleRate).putInt(sampleRate * 8).putShort(8).putShort(32)
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(data)
        for (i in 0 until frames) { b.putFloat(left[i]); b.putFloat(right[i]) }
        return b.array()
    }

    fun manifest(d: PhaseDesign): JsonObject {
        val o = d.options
        return buildJsonObject {
            put("format", "siphondsp-band-ir")
            put("version", 1)
            put("correction_only", true)
            put("correction_type", "phase_only")
            put("sample_rate", o.sampleRate)
            put("taps", o.taps)
            put("latency_frames", o.latencyFrames)
            putJsonObject("crossover_hz") {
                put("low_mid", o.lowMidHz)
                put("mid_high", o.midHighHz)
            }
            putJsonObject("files") { Band.BRANCHES.forEach { put(it.fileName, "${it.fileName}.wav") } }
            putJsonObject("gain_db") { Band.BRANCHES.forEach { put(it.fileName, 0.0) } }
            put("common_gain_db", d.commonGainDb.round(4))
            put("design", json.encodeToJsonElement(o))
            put("measurement_topology", "software-band-solo; all physical speakers connected; shared stereo plant")
            put("routing_assumption", "diagonal stereo; mono bass and cross-channel mixing disabled")
            put("generator", GENERATOR)
            // Informational (J_DSP's importer ignores unknown keys): Band Align changes the IRs need.
            if (d.report.jdspSettingChanges.isNotEmpty()) {
                put("jdsp_setting_changes", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(JdspSettingChange.serializer()), d.report.jdspSettingChanges))
            }
        }
    }

    fun readme(d: PhaseDesign): String {
        val o = d.options
        return """
            J_DSP band IR package (phase-only crossover alignment)
            ======================================================

            Files: low.wav, mid.wav, high.wav (stereo float32, ${o.sampleRate} Hz, ${o.taps} taps),
            manifest.json, report.json.

            These IRs only change each band's phase near the crossovers so the software bands sum
            coherently at both seats. They do not change band magnitude, and contain no crossover.

            @@BAND_ALIGN@@Loading in J_DSP
            1. Convolver page: import this ZIP and check the Low, Mid and High slots all load.
            2. Select Per-band mode. Set band-mode gain to 0 dB to match the prediction.
            3. Keep the measured baseline exactly: Low/Mid ${"%.1f".format(o.lowMidHz)} Hz, Mid/High ${"%.1f".format(o.midHighHz)} Hz,
               the same slopes, PEQ, delays, polarity, gains and headroom used for the sweeps.
            4. Mono bass and cross-channel routing must stay off.
            5. Added latency: about ${"%.0f".format(o.jdspLatencyMs)} ms (common IR delay ${o.latencyFrames} frames + J_DSP's
               1024-frame partition). Check video, phone and navigation audio on the car.
            6. Do not normalise or realign the WAVs individually: that breaks the band relationship.
            7. Re-measure both seats with the IRs active. The report's numbers are predictions.

            Summed level may rise near the crossovers where cancellation is removed; re-check the PEQ.
        """.trimIndent().replace("@@BAND_ALIGN@@", bandAlignSection(d)) + "\n"
    }

    private fun bandAlignSection(d: PhaseDesign): String {
        val changes = d.report.jdspSettingChanges
        if (changes.isEmpty()) return ""
        return buildString {
            append("FIRST: Band Align changes (Alignment > DELAY page)\n")
            append("These IRs were designed for these J_DSP settings. Make them before loading the IRs:\n")
            changes.forEach { append("  - ").append(it.text).append('\n') }
            append("\n")
        }
    }

    /** Writes the loose files and the ZIP into [directory]. Returns the ZIP. */
    fun export(d: PhaseDesign, directory: File): File {
        require(d.impulses.all { b -> b.all { c -> c.all { it.isFinite() } } }) { "Design contains non-finite coefficients; nothing exported" }
        directory.mkdirs()
        val entries = linkedMapOf<String, ByteArray>()
        entries["manifest.json"] = json.encodeToString(JsonObject.serializer(), manifest(d)).toByteArray()
        entries["report.json"] = json.encodeToString(DesignReport.serializer(), d.report).toByteArray()
        Band.BRANCHES.forEachIndexed { bi, b ->
            entries["${b.fileName}.wav"] = stereoFloatWav(d.options.sampleRate, d.impulses[bi][0], d.impulses[bi][1])
        }
        entries["README.txt"] = readme(d).toByteArray()
        for ((name, bytes) in entries) File(directory, name).writeBytes(bytes)
        val zip = File(directory, PACKAGE_NAME)
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        zip.writeBytes(buffer.toByteArray())
        return zip
    }
}
