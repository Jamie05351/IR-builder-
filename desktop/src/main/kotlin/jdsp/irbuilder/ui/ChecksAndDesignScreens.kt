package jdsp.irbuilder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jdsp.irbuilder.DesignOptions
import jdsp.irbuilder.Severity
import kotlin.math.roundToInt

@Composable
fun ChecksScreen(state: AppState) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel("Measurement setup", accent = Jdsp.Cyan) {
            val o = state.options
            fun set(n: DesignOptions) = state.updateOptions(n)
            Note("Used by both tools. Enter the crossover corners J_DSP had during the sweeps: Band Align scores around them and the Phase IRs correct around them.")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                Labeled("Low/Mid crossover (Hz)") { NumberField(o.lowMidHz, { set(o.copy(lowMidHz = it)) }, accent = Jdsp.LowBand) }
                Labeled("Mid/High crossover (Hz)") { NumberField(o.midHighHz, { set(o.copy(midHighHz = it)) }, accent = Jdsp.HighBand) }
                Labeled("Sample rate (impulse WAVs)") {
                    GlassDropdown(o.sampleRate, listOf(48000, 44100), { "$it Hz" }, { rate ->
                        val taps = if (rate == 44100) minOf(o.taps, 32768) else o.taps
                        set(o.copy(sampleRate = rate, taps = taps, latencyFrames = if (taps != o.taps) taps / 2 else o.latencyFrames))
                    })
                }
            }
            Text("Smoothing", color = Jdsp.TextDim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                jdsp.irbuilder.MEASUREMENT_SMOOTHING_CHOICES.forEach { n ->
                    Chip(if (n == 0) "None" else "1/$n oct", o.measurementSmoothing == n, { set(o.copy(measurementSmoothing = n)) }, Jdsp.Cyan)
                }
            }
            Note("Complex (vector) smoothing applied to every file as it's read, so the checks, Band Align, the phase solver and the plots all use the same data. Each file's arrival time is taken out first and put back after, so phase stays exact. 1/24 suits most car measurements; 1/12 for noisy ones. Changing any of these re-reads the files.")
            runCatching { o.validateMeasurement() }.exceptionOrNull()?.message?.let { Note(it, Jdsp.MRed) }
        }
        Panel("Measurement checks", accent = Jdsp.MGreen, trailing = {
            GlassButton("Run checks", { state.runChecks() }, accent = Jdsp.MGreen, enabled = state.measurements.isNotEmpty() && state.busy == null)
        }) {
            Note("Reads every file onto the design grid and checks timing references, completeness, levels, band tags, the combined-vs-sum model and whether positions agree on each crossover's band phase. These checks rely on your tags; software cannot confirm the physical timing reference or routing.")
            val c = state.checks
            if (c == null) {
                Note("Not run yet for the current inputs.", Jdsp.Orange)
                return@Panel
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Badge("${c.errors} errors", if (c.errors > 0) Jdsp.MRed else Jdsp.MGreen)
                Badge("${c.warnings} warnings", if (c.warnings > 0) Jdsp.MidYellow else Jdsp.MGreen)
                val ready = if (state.tool == jdsp.irbuilder.Tool.BAND_ALIGN) "Ready for Band Align" else "Ready to design"
                Badge(if (c.canDesign) ready else "Fix errors first", if (c.canDesign) Jdsp.MGreen else Jdsp.MRed)
            }
            c.items.forEach { item ->
                val color = when (item.severity) {
                    Severity.ERROR -> Jdsp.MRed
                    Severity.WARNING -> Jdsp.MidYellow
                    Severity.INFO -> Jdsp.LightBlue
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(76.dp).glassBox(color, borderAlpha = 0.8f), contentAlignment = Alignment.Center) {
                        Text(item.severity.name, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding4())
                    }
                    Column {
                        Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(item.detail, color = Jdsp.TextDim, fontSize = 12.sp)
                    }
                }
            }
        }
        val phases = state.checks?.crossoverPhases.orEmpty()
        if (phases.isNotEmpty()) {
            Panel("Band phase at each crossover", accent = Jdsp.Purple) {
                Note("arg(upper band / lower band) averaged over ±1/6 octave at the crossover. 0° means the bands already add coherently. A large spread means positions disagree and a single fixed correction is a compromise.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Seat" to 120, "Channel" to 80, "Crossover" to 100, "Offset" to 90, "Spread" to 90, "Positions" to 80).forEach { (t, w) ->
                        Text(t, color = Jdsp.TextDim, fontSize = 12.sp, modifier = Modifier.width(w.dp))
                    }
                }
                phases.forEach { p ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Cell(p.seat.label, 120); Cell(p.channel.label, 80); Cell(p.crossover.label, 100)
                        Cell("%+.0f°".format(p.meanDeg), 90, if (kotlin.math.abs(p.meanDeg) > 60) Jdsp.MidYellow else Color.White)
                        Cell(if (p.positions > 1) "%.0f°".format(p.spreadDeg) else "–", 90, if (p.spreadDeg > 60) Jdsp.MidYellow else Color.White)
                        Cell(p.positions.toString(), 80)
                    }
                }
            }
        }
    }
}

private fun Modifier.padding4() = padding(horizontal = 6.dp, vertical = 4.dp)

@Composable
fun Badge(text: String, color: Color) {
    Box(Modifier.glassBox(color)) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding4())
    }
}

@Composable
fun Cell(text: String, width: Int, color: Color = Color.White) = Text(text, color = color, fontSize = 13.sp, modifier = Modifier.width(width.dp))

@Composable
fun DesignScreen(state: AppState) {
    val o = state.options
    fun set(n: DesignOptions) = state.updateOptions(n)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val stereo = o.irMode == jdsp.irbuilder.IrMode.STEREO
        Panel("Convolver mode", accent = Jdsp.HighPink) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                jdsp.irbuilder.IrMode.entries.forEach { m ->
                    Chip(m.label, o.irMode == m, {
                        // Linearising the summed low-frequency phase needs more shift than band alignment.
                        set(if (m == jdsp.irbuilder.IrMode.STEREO) o.copy(irMode = m, maxShiftMs = maxOf(o.maxShiftMs, 5.0)) else o.copy(irMode = m))
                    }, Jdsp.HighPink)
                }
            }
            Note(
                if (stereo) "One stereo IR for J_DSP's Stereo slot, after the band sum. It removes the phase rotation of the summed response " +
                    "(crossovers and speakers) toward one arrival time at both seats, phase-only. It acts on every band alike, so it can't " +
                    "fix cancellation between bands at a crossover; use Band Align or Per-band for that. Uses the Combined sweeps " +
                    "(all bands playing), one per channel per position."
                else "Phase-only crossover alignment. Each band's IR has unity magnitude: tone stays with your PEQ. Near the Low/Mid and " +
                    "Mid/High crossovers, each band's phase is adjusted so the software bands add coherently at both seats. Outside the " +
                    "correction regions the IRs are a pure common delay.",
                Color.White,
            )
        }
        if (stereo) Panel("Stereo range", accent = Jdsp.Cyan) {
            Note("The summed phase is linearised between these frequencies; the correction fades out beyond them. Low frequencies need long shifts: keep Max shift at least 4-5 ms for a 150 Hz crossover.")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Labeled("From (Hz)") { NumberField(o.stereoMinHz, { set(o.copy(stereoMinHz = it)) }, accent = Jdsp.Cyan) }
                Labeled("To (Hz)") { NumberField(o.stereoMaxHz, { set(o.copy(stereoMaxHz = it)) }, accent = Jdsp.Cyan) }
            }
        }
        if (!stereo) Panel("Existing J_DSP crossovers", accent = Jdsp.LowBand) {
            Note("Enter the corners J_DSP used for the sweeps. They place the correction regions and are written into the package; J_DSP asks you to acknowledge a mismatch with its own settings (±1 Hz). This program never changes the crossovers.")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Labeled("Low/Mid (Hz)") { NumberField(o.lowMidHz, { set(o.copy(lowMidHz = it)) }, accent = Jdsp.LowBand) }
                Labeled("Mid/High (Hz)") { NumberField(o.midHighHz, { set(o.copy(midHighHz = it)) }, accent = Jdsp.HighBand) }
            }
        }
        Panel("Correction limits", accent = Jdsp.Orange) {
            if (!stereo) SliderRow("Region width", o.regionOctaves, 0.25, 2.0, "±%.2f oct".format(o.regionOctaves), "Fully active this far either side of each crossover.") { set(o.copy(regionOctaves = it)) }
            SliderRow("Fade width", o.fadeOctaves, 0.1, 1.5, "%.2f oct".format(o.fadeOctaves), "Correction fades to zero over this further span.") { set(o.copy(fadeOctaves = it)) }
            SliderRow("Max shift", o.maxShiftMs, 0.1, 10.0, "%.2f ms".format(o.maxShiftMs), "Largest group-delay change any band may receive. Limits pre-ringing.") { set(o.copy(maxShiftMs = it)) }
            SliderRow("Smoothing", o.smoothingOctaves, 0.0, 1.0, if (o.smoothingOctaves == 0.0) "off" else "1/%.0f oct".format(1 / o.smoothingOctaves), "Avoids fitting narrow position-specific detail.") { set(o.copy(smoothingOctaves = it)) }
            if (!stereo) SliderRow("Regularisation", o.regularization, 0.0, 1.0, "%.2f".format(o.regularization), "Pull toward no change. Higher is more conservative.") { set(o.copy(regularization = it)) }
        }
        Panel("Seat weighting", accent = Jdsp.Purple) {
            val share = o.driverWeight / (o.driverWeight + o.passengerWeight)
            SliderRow("Driver share", share, 0.05, 0.95, "Driver %.0f%% · Passenger %.0f%%".format(share * 100, (1 - share) * 100), "Each seat's positions share that seat's weight, however many positions it has.") {
                set(o.copy(driverWeight = it, passengerWeight = 1 - it))
            }
        }
        Panel("Filter length and latency", accent = Jdsp.Cyan) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                Labeled("Sample rate") { GlassDropdown(o.sampleRate, listOf(48000, 44100), { "$it Hz" }, { rate ->
                    val taps = if (rate == 44100) minOf(o.taps, 32768) else o.taps
                    set(o.copy(sampleRate = rate, taps = taps, latencyFrames = if (taps != o.taps) taps / 2 else o.latencyFrames))
                }) }
                Labeled("Taps") { GlassDropdown(o.taps, listOf(4096, 8192, 16384, 32768, 65536).filter { o.sampleRate == 48000 || it <= 32768 }, { "$it" }, { set(o.copy(taps = it, latencyFrames = it / 2)) }) }
                Labeled("Common delay (frames)") { NumberField(o.latencyFrames.toDouble(), { set(o.copy(latencyFrames = it.roundToInt())) }) }
                Labeled("Latency in J_DSP") { Text("≈ %.0f ms".format(o.jdspLatencyMs), color = Jdsp.Cyan, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            }
            Note("Default 16384 taps with an 8192-frame common delay: ≈192 ms in J_DSP at 48 kHz (IR delay + 1024-frame partition). Shorter filters cut latency but limit how much phase can be moved at the Low/Mid crossover.")
        }
        state.optionsError?.let { Note("Settings: $it", Jdsp.MRed) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassButton("Calculate and preview", { state.calculate() }, accent = Jdsp.MGreen, enabled = state.optionsError == null && state.measurements.isNotEmpty() && state.busy == null)
            GlassButton("Reset to defaults", { set(DesignOptions(lowMidHz = o.lowMidHz, midHighHz = o.midHighHz)) }, accent = Jdsp.TextDim)
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Double, min: Double, max: Double, readout: String, hint: String, onChange: (Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(140.dp).glassBox(Jdsp.Orange, borderAlpha = 0.6f)) {
            Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding4())
        }
        Slider(
            value = value.toFloat().coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = min.toFloat()..max.toFloat(),
            modifier = Modifier.width(360.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Jdsp.Orange, inactiveTrackColor = Color(0xFF2A3038)),
        )
        Box(Modifier.width(220.dp).glassBox(Jdsp.Orange)) { Text(readout, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding4()) }
        Text(hint, color = Jdsp.TextDim, fontSize = 12.sp)
    }
}
