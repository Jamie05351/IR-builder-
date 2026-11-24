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
    FINDER("Crossover Finder", "Frequency, slope, delay"),
    EQ("EQ Wizard", "PEQ bands toward a target"),
    DESIGN("Design", "Crossovers, limits, seats"),
    PREVIEW("Preview", "Predicted result"),
    EXPORT("Export", "Package for J_DSP"),
}

/** Each tool's own steps; both start with importing and checking measurements. */
fun stepsFor(tool: Tool): List<Step> = when (tool) {
    Tool.BAND_ALIGN -> listOf(Step.MEASUREMENTS, Step.CHECKS, Step.BAND_ALIGN)
    Tool.PHASE_IRS -> listOf(Step.MEASUREMENTS, Step.CHECKS, Step.DESIGN, Step.PREVIEW, Step.EXPORT)
    Tool.CROSSOVER_FINDER -> listOf(Step.MEASUREMENTS, Step.CHECKS, Step.FINDER)
    Tool.EQ_WIZARD -> listOf(Step.MEASUREMENTS, Step.CHECKS, Step.EQ)
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
    /** The Phase IRs tool's result in Stereo mode (one IR after the band sum). */
    var stereoDesign by mutableStateOf<jdsp.irbuilder.StereoDesign?>(null)
    val hasDesign get() = design != null || stereoDesign != null
    var align by mutableStateOf(AlignOptions())
        private set
    var alignError by mutableStateOf<String?>(null)
    var alignment by mutableStateOf<BandAlignReport?>(null)
    var finder by mutableStateOf(jdsp.irbuilder.FinderOptions())
        private set
    var finderError by mutableStateOf<String?>(null)
    var finding by mutableStateOf<jdsp.irbuilder.FinderReport?>(null)
    /** Each driver path's usable range, from the last "Suggest from measurements". */
    var bandRanges by mutableStateOf<Map<Band, jdsp.irbuilder.BandEstimate.Range>?>(null)
    var eq by mutableStateOf(jdsp.irbuilder.EqOptions())
        private set
    var eqError by mutableStateOf<String?>(null)
    var eqResult by mutableStateOf<jdsp.irbuilder.EqResult?>(null)
    var eqExported by mutableStateOf<File?>(null)
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
        finding = null
        eqResult = null
        eqExported = null
        design = null
        stereoDesign = null
        exportedZip = null
    }

    /**
     * The settings measurements are read with. The Crossover Finder takes its crossover corners
     * from its own page (J_DSP's filters as set), so the sweeps are checked around those.
     */
    fun effectiveOptions(): DesignOptions = when (tool) {
        Tool.CROSSOVER_FINDER -> options.copy(lowMidHz = finder.current.lowMidHz, midHighHz = finder.current.midHighHz)
        // The EQ reads the whole fit range: the Stereo range is what sets how far files are read.
        Tool.EQ_WIZARD -> options.copy(irMode = jdsp.irbuilder.IrMode.STEREO, stereoMinHz = eq.minHz, stereoMaxHz = eq.maxHz)
        else -> options
    }

    /** EQ Wizard inputs changed: its result goes; the checks too if the fit range changed. */
    fun updateEq(e: jdsp.irbuilder.EqOptions) {
        if (e.minHz != eq.minHz || e.maxHz != eq.maxHz) { checks = null; set = null }
        eq = e
        eqError = runCatching { e.validate() }.exceptionOrNull()?.message
        eqResult = null
        eqExported = null
    }

    /** Crossover Finder inputs changed: its result goes; the checks too if what they cover changed. */
    fun updateFinder(f: jdsp.irbuilder.FinderOptions) {
        val old = finder
        if (f.crossovers != old.crossovers || f.current.lowMidHz != old.current.lowMidHz || f.current.midHighHz != old.current.midHighHz) {
            checks = null
            set = null
        }
        finder = f
        finderError = runCatching { f.validate() }.exceptionOrNull()?.message
        finding = null
    }

    /** Opens a tool from the start screen. Imported measurements are kept. */
    fun startTool(t: Tool) {
        tool = t
        step = Step.MEASUREMENTS
        status = when (t) {
            Tool.BAND_ALIGN -> "Band Align: import Low/Mid/High band-solo sweeps for both seats, then enter J_DSP's delays."
            Tool.PHASE_IRS -> "Phase IRs: import software-band-solo sweeps for both seats and both channels."
            Tool.CROSSOVER_FINDER -> "Crossover Finder: import Low/Mid/High band-solo sweeps for both seats, then enter J_DSP's crossovers and delays."
            Tool.EQ_WIZARD -> "EQ Wizard: import Combined sweeps (all bands playing) for both seats and both sides."
        }
        // The crossover corners the checks use depend on the tool.
        checks = null
        set = null
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
                // With tagging from names off, the band chosen under "New files" is what the user meant.
                band = t.band ?: if (d.autoTag) untaggedBand(d.band) else d.band, timingReference = d.reference(ch), validationOnly = d.validationOnly, calibrationDb = d.calibrationDb,
            )
            added++
        }
        status = "Added $added file(s). Check each row's tags."
        // Phase IRs from nothing but Combined sweeps can only be the Stereo IR.
        if (tool == Tool.PHASE_IRS && options.irMode == jdsp.irbuilder.IrMode.PER_BAND && measurements.isNotEmpty() && measurements.all { it.band == Band.COMBINED }) {
            updateOptions(options.copy(irMode = jdsp.irbuilder.IrMode.STEREO, maxShiftMs = maxOf(options.maxShiftMs, 5.0)))
            status = "Added $added Combined sweep(s): switched the convolver mode to Stereo (change it on the Checks or Design page)."
        }
        invalidate()
    }

    /**
     * The band for a file whose name doesn't say: the EQ Wizard and the Stereo IR work from
     * full-range sweeps, so there it's Combined; elsewhere the default from "New files".
     */
    private fun untaggedBand(default: Band): Band = when {
        tool == Tool.EQ_WIZARD -> Band.COMBINED
        tool == Tool.PHASE_IRS && options.irMode == jdsp.irbuilder.IrMode.STEREO -> Band.COMBINED
        else -> default
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
    private fun inputsChangedSince(snapshot: List<Measurement>, o: DesignOptions, a: AlignOptions = align, f: jdsp.irbuilder.FinderOptions = finder, e: jdsp.irbuilder.EqOptions = eq) =
        measurements.toList() != snapshot || effectiveOptions() != o || align != a || finder != f || eq != e

    /** The loaded set and its checks, reusing what's current. */
    /** Stereo mode reads only the Combined sweeps; band-solo files aren't used by it. */
    private fun inputs(snapshot: List<Measurement>, o: DesignOptions) =
        if (tool == Tool.PHASE_IRS && o.irMode == jdsp.irbuilder.IrMode.STEREO) snapshot.filter { it.band == Band.COMBINED } else snapshot

    private fun loadAndCheck(all: List<Measurement>, o: DesignOptions, scope: CheckScope = checkScope()): Pair<MeasurementSet, CheckResult> {
        val snapshot = inputs(all, o)
        val xo = scope.crossovers.toSet()
        val s = set?.takeIf { it.options == o && it.measurements == snapshot && it.crossovers == xo } ?: MeasurementSet.load(snapshot, o, xo)
        // The checks are cheap next to reading files; always run them for the current tool's scope.
        return s to Checks.run(s, scope)
    }

    /** What the current tool requires of the measurements. */
    fun checkScope(): CheckScope = when {
        tool == Tool.BAND_ALIGN -> CheckScope.bandAlign(align, measurements.toList())
        tool == Tool.CROSSOVER_FINDER -> CheckScope.crossoverFinder(finder, measurements.toList())
        tool == Tool.EQ_WIZARD -> CheckScope.eqWizard(measurements.toList())
        options.irMode == jdsp.irbuilder.IrMode.STEREO -> CheckScope.STEREO
        else -> CheckScope.phaseIrs(measurements.toList())
    }

    /**
     * Installs a freshly loaded set. Results computed from a different load (e.g. files re-exported
     * under the same names) are dropped, so nothing shown or exported comes from old data.
     */
    private fun publish(s: MeasurementSet, c: CheckResult) {
        if (s !== set) {
            alignment = null
            finding = null
            eqResult = null
            design = null
            stereoDesign = null
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
            val (s, c) = loadAndCheck(snapshot, o, CheckScope.bandAlign(a, snapshot))
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

    fun runFinder() = background("Searching crossovers, slopes, delays and polarity…") {
        val snapshot = measurements.toList()
        val o = effectiveOptions()
        val f = finder
        val (s, c, r) = withContext(Dispatchers.Default) {
            val (s, c) = loadAndCheck(snapshot, o, CheckScope.crossoverFinder(f, snapshot))
            Triple(s, c, if (c.canDesign) jdsp.irbuilder.CrossoverFinder.solve(s, f) else null)
        }
        if (inputsChangedSince(snapshot, o, f = f)) {
            status = "Inputs changed while searching; run the Crossover Finder again."
            return@background
        }
        publish(s, c)
        finding = r
        if (r == null) {
            status = "Crossover Finder blocked: ${c.errors} error(s) on the Checks page."
            step = Step.CHECKS
        } else {
            val n = r.filterChanges.size + r.timingChanges.size
            status = r.error ?: if (r.hasChanges) "Crossover Finder: $n J_DSP setting(s) to change." else "Crossover Finder: the current crossovers are already best; no changes."
        }
    }

    fun runEq() = background("Fitting PEQ bands…") {
        val snapshot = measurements.toList()
        val o = effectiveOptions()
        val e = eq
        val (s, c, r) = withContext(Dispatchers.Default) {
            val (s, c) = loadAndCheck(snapshot, o, CheckScope.eqWizard(snapshot))
            Triple(s, c, if (c.canDesign) jdsp.irbuilder.EqWizard.solve(s, e) else null)
        }
        if (inputsChangedSince(snapshot, o, e = e)) {
            status = "Inputs changed while fitting; run the EQ Wizard again."
            return@background
        }
        publish(s, c)
        eqResult = r
        eqExported = null
        if (r == null) {
            status = "EQ Wizard blocked: ${c.errors} error(s) on the Checks page."
            step = Step.CHECKS
        } else status = "EQ Wizard: ${r.bands.size} band(s). Preview, then export for J_DSP's Pre EQ."
    }

    fun importCurve(file: File) = background("Reading house curve…") {
        val pts = withContext(Dispatchers.IO) { jdsp.irbuilder.HouseCurve.parse(file.readText()) }
        updateEq(eq.copy(curve = pts, useCurve = true))
        status = "House curve: ${pts.size} points from ${file.name}."
    }

    fun saveCurve(file: File) = background("Saving house curve…") {
        val f = if (file.extension.isEmpty()) File(file.path + ".txt") else file
        withContext(Dispatchers.IO) { f.writeText(jdsp.irbuilder.HouseCurve.text(eq.curve.sortedBy { it.hz })) }
        status = "Saved ${f.name}"
        Dialogs.written(f)
    }

    fun exportEq(file: File) = background("Writing PEQ file…") {
        val r = eqResult ?: throw IllegalStateException("Run the EQ Wizard first")
        val f = if (file.extension.equals("txt", true)) file else File(file.path + ".txt")
        withContext(Dispatchers.IO) { f.writeText(jdsp.irbuilder.EqWizard.apoText(r)) }
        eqExported = f
        status = "Exported ${f.path}"
        Dialogs.written(f)
    }

    /** Reads each driver path's usable range from the sweeps and narrows the Finder's limits to it. */
    fun suggestFinderLimits() = background("Reading each driver's usable range…") {
        val snapshot = measurements.toList()
        val o = effectiveOptions()
        val f = finder
        val (s, c, est) = withContext(Dispatchers.Default) {
            val (s, c) = loadAndCheck(snapshot, o, CheckScope.crossoverFinder(f, snapshot))
            Triple(s, c, jdsp.irbuilder.BandEstimate.estimate(s, f.current))
        }
        if (inputsChangedSince(snapshot, o, f = f)) {
            status = "Inputs changed while reading; try again."
            return@background
        }
        publish(s, c)
        updateFinder(jdsp.irbuilder.BandEstimate.suggestLimits(f, est))
        bandRanges = est
        status = "Limits narrowed to the measured driver ranges. Check them against what your speakers can take."
    }

    fun runChecks(then: (() -> Unit)? = null) = background("Reading measurements and running checks…") {
        val snapshot = measurements.toList()
        val scope = checkScope()
        val o = effectiveOptions()
        val (s, c) = withContext(Dispatchers.Default) {
            val s = MeasurementSet.load(inputs(snapshot, o), o, scope.crossovers.toSet())
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
        val stereo = o.irMode == jdsp.irbuilder.IrMode.STEREO
        val (s, c, d) = withContext(Dispatchers.Default) {
            val (s, c) = loadAndCheck(snapshot, o, if (stereo) CheckScope.STEREO else CheckScope.phaseIrs(snapshot))
            // The phase IRs are designed on the measurements as taken: Band Align is a separate
            // tool, applied in J_DSP and re-measured before designing IRs.
            Triple(s, c, if (!c.canDesign) null else if (stereo) jdsp.irbuilder.StereoDesigner.design(s) else Designer.design(s))
        }
        if (inputsChangedSince(snapshot, o)) {
            status = "Inputs changed while calculating; result discarded. Calculate again."
            return@background
        }
        publish(s, c)
        design = d as? PhaseDesign
        stereoDesign = d as? jdsp.irbuilder.StereoDesign
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
        val d = design
        val sd = stereoDesign
        val zip = withContext(Dispatchers.IO) {
            when {
                sd != null -> Exporter.exportStereo(sd, dir)
                d != null -> Exporter.export(d, dir)
                else -> throw IllegalStateException("Calculate a design first")
            }
        }
        exportDir = dir
        exportedZip = zip
        status = "Exported ${zip.path}"
        Dialogs.written(zip)
    }

    fun saveProject(file: File) = background("Saving project…") {
        val f = if (file.extension.equals(Project.EXTENSION, true)) file else File(file.path + "." + Project.EXTENSION)
        withContext(Dispatchers.IO) { Project.save(Project(tool = tool, measurements = measurements.toList(), options = options, align = align, finder = finder, eq = eq), f) }
        projectFile = f
        status = "Saved ${f.name}"
        Dialogs.written(f)
    }

    fun openProject(file: File) = background("Opening project…") {
        val p = withContext(Dispatchers.IO) { Project.load(file) }
        measurements.clear()
        measurements.addAll(p.measurements)
        selected.clear()
        updateOptions(p.options)
        updateAlign(p.align)
        updateFinder(p.finder)
        updateEq(p.eq)
        tool = p.tool ?: Tool.PHASE_IRS
        step = Step.MEASUREMENTS
        projectFile = file
        status = "Opened ${file.name}: ${p.measurements.size} measurement(s)."
    }
}
