package jdsp.irbuilder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jdsp.irbuilder.AlignOptions
import jdsp.irbuilder.Band
import jdsp.irbuilder.BandAlign
import jdsp.irbuilder.BandAlignReport
import jdsp.irbuilder.Channel
import jdsp.irbuilder.CheckResult
import jdsp.irbuilder.CheckScope
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
import jdsp.irbuilder.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Step(val title: String, val subtitle: String) {
    MEASUREMENTS("Measurements", "Import and tag"),
    CHECKS("Checks", "Timing, quality, completeness"),
    BAND_ALIGN("Band Align", "Delay & polarity"),
    DESIGN("Design", "Crossovers, limits, seats"),
    PREVIEW("Preview", "Predicted result"),
    EXPORT("Export", "Package for J_DSP"),
}

/** Each tool's own steps; both start with importing and checking measurements. */
fun stepsFor(tool: Tool): List<Step> = when (tool) {
    Tool.BAND_ALIGN -> listOf(Step.MEASUREMENTS, Step.CHECKS, Step.BAND_ALIGN)
    Tool.PHASE_IRS -> listOf(Step.MEASUREMENTS, Step.CHECKS, Step.DESIGN, Step.PREVIEW, Step.EXPORT)
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

class AppState(private var scope: CoroutineScope) {
    /** Tests only: run later background work in another scope. */
    internal fun rescope(s: CoroutineScope) { scope = s }

    /** The tool chosen on the start screen; null shows the start screen. */
    var tool by mutableStateOf<Tool?>(null)
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
    var align by mutableStateOf(AlignOptions())
        private set
    var alignError by mutableStateOf<String?>(null)
    var alignment by mutableStateOf<BandAlignReport?>(null)
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
        alignment = null
        design = null
        exportedZip = null
    }

    /** Opens a tool from the start screen. Imported measurements are kept. */
    fun startTool(t: Tool) {
        tool = t
        step = Step.MEASUREMENTS
        status = when (t) {
            Tool.BAND_ALIGN -> "Band Align: import Low/Mid/High band-solo sweeps for both seats, then enter J_DSP's delays."
            Tool.PHASE_IRS -> "Phase IRs: import software-band-solo sweeps for both seats and both channels."
        }
    }

    /** Band Align inputs changed: its result goes, the checks stay valid. */
    fun updateAlign(a: AlignOptions) {
        // Which bands Band Align needs depends on its pairs, so its checks must be re-run.
        if (a.lowMid != align.lowMid || a.midHigh != align.midHigh) checks = null
        align = a
        alignError = runCatching { a.timing.validate() }.exceptionOrNull()?.message
        alignment = null
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

    /** A background result is only published if the inputs it was computed from are still current. */
    private fun inputsChangedSince(snapshot: List<Measurement>, o: DesignOptions, a: AlignOptions = align) =
        measurements.toList() != snapshot || options != o || align != a

    /** The loaded set and its checks, reusing what's current. */
    private fun loadAndCheck(snapshot: List<Measurement>, o: DesignOptions, scope: CheckScope = checkScope()): Pair<MeasurementSet, CheckResult> {
        val xo = scope.crossovers.toSet()
        val s = set?.takeIf { it.options == o && it.measurements == snapshot && it.crossovers == xo } ?: MeasurementSet.load(snapshot, o, xo)
        // The checks are cheap next to reading files; always run them for the current tool's scope.
        return s to Checks.run(s, scope)
    }

    /** What the current tool requires of the measurements. */
    fun checkScope(): CheckScope = if (tool == Tool.BAND_ALIGN) CheckScope.bandAlign(align) else CheckScope.PHASE_IRS

    /**
     * Installs a freshly loaded set. Results computed from a different load (e.g. files re-exported
     * under the same names) are dropped, so nothing shown or exported comes from old data.
     */
    private fun publish(s: MeasurementSet, c: CheckResult) {
        if (s !== set) {
            alignment = null
            design = null
            exportedZip = null
        }
        set = s
        checks = c
    }

    fun runAlign() = background("Searching delays and polarities…") {
        val snapshot = measurements.toList()
        val o = options
        val a = align
        val (s, c, r) = withContext(Dispatchers.Default) {
            val (s, c) = loadAndCheck(snapshot, o, CheckScope.bandAlign(a))
            Triple(s, c, if (c.canDesign) BandAlign.solve(s, a) else null)
        }
        if (inputsChangedSince(snapshot, o, a)) {
            status = "Inputs changed while aligning; run Band Align again."
            return@background
        }
        publish(s, c)
        alignment = r
        if (r == null) {
            status = "Band Align blocked: ${c.errors} error(s) on the Checks page."
            step = Step.CHECKS
        } else {
            status = r.error ?: if (r.hasChanges) "Band Align: ${r.changes.size} J_DSP setting(s) to change." else "Band Align: the bands already add up; no changes."
        }
    }

    fun runChecks(then: (() -> Unit)? = null) = background("Reading measurements and running checks…") {
        val snapshot = measurements.toList()
        val scope = checkScope()
        val o = options
        val (s, c) = withContext(Dispatchers.Default) {
            val s = MeasurementSet.load(snapshot, o, scope.crossovers.toSet())
            s to Checks.run(s, scope)
        }
        if (inputsChangedSince(snapshot, o)) {
            status = "Inputs changed while checking; run the checks again."
            return@background
        }
        publish(s, c)
        status = "Checks: ${c.errors} error(s), ${c.warnings} warning(s)."
        then?.invoke()
    }

    fun calculate() = background("Calculating phase-alignment filters…") {
        val snapshot = measurements.toList()
        val o = options
        val (s, c, d) = withContext(Dispatchers.Default) {
            val (s, c) = loadAndCheck(snapshot, o, CheckScope.PHASE_IRS)
            // The phase IRs are designed on the measurements as taken: Band Align is a separate
            // tool, applied in J_DSP and re-measured before designing IRs.
            Triple(s, c, if (c.canDesign) Designer.design(s) else null)
        }
        if (inputsChangedSince(snapshot, o)) {
            status = "Inputs changed while calculating; result discarded. Calculate again."
            return@background
        }
        publish(s, c)
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
        withContext(Dispatchers.IO) { Project.save(Project(tool = tool, measurements = measurements.toList(), options = options, align = align), f) }
        projectFile = f
        status = "Saved ${f.name}"
    }

    fun openProject(file: File) = background("Opening project…") {
        val p = withContext(Dispatchers.IO) { Project.load(file) }
        measurements.clear()
        measurements.addAll(p.measurements)
        selected.clear()
        updateOptions(p.options)
        updateAlign(p.align)
        tool = p.tool ?: Tool.PHASE_IRS
        step = Step.MEASUREMENTS
        projectFile = file
        status = "Opened ${file.name}: ${p.measurements.size} measurement(s)."
    }
}
