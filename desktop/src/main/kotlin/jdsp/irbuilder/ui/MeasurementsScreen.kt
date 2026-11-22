package jdsp.irbuilder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jdsp.irbuilder.Band
import jdsp.irbuilder.Channel
import jdsp.irbuilder.Checks
import jdsp.irbuilder.Measurement
import jdsp.irbuilder.Seat
import java.io.File

@Composable
fun MeasurementsScreen(state: AppState) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ArchitecturePanel()
        DefaultsPanel(state)
        FilesPanel(state)
        CompletenessPanel(state)
    }
}

@Composable
private fun ArchitecturePanel() {
    var open by remember { mutableStateOf(false) }
    Panel("How J_DSP is measured", accent = Jdsp.Orange, trailing = { Chip(if (open) "Hide guide" else "Measurement guide", open, { open = !open }, Jdsp.Orange) }) {
        Note(
            "J_DSP sums its Low, Mid and High software bands back to two channels before the BMW amplifier splits them to " +
                "the physical speakers. So each band is measured as a software-band solo through the whole car: all speakers " +
                "connected, convolution bypassed, crossovers / PEQ / delays / gains exactly as they will be used.",
            Color.White,
        )
        if (open) {
            val steps = listOf(
                "Save the J_DSP configuration. Bypass the convolver. Keep crossovers, PEQ, delays, polarity and gains unchanged; turn mono bass and cross-channel routing OFF (and keep them off when using the IRs). Bypass dynamics or measure below their thresholds.",
                "For each mic position, solo Low, Mid and High on the left channel, then the right. Optionally also sweep all bands together per channel (tag it Combined) to verify the band sum.",
                "Timing reference: use one unchanged acoustic reference for every sweep of a channel (e.g. the opposite side's speaker with its bands unmuted, or an electrical loopback). Mute bands only on the measured side.",
                "Positions: start with the centre plus two nearby positions for each seat. 6 positions × 6 sweeps = 36 band-solo files. One extra position per seat marked validation-only checks the result on data the fit never saw.",
                "Export from REW: File › Export › Impulse response as text (best: keeps the time base and lets the app window out the cabin's reflections), or Measurement as text (frequency, SPL, phase), or impulse WAVs with the timing-reference offset preserved. Turn REW's IR window and normalisation off. Keep the .mdat originals.",
                "Name files like driver_pos2_L_mid.txt so seat, position, channel and band fill in automatically.",
            )
            steps.forEachIndexed { i, s -> Note("${i + 1}. $s") }
        }
    }
}

@Composable
private fun DefaultsPanel(state: AppState) {
    val d = state.defaults
    Panel("New files", accent = Jdsp.LightBlue) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            Labeled("Seat") { GlassDropdown(d.seat, Seat.entries, { it.label }, { state.defaults = d.copy(seat = it) }) }
            Labeled("Position") { GlassField(d.position, { state.defaults = d.copy(position = it) }) }
            Labeled("Channel") { GlassDropdown(d.channel, Channel.entries, { it.label }, { state.defaults = d.copy(channel = it) }, width = 90.dp) }
            Labeled("Software band") { GlassDropdown(d.band, Band.entries, { it.label }, { state.defaults = d.copy(band = it) }, accent = bandColor(d.band)) }
            Labeled("Left timing ref") { GlassField(d.referenceL, { state.defaults = d.copy(referenceL = it) }, width = 130.dp) }
            Labeled("Right timing ref") { GlassField(d.referenceR, { state.defaults = d.copy(referenceR = it) }, width = 130.dp) }
            Labeled("Calibration dB") { NumberField(d.calibrationDb, { state.defaults = d.copy(calibrationDb = it) }, width = 80.dp) }
            Labeled("Validation only") { GlassSwitch(d.validationOnly, { state.defaults = d.copy(validationOnly = it) }) }
            Labeled("Tag from names") { GlassSwitch(d.autoTag, { state.defaults = d.copy(autoTag = it) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassButton("Add files…", { Dialogs.openFiles("Add measurement files", listOf("txt", "csv", "frd", "wav"))?.let(state::addFiles) })
            GlassButton("Apply tags to selected", { state.applyDefaultsToSelected(true, true, true, true, true, true) }, enabled = state.selected.isNotEmpty())
            GlassButton("Remove selected", state::removeSelected, accent = Jdsp.MRed, enabled = state.selected.isNotEmpty())
            GlassButton("Clear all", state::clearAll, accent = Jdsp.MRed, enabled = state.measurements.isNotEmpty())
            Spacer(Modifier.width(24.dp))
            GlassButton("Open project…", { Dialogs.openFiles("Open project", listOf("jirb"), multiple = false)?.firstOrNull()?.let(state::openProject) }, accent = Jdsp.Purple)
            GlassButton("Save project…", { Dialogs.saveFile("Save project", state.projectFile?.name ?: "tune.jirb")?.let(state::saveProject) }, accent = Jdsp.Purple)
        }
        Note("Calibration 0 dB is right for REW SPL exports. Impulse WAVs need the offset that maps them to measured SPL; all bands at one position must share it.")
    }
}

@Composable
private fun FilesPanel(state: AppState) {
    Panel("Measurements (${state.measurements.size})", accent = Jdsp.LightBlue) {
        if (state.measurements.isEmpty()) {
            Note("No files yet. Add REW exports: impulse response as text (best), measurement as text (.txt/.csv/.frd), or mono impulse WAVs.")
            return@Panel
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Header("", 22); Header("File", 0); Header("Seat", 120); Header("Position", 110); Header("Ch", 80)
            Header("Band", 110); Header("Timing ref", 120); Header("Cal dB", 70); Header("Valid.", 60)
        }
        state.measurements.forEachIndexed { i, m -> MeasurementRow(state, i, m) }
    }
}

@Composable
private fun Header(text: String, width: Int) {
    val mod = if (width == 0) Modifier.width(260.dp) else Modifier.width(width.dp)
    Text(text, color = Jdsp.TextDim, fontSize = 12.sp, modifier = mod)
}

@Composable
private fun MeasurementRow(state: AppState, i: Int, m: Measurement) {
    val selected = i in state.selected
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (selected) Jdsp.LightBlue.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).glassBox(if (selected) Jdsp.LightBlue else Jdsp.TextDim, borderAlpha = if (selected) 1f else 0.5f).clickable { state.toggleSelect(i) },
            contentAlignment = Alignment.Center,
        ) { if (selected) Text("✓", color = Jdsp.LightBlue, fontSize = 12.sp) }
        Spacer(Modifier.width(4.dp))
        Text(File(m.path).name, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(260.dp).clickable { state.toggleSelect(i) })
        GlassDropdown(m.seat, Seat.entries, { it.label }, { state.update(i, m.copy(seat = it)) })
        GlassField(m.position, { state.update(i, m.copy(position = it)) })
        GlassDropdown(m.channel, Channel.entries, { it.name }, { state.update(i, m.copy(channel = it)) }, width = 80.dp)
        GlassDropdown(m.band, Band.entries, { it.label }, { state.update(i, m.copy(band = it)) }, width = 110.dp, accent = bandColor(m.band))
        GlassField(m.timingReference, { state.update(i, m.copy(timingReference = it)) }, width = 120.dp)
        NumberField(m.calibrationDb, { state.update(i, m.copy(calibrationDb = it)) }, width = 70.dp)
        GlassSwitch(m.validationOnly, { state.update(i, m.copy(validationOnly = it)) }, accent = Jdsp.Purple)
    }
}

@Composable
private fun CompletenessPanel(state: AppState) {
    val grid = Checks.completeness(state.measurements)
    if (grid.isEmpty()) return
    Panel("Completeness", accent = Jdsp.MGreen) {
        Note(
            if (state.tool == jdsp.irbuilder.Tool.PHASE_IRS && state.options.irMode == jdsp.irbuilder.IrMode.STEREO) "Stereo IR mode uses one Combined sweep (all bands playing, full range) per channel per position. Band-solo sweeps aren't used in this mode."
            else if (state.tool == jdsp.irbuilder.Tool.EQ_WIZARD) "The EQ Wizard uses each side's Combined sweeps (all bands playing, wide range) at both seats. Without Combined sweeps it sums each position's Low/Mid/High solos. Measure through the tune as it will be used: the bands it proposes go on top."
            else if (state.tool == jdsp.irbuilder.Tool.CROSSOVER_FINDER) "The Crossover Finder needs the bands of each crossover it searches (Low + Mid, Mid + High), swept through J_DSP's current crossovers. Missing seats or sides are skipped with a warning. Band-limited sweeps are fine: candidates that would need data beyond a sweep are left out."
            else if (state.tool == jdsp.irbuilder.Tool.BAND_ALIGN) "Band Align needs the bands of the pairs switched on (Low + Mid, Mid + High). Missing seats or sides are skipped with a warning. Band-limited sweeps are fine if each band covers an octave either side of its crossovers."
            else "Phase IRs correct each crossover where both of its bands were measured (Low+Mid for Low/Mid, Mid+High for Mid/High), per channel. Anything without data is left as a pure delay. Combined is optional (validates the band sum). Each band needs to cover an octave either side of the crossovers it's used for.",
        )
        val rows = grid.keys.map { it.seat to it.position }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("", modifier = Modifier.width(200.dp))
            for (ch in Channel.entries) for (b in Band.entries) {
                Text("${ch.name} ${b.label}", color = bandColor(b), fontSize = 11.sp, modifier = Modifier.width(74.dp))
            }
        }
        rows.forEach { (seat, pos) ->
            val validation = state.measurements.any { it.seat == seat && it.position.trim() == pos && it.validationOnly }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${seat.label} · $pos" + if (validation) "  (validation)" else "", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(200.dp))
                for (ch in Channel.entries) for (b in Band.entries) {
                    val have = grid[jdsp.irbuilder.GroupKey(seat, pos, ch)]?.contains(b) == true
                    val required = b in state.checkScope().requiredBands
                    val color = when {
                        have -> Jdsp.MGreen
                        required -> Jdsp.MRed
                        else -> Jdsp.TextDim.copy(alpha = 0.4f)
                    }
                    Box(Modifier.width(74.dp).height(24.dp).glassBox(color, borderAlpha = if (have) 0.9f else 0.5f), contentAlignment = Alignment.Center) {
                        Text(if (have) "✓" else if (required) "missing" else "–", color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
