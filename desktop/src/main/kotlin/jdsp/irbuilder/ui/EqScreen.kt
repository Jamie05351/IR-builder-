package jdsp.irbuilder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jdsp.irbuilder.EqOptions
import jdsp.irbuilder.EqSide
import jdsp.irbuilder.EqSideResult
import jdsp.irbuilder.EqWizard
import jdsp.irbuilder.Seat
import java.awt.Desktop
import kotlin.math.ceil
import kotlin.math.floor

@Composable
fun EqScreen(state: AppState) {
    val e = state.eq
    fun set(n: EqOptions) = state.updateEq(n)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel("EQ Wizard", accent = Jdsp.Cyan) {
            Note(
                "Fits J_DSP Parametric EQ bands (peaking) so the system's response, averaged over both seats, follows a house curve. " +
                    "It works on what you measured through the current tune, so the bands are corrections on top of your EQ: import them " +
                    "into Pre EQ with Append. Cuts are free; boosts are capped and never chase a narrow dip, which is cancellation EQ " +
                    "can't fill. Use it last, on the finished crossovers, delays and IRs.",
                Color.White,
            )
        }
        Panel("Target and limits", accent = Jdsp.LightBlue) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                Labeled("Fit from (Hz)") { NumberField(e.minHz, { set(e.copy(minHz = it)) }, width = 90.dp) }
                Labeled("Fit to (Hz)") { NumberField(e.maxHz, { set(e.copy(maxHz = it)) }, width = 90.dp) }
                Labeled("Bass lift (dB)") { NumberField(e.bassLiftDb, { set(e.copy(bassLiftDb = it)) }, width = 90.dp, accent = Jdsp.LowBand) }
                Labeled("Treble tilt 1–10 kHz (dB)") { NumberField(e.trebleTiltDb, { set(e.copy(trebleTiltDb = it)) }, width = 90.dp, accent = Jdsp.HighBand) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                Labeled("Bands per side") { NumberField(e.maxBands.toDouble(), { set(e.copy(maxBands = it.toInt())) }, width = 80.dp) }
                Labeled("Max boost (dB)") { NumberField(e.maxBoostDb, { set(e.copy(maxBoostDb = it)) }, width = 80.dp) }
                Labeled("Max cut (dB)") { NumberField(e.maxCutDb, { set(e.copy(maxCutDb = it)) }, width = 80.dp) }
                Labeled("Max Q (cuts)") { NumberField(e.maxQ, { set(e.copy(maxQ = it)) }, width = 80.dp) }
                Labeled("Same EQ both sides") { GlassSwitch(e.linked, { set(e.copy(linked = it)) }) }
            }
            Note("House curve: a low-shelf lift centred near 100 Hz and a straight tilt from 1 kHz to 10 kHz, levelled to your response between 300 Hz and 3 kHz. J_DSP's Pre EQ bank holds 16 bands in all: per side that's 8 each, minus any you already use.")
            state.eqError?.let { Note(it, Jdsp.MRed) }
            GlassButton("Fit EQ", { state.runEq() }, accent = Jdsp.MGreen, enabled = state.eqError == null && state.measurements.isNotEmpty() && state.busy == null)
        }
        val r = state.eqResult ?: return@Column
        r.warnings.forEach { Note("• $it", Jdsp.MidYellow) }
        Panel("Export for J_DSP", accent = Jdsp.MGreen) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton("Save PEQ file…", { Dialogs.saveFile("Save PEQ file", EqWizard.EXPORT_FILE)?.let(state::exportEq) }, accent = Jdsp.MGreen, enabled = state.busy == null)
                state.eqExported?.let { file ->
                    GlassButton("Open folder", { runCatching { Desktop.getDesktop().open(file.parentFile) } }, accent = Jdsp.LightBlue)
                    Text(file.path, color = Jdsp.MGreen, fontSize = 13.sp)
                }
            }
            listOf(
                "Copy the file to the head unit. Keep \"input\" in its name: J_DSP routes it to Pre EQ.",
                "Parametric EQ → Import → pick the file → Append (adds these bands on top of the ones you measured with).",
                if (r.preampDb < 0) "The bands boost up to %.1f dB somewhere: lower Pre EQ's preamp by that much (Append leaves the preamp alone).".format(-r.preampDb)
                else "The bands only cut: no preamp change needed.",
                "Re-measure both seats and run the wizard again; it should find little left to do.",
            ).forEachIndexed { i, s -> Note("${i + 1}. $s") }
        }
        r.sides.forEach { SidePanel(it, r.freqs, e) }
    }
}

@Composable
private fun SidePanel(s: EqSideResult, f: DoubleArray, e: EqOptions) {
    val accent = when (s.side) { EqSide.LEFT -> Jdsp.LightBlue; EqSide.RIGHT -> Jdsp.HighPink; EqSide.BOTH -> Jdsp.Cyan }
    val name = when (s.side) { EqSide.LEFT -> "Left side"; EqSide.RIGHT -> "Right side"; EqSide.BOTH -> "Both sides (L+R)" }
    Panel(name, accent = accent) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Badge("RMS from target %.1f → %.1f dB".format(s.rmsBeforeDb, s.rmsAfterDb), if (s.rmsAfterDb < s.rmsBeforeDb) Jdsp.MGreen else Jdsp.MidYellow)
            Text("${s.bands.size} band(s)", color = Jdsp.TextDim, fontSize = 13.sp)
        }
        val idx = f.indices.filter { f[it] in e.minHz / 1.5..e.maxHz * 1.5 }
        if (idx.size > 2) {
            val x = DoubleArray(idx.size) { f[idx[it]] }
            fun ser(label: String, y: DoubleArray, c: Color, w: Float = 2f, dashed: Boolean = false) = Series(label, x, DoubleArray(idx.size) { y[idx[it]] }, c, dashed = dashed, width = w)
            val series = buildList {
                add(ser("Target", s.targetDb, Jdsp.Cyan.copy(alpha = 0.8f), 1.5f))
                add(ser("Now (both seats)", s.measuredDb, Jdsp.TextDim, 1.5f))
                add(ser("With EQ", DoubleArray(f.size) { s.measuredDb[it] + s.eqDb[it] }, accent, 2.5f))
                s.seatsAfterDb[Seat.PASSENGER]?.let { add(ser("Passenger with EQ", it, Jdsp.Purple, 1.5f, dashed = true)) }
            }
            val ys = series.flatMap { it.y.asList() }.filter { it.isFinite() }
            val top = ceil((ys.max() + 3) / 5) * 5
            val bottom = maxOf(floor((ys.min() - 3) / 5) * 5, top - 50)
            LinePlot(series, bottom, top, "dB", height = 260.dp, accent = accent)
            LinePlot(listOf(ser("EQ", s.eqDb, accent, 2.5f), ser("Wanted", s.wantedDb, Jdsp.TextDim, 1.5f, dashed = true)), -14.0, 8.0, "dB", height = 160.dp, accent = accent)
            Note("Lower plot: the EQ's own curve, and what it was asked to do (target − response, boosts held back where the octave-wide response doesn't lack it).")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("#" to 30, "Type" to 50, "Side" to 50, "Frequency" to 110, "Gain" to 90, "Q" to 70).forEach { (t, w) -> Text(t, color = Jdsp.TextDim, fontSize = 12.sp, modifier = Modifier.width(w.dp)) }
        }
        s.bands.forEachIndexed { i, b ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell("${i + 1}", 30); Cell("PK", 50); Cell(b.side.label, 50)
                Cell("${trimNumber(b.hz)} Hz", 110, Color.White)
                Cell("%+.1f dB".format(b.gainDb), 90, if (b.gainDb > 0) Jdsp.MidYellow else Jdsp.MGreen)
                Cell(trimNumber(b.q), 70)
            }
        }
        Text("", fontSize = 4.sp, fontWeight = FontWeight.Normal)
    }
}
