package jdsp.irbuilder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jdsp.irbuilder.Band
import jdsp.irbuilder.Channel
import jdsp.irbuilder.CheckResult
import jdsp.irbuilder.Checks
import jdsp.irbuilder.DesignOptions
import jdsp.irbuilder.Designer
import jdsp.irbuilder.Exporter
import jdsp.irbuilder.FilenameTags
import jdsp.irbuilder.Measurement
import jdsp.irbuilder.MeasurementSet
import jdsp.irbuilder.PhaseDesign
import jdsp.irbuilder.Project
import jdsp.irbuilder.Seat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Step(val title: String, val subtitle: String) {
    MEASUREMENTS("Measurements", "Import and tag"),
    CHECKS("Checks", "Timing, quality, completeness"),
    DESIGN("Design", "Crossovers, limits, seats"),
    PREVIEW("Preview", "Predicted result"),
    EXPORT("Export", "Package for J_DSP"),
}

/** Tags applied to newly added files (filename auto-tagging may override them). */
data class Defaults(
    val seat: Seat = Seat.DRIVER,
    val position: String = "centre",
    val channel: Channel = Channel.L,
    val band: Band = Band.LOW,
    val referenceL: String = "R speaker",
    val referenceR: String = "L speaker",
    val calibrationDb: Double = 0.0,
    val validationOnly: Boolean = false,
    val autoTag: Boolean = true,
) {
    fun reference(c: Channel) = if (c == Channel.L) referenceL else referenceR
}

class AppState(private val scope: CoroutineScope) {
    var step by mutableStateOf(Step.MEASUREMENTS)
    val measurements = mutableStateListOf<Measurement>()
    val selected = mutableStateListOf<Int>()
    var defaults by mutableStateOf(Defaults())
    var options by mutableStateOf(DesignOptions())
        private set
    var optionsError by mutableStateOf<String?>(null)
    var checks by mutableStateOf<CheckResult?>(null)
    var set by mutableStateOf<MeasurementSet?>(null)
    var design by mutableStateOf<PhaseDesign?>(null)
    var busy by mutableStateOf<String?>(null)
    var status by mutableStateOf("Import software-band-solo sweeps for both seats and both channels.")
    var errorMessage by mutableStateOf<String?>(null)
    var projectFile by mutableStateOf<File?>(null)
    var exportDir by mutableStateOf<File?>(null)
    var exportedZip by mutableStateOf<File?>(null)

    /** Anything that changes inputs invalidates the checks and the design. */
    private fun invalidate() {
        checks = null
        set = null
        design = null
        exportedZip = null
    }

    fun updateOptions(o: DesignOptions) {
        options = o
        optionsError = runCatching { o.validate() }.exceptionOrNull()?.message
        invalidate()
    }

    fun addFiles(files: List<File>) {
        val d = defaults
        val existing = measurements.map { it.path }.toSet()
        var added = 0
        for (f in files.sortedBy { it.name.lowercase() }) {
            if (f.path in existing) continue
            val t = if (d.autoTag) FilenameTags.parse(f.name) else FilenameTags.Tags(null, null, null, null)
            val ch = t.channel ?: d.channel
            measurements += Measurement(
                path = f.path, seat = t.seat ?: d.seat, position = t.position ?: d.position, channel = ch,
                band = t.band ?: d.band, timingReference = d.reference(ch), validationOnly = d.validationOnly, calibrationDb = d.calibrationDb,
            )
            added++
        }
        status = "Added $added file(s). Check each row's tags."
        invalidate()
    }

    fun update(index: Int, m: Measurement) {
        if (index in measurements.indices && measurements[index] != m) {
            measurements[index] = m
            invalidate()
        }
    }

    fun removeSelected() {
        val idx = selected.sortedDescending()
        idx.forEach { if (it in measurements.indices) measurements.removeAt(it) }
        selected.clear()
        status = "Removed ${idx.size} file(s)."
        invalidate()
    }

    fun clearAll() {
        measurements.clear()
        selected.clear()
        invalidate()
    }

    fun toggleSelect(i: Int) { if (i in selected) selected.remove(i) else selected += i }

    /** Applies the current default tags to the selected rows. */
    fun applyDefaultsToSelected(seat: Boolean, position: Boolean, channel: Boolean, band: Boolean, reference: Boolean, validation: Boolean) {
        val d = defaults
        for (i in selected) {
            var m = measurements.getOrNull(i) ?: continue
            if (seat) m = m.copy(seat = d.seat)
            if (position) m = m.copy(position = d.position)
            if (channel) m = m.copy(channel = d.channel)
            if (band) m = m.copy(band = d.band)
            if (reference) m = m.copy(timingReference = d.reference(m.channel))
            if (validation) m = m.copy(validationOnly = d.validationOnly)
            measurements[i] = m
        }
        invalidate()
    }

    private fun background(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        errorMessage = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                errorMessage = e.message ?: e.toString()
                status = "Failed: ${errorMessage}"
            } finally {
                busy = null
            }
        }
    }

    fun runChecks(then: (() -> Unit)? = null) = background("Reading measurements and running checks…") {
        val snapshot = measurements.toList()
        val o = options
        val (s, c) = withContext(Dispatchers.Default) {
            val s = MeasurementSet.load(snapshot, o)
            s to Checks.run(s)
        }
        set = s
        checks = c
        status = "Checks: ${c.errors} error(s), ${c.warnings} warning(s)."
        then?.invoke()
    }

    fun calculate() = background("Calculating phase-alignment filters…") {
        val snapshot = measurements.toList()
        val o = options
        val (s, c, d) = withContext(Dispatchers.Default) {
            val s = set?.takeIf { it.options == o && it.measurements == snapshot } ?: MeasurementSet.load(snapshot, o)
            val c = Checks.run(s)
            Triple(s, c, if (c.canDesign) Designer.design(s) else null)
        }
        set = s
        checks = c
        design = d
        exportedZip = null
        if (d == null) {
            status = "Design blocked: ${c.errors} error(s) on the Checks page."
            step = Step.CHECKS
        } else {
            status = "Design ready. Review both seats before exporting."
            step = Step.PREVIEW
        }
    }

    fun export(dir: File) = background("Writing package…") {
        val d = design ?: throw IllegalStateException("Calculate a design first")
        val zip = withContext(Dispatchers.IO) { Exporter.export(d, dir) }
        exportDir = dir
        exportedZip = zip
        status = "Exported ${zip.path}"
    }

    fun saveProject(file: File) = background("Saving project…") {
        val f = if (file.extension.equals(Project.EXTENSION, true)) file else File(file.path + "." + Project.EXTENSION)
        withContext(Dispatchers.IO) { Project.save(Project(measurements = measurements.toList(), options = options), f) }
        projectFile = f
        status = "Saved ${f.name}"
    }

    fun openProject(file: File) = background("Opening project…") {
        val p = withContext(Dispatchers.IO) { Project.load(file) }
        measurements.clear()
        measurements.addAll(p.measurements)
        selected.clear()
        updateOptions(p.options)
        projectFile = file
        status = "Opened ${file.name}: ${p.measurements.size} measurement(s)."
    }
}
