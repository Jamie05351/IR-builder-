package jdsp.irbuilder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jdsp.irbuilder.Channel
import jdsp.irbuilder.Exporter
import jdsp.irbuilder.Seat
import jdsp.irbuilder.StereoDesign
import kotlin.math.abs
import kotlin.math.ceil

/** A seat average, or one measured position. */
private sealed interface StereoView {
    data class SeatAvg(val seat: Seat) : StereoView
    data class Position(val seat: Seat, val position: String) : StereoView
}

@Composable
fun StereoPreview(d: StereoDesign) {
    var channel by remember { mutableStateOf(Channel.L) }
    var view by remember { mutableStateOf<StereoView>(StereoView.SeatAvg(Seat.DRIVER)) }
    val f = d.freqs
    val o = d.options
    val shades = listOf(Shade(o.stereoMinHz, o.stereoMaxHz, Jdsp.Cyan.copy(alpha = 0.06f)))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel("Select", accent = Jdsp.LightBlue) {
            WrapRow(8.dp, Alignment.CenterVertically) {
                Text("Channel", color = Jdsp.TextDim, fontSize = 12.sp)
                Channel.entries.forEach { c -> Chip(c.label, channel == c, { channel = c }) }
                Text("   View", color = Jdsp.TextDim, fontSize = 12.sp)
                Seat.entries.forEach { s -> Chip("${s.label} average", view == StereoView.SeatAvg(s), { view = StereoView.SeatAvg(s) }, Jdsp.Purple) }
            }
            WrapRow(8.dp) {
                d.groups(channel).forEach { g ->
                    val v = StereoView.Position(g.key.seat, g.key.position)
                    Chip("${g.key.seat.label} · ${g.key.position}" + if (g.validationOnly) " (val.)" else "", view == v, { view = v }, if (g.validationOnly) Jdsp.Orange else Jdsp.LightBlue)
                }
            }
        }
        val groups = when (val v = view) {
            is StereoView.SeatAvg -> d.groups(channel, includeValidation = false).filter { it.key.seat == v.seat }
            is StereoView.Position -> d.groups(channel).filter { it.key.seat == v.seat && it.key.position == v.position }
        }
        if (groups.isEmpty()) {
            Panel("No data", accent = Jdsp.MRed) { Note("No Combined sweep for this selection: this channel's IR is a pure delay.") }
        } else {
            fun mean(curves: List<DoubleArray>) = DoubleArray(f.size) { i -> curves.sumOf { it[i] } / curves.size }
            val beforePh = mean(groups.map { d.excessPhaseDeg(d.combined(it), d.delayHint(it)) })
            val afterPh = mean(groups.map { d.excessPhaseDeg(d.predicted(it), d.delayHint(it)) })
            val beforeGd = mean(groups.map { d.groupDelayMs(d.combined(it), d.delayHint(it)) })
            val afterGd = mean(groups.map { d.groupDelayMs(d.predicted(it), d.delayHint(it)) })
            val inRange = f.indices.filter { f[it] in o.stereoMinHz..o.stereoMaxHz }
            val phLim = maxOf(90.0, ceil(inRange.maxOf { maxOf(abs(beforePh[it]), abs(afterPh[it])) } / 90.0) * 90.0)
            val gdLim = maxOf(2.0, ceil(inRange.maxOf { maxOf(abs(beforeGd[it]), abs(afterGd[it])) }))
            Panel("Summed phase vs a pure delay", accent = Jdsp.MGreen) {
                Note("Phase of the full band sum with its arrival time and the best straight line removed. Flat at 0° means every frequency arrives together. Shaded: the stereo range.")
                LinePlot(listOf(Series("Before", f, beforePh, Jdsp.TextDim), Series("Predicted", f, afterPh, Jdsp.MGreen, width = 2.5f)), -phLim, phLim, "°", shades = shades)
            }
            Panel("Group delay", accent = Jdsp.LightBlue) {
                Note("How late each frequency arrives relative to the rest. Crossovers show as humps; linearised, the line is flat.")
                LinePlot(listOf(Series("Before", f, beforeGd, Jdsp.TextDim), Series("Predicted", f, afterGd, Jdsp.LightBlue, width = 2.5f)), -gdLim, gdLim, "ms", shades = shades)
            }
        }
        Panel("Correction (${channel.label})", accent = Jdsp.HighPink) {
            val c = DoubleArray(f.size) { Math.toDegrees(d.correction[channel.ordinal][it]) }
            val lim = maxOf(90.0, ceil(c.maxOf { abs(it) } / 90.0) * 90.0)
            LinePlot(listOf(Series("IR phase", f, c, Jdsp.HighPink)), -lim, lim, "°", shades = shades, height = 200.dp)
        }
        Panel("Impulse (${channel.label})", accent = Jdsp.Cyan) {
            val span = ((o.maxShiftMs + 3) * o.sampleRate / 1000).toInt()
            val start = (o.latencyFrames - span).coerceAtLeast(0)
            val end = (o.latencyFrames + span).coerceAtMost(o.taps)
            val t = DoubleArray(end - start) { (start + it - o.latencyFrames) * 1000.0 / o.sampleRate }
            Note("Around the common delay (%d frames = %.1f ms, shown as 0 ms). Energy before 0 ms is pre-ringing.".format(o.latencyFrames, o.latencyMs))
            LinePlot(listOf(Series(channel.label, t, DoubleArray(t.size) { d.impulses[channel.ordinal][start + it].toDouble() }, Jdsp.Cyan, width = 1.5f)),
                -0.5, 1.05, "", logX = false, xUnit = "ms", height = 200.dp)
        }
        Panel("Predicted results", accent = Jdsp.MGreen) {
            Note("RMS phase deviation from a pure delay over the stereo range, and the spread of group delay, before → predicted.")
            fun color(b: Double, a: Double) = if (a > b + 1e-9) Jdsp.MRed else Jdsp.MGreen
            Table {
            TableRow {
                listOf("Where" to 300, "Phase deviation" to 200, "Group-delay spread" to 200).forEach { (t, w) -> Text(t, color = Jdsp.TextDim, fontSize = 12.sp, modifier = Modifier.width(w.dp)) }
            }
            d.report.seats.forEach { s ->
                TableRow {
                    Cell("${s.seat.label} ${s.channel.name} average (${s.positions})", 300, Jdsp.Purple)
                    Cell("%.0f° → %.0f°".format(s.beforeRmsDeg, s.afterRmsDeg), 200, color(s.beforeRmsDeg, s.afterRmsDeg))
                    Cell("%.2f → %.2f ms".format(s.beforeGroupDelaySpreadMs, s.afterGroupDelaySpreadMs), 200, color(s.beforeGroupDelaySpreadMs, s.afterGroupDelaySpreadMs))
                }
            }
            d.report.positions.forEach { p ->
                TableRow {
                    Cell("${p.seat.label} · ${p.position} · ${p.channel.name}" + (if (p.validationOnly) " (val.)" else "") + if (p.deteriorated) "  ▲ worse" else "", 300, if (p.deteriorated) Jdsp.MRed else Color.White)
                    Cell("%.0f° → %.0f°".format(p.beforeRmsDeg, p.afterRmsDeg), 200, color(p.beforeRmsDeg, p.afterRmsDeg))
                    Cell("%.2f → %.2f ms".format(p.beforeGroupDelaySpreadMs, p.afterGroupDelaySpreadMs), 200, color(p.beforeGroupDelaySpreadMs, p.afterGroupDelaySpreadMs))
                }
            }
            }
            Text("Warnings", color = Jdsp.MidYellow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            d.report.warnings.forEach { Note("• $it") }
            d.report.channels.forEach { c ->
                Note("${c.channel.name}: " + if (!c.corrected) "not corrected (pure delay)" else "max phase %.0f°, max shift %.2f ms%s, magnitude ripple %.2f dB, pre-ringing %.0f dB".format(
                    c.maxPhaseDeg, c.maxGroupDelayChangeMs, if (c.shiftLimitScale < 1) " (limited to %.0f%%)".format(c.shiftLimitScale * 100) else "", c.magnitudeRippleDb, c.preRingingDb))
            }
        }
    }
}

@Composable
fun StereoExport(state: AppState, d: StereoDesign) {
    val o = d.options
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel("Export for J_DSP", accent = Jdsp.MGreen) {
            Note("Writes ${Exporter.STEREO_FILE} (stereo float32, ${o.sampleRate} Hz, ${o.taps} taps), report.json and README.txt, for the convolver page's Stereo slot.", Color.White)
            WrapRow(12.dp, Alignment.CenterVertically) {
                GlassButton("Choose folder and export…", { Dialogs.chooseFolder("Export stereo IR", state.exportDir) { state.export(it) } }, accent = Jdsp.MGreen, enabled = state.busy == null)
                state.exportedZip?.let { file ->
                    GlassButton(Dialogs.REVEAL_LABEL, { Dialogs.reveal(file) }, accent = Jdsp.LightBlue)
                    Text(file.path, color = Jdsp.MGreen, fontSize = 13.sp)
                }
            }
        }
        Panel("Load it in J_DSP", accent = Jdsp.LightBlue) {
            listOf(
                "Copy ${Exporter.STEREO_FILE} to the head unit.",
                "Convolver page → tap the Stereo slot → Load WAV… and pick the file. Select Stereo mode.",
                "Set mode gain to 0 dB to reproduce this prediction.",
                "Keep the measured baseline: crossovers, PEQ, delays, polarity and gains as swept.",
                "Expect about %.0f ms of added latency. Check video, phone and navigation audio.".format(o.jdspLatencyMs),
                "Re-measure both seats with the IR active.",
            ).forEachIndexed { i, s -> Note("${i + 1}. $s") }
        }
    }
}
