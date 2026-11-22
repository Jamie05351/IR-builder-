package jdsp.irbuilder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import jdsp.irbuilder.Band
import jdsp.irbuilder.Channel
import jdsp.irbuilder.Crossover
import jdsp.irbuilder.PhaseDesign
import jdsp.irbuilder.RegionMetrics
import jdsp.irbuilder.Seat
import java.awt.Desktop
import kotlin.math.log10
import kotlin.math.pow

/** What the preview shows: a seat average or one measured position. */
private sealed interface View {
    data class SeatAvg(val seat: Seat) : View
    data class Position(val seat: Seat, val position: String) : View
}

@Composable
fun PreviewScreen(state: AppState) {
    val d = state.design
    if (d == null) {
        Panel("Preview", accent = Jdsp.LightBlue) { Note("Calculate a design on the Design page first.") }
        return
    }
    var channel by remember { mutableStateOf(Channel.L) }
    var view by remember { mutableStateOf<View>(View.SeatAvg(Seat.DRIVER)) }
    val f = d.freqs
    val o = d.options
    val shades = Crossover.entries.map { xo ->
        val hz = xo.hz(o)
        Shade(hz / 2.0.pow(o.regionOctaves), hz * 2.0.pow(o.regionOctaves), (if (xo == Crossover.LOW_MID) Jdsp.LowBand else Jdsp.HighBand).copy(alpha = 0.08f))
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel("Select", accent = Jdsp.LightBlue) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Channel", color = Jdsp.TextDim, fontSize = 12.sp)
                Channel.entries.forEach { c -> Chip(c.label, channel == c, { channel = c }) }
                Text("   View", color = Jdsp.TextDim, fontSize = 12.sp)
                Seat.entries.forEach { s -> Chip("${s.label} average", view == View.SeatAvg(s), { view = View.SeatAvg(s) }, Jdsp.Purple) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                d.set.groups.filter { it.key.channel == channel && it.crossovers().isNotEmpty() }.forEach { g ->
                    val v = View.Position(g.key.seat, g.key.position)
                    Chip("${g.key.seat.label} · ${g.key.position}" + if (g.validationOnly) " (val.)" else "", view == v, { view = v }, if (g.validationOnly) Jdsp.Orange else Jdsp.LightBlue)
                }
            }
        }

        val curves: Triple<DoubleArray, DoubleArray, DoubleArray>? = when (val v = view) {
            is View.SeatAvg -> d.seatAverage(v.seat, channel)?.let { Triple(it.beforeDb, it.afterDb, it.coherentDb) }
            is View.Position -> d.set.groups.firstOrNull { it.key.seat == v.seat && it.key.position == v.position && it.key.channel == channel && it.crossovers().isNotEmpty() }?.let { g ->
                val b = g.branchSum()
                val a = d.predicted(g)
                Triple(DoubleArray(f.size) { db(b.abs(it)) }, DoubleArray(f.size) { db(a.abs(it)) }, DoubleArray(f.size) { i -> db(Band.BRANCHES.sumOf { g.branch(it).abs(i) }) })
            }
        }
        if (curves == null) {
            Panel("No data", accent = Jdsp.MRed) { Note("Nothing measured for this selection.") }
        } else {
            val (before, after, coherent) = curves
            val effBefore = DoubleArray(f.size) { before[it] - coherent[it] }
            val effAfter = DoubleArray(f.size) { after[it] - coherent[it] }
            Panel("Band summation", accent = Jdsp.MGreen) {
                Note("How completely the three software bands add, versus the coherent maximum (0 dB). Dips are crossover cancellations. Shaded: correction regions.")
                LinePlot(
                    listOf(Series("Before", f, effBefore, Jdsp.TextDim), Series("Predicted", f, effAfter, Jdsp.MGreen, width = 2.5f)),
                    yMin = -24.0, yMax = 2.0, yUnit = "dB", shades = shades,
                )
            }
            Panel("Summed level", accent = Jdsp.LightBlue) {
                val ref = before.average()
                fun rel(x: DoubleArray) = DoubleArray(x.size) { x[it] - ref }
                Note("Level of the band sum (relative). Where cancellation is removed the sum rises: re-check your PEQ there after installing.")
                LinePlot(
                    listOf(Series("Before", f, rel(before), Jdsp.TextDim), Series("Predicted", f, rel(after), Jdsp.LightBlue, width = 2.5f), Series("Coherent max", f, rel(coherent), Jdsp.Orange, dashed = true, width = 1.5f)),
                    yMin = -30.0, yMax = 15.0, yUnit = "dB", shades = shades,
                )
            }
        }
        Panel("Phase corrections (${channel.label})", accent = Jdsp.HighPink) {
            val ci = channel.ordinal
            val series = Band.BRANCHES.mapIndexed { bi, b -> Series(b.label, f, DoubleArray(f.size) { Math.toDegrees(d.designedPhase[ci][bi][it]) }, bandColor(b)) }
            val extent = series.maxOf { s -> s.y.maxOf { kotlin.math.abs(it) } }
            val lim = maxOf(90.0, kotlin.math.ceil(extent / 90.0) * 90.0)
            Note("Unwrapped phase each band's IR applies (common delay removed).")
            LinePlot(series, yMin = -lim, yMax = lim, yUnit = "°", shades = shades, height = 200.dp)
        }
        Panel("Impulses (${channel.label})", accent = Jdsp.Cyan) {
            val ci = channel.ordinal
            val span = ((o.maxShiftMs + 3) * o.sampleRate / 1000).toInt()
            val start = (o.latencyFrames - span).coerceAtLeast(0)
            val end = (o.latencyFrames + span).coerceAtMost(o.taps)
            val t = DoubleArray(end - start) { (start + it - o.latencyFrames) * 1000.0 / o.sampleRate }
            Note("Around the common delay (%d frames = %.1f ms, shown as 0 ms). Energy before 0 ms is pre-ringing.".format(o.latencyFrames, o.latencyMs))
            LinePlot(
                Band.BRANCHES.mapIndexed { bi, b -> Series(b.label, t, DoubleArray(t.size) { d.impulses[bi][ci][start + it].toDouble() }, bandColor(b), width = 1.5f) },
                yMin = -0.5, yMax = 1.05, yUnit = "", logX = false, xUnit = "ms", height = 200.dp,
            )
        }
        ResultsPanel(d)
    }
}

@Composable
private fun ResultsPanel(d: PhaseDesign) {
    Panel("Predicted results", accent = Jdsp.MGreen) {
        Note("Mean and worst summation efficiency in each correction region, before → predicted. Validation-only positions were not used for the fit.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Where" to 260, "Low/Mid mean" to 150, "Low/Mid worst" to 150, "Mid/High mean" to 150, "Mid/High worst" to 150, "Level change (max)" to 150).forEach { (t, w) -> Text(t, color = Jdsp.TextDim, fontSize = 12.sp, modifier = Modifier.width(w.dp)) }
        }
        d.report.seats.forEach { s -> ResultRow("${s.seat.label} ${s.channel.name} average (${s.positions})", s.regions, false, Jdsp.Purple) }
        d.report.positions.forEach { p -> ResultRow("${p.seat.label} · ${p.position} · ${p.channel.name}" + if (p.validationOnly) " (val.)" else "", p.regions, p.deteriorated, Color.White) }
        Text("Warnings", color = Jdsp.MidYellow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        d.report.warnings.forEach { Note("• $it") }
        Text("Per-band filters", color = Jdsp.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        d.report.bands.forEach { b ->
            Note("${b.channel.name} ${b.band.label}: max phase %.0f°, max shift %.2f ms%s, magnitude ripple %.2f dB, pre-ringing %.0f dB".format(
                b.maxPhaseDeg, b.maxGroupDelayChangeMs, if (b.shiftLimitScale < 1) " (limited to %.0f%%)".format(b.shiftLimitScale * 100) else "", b.magnitudeRippleDb, b.preRingingDb))
        }
        Note("Common gain %.2f dB. These are predictions from the measurements; re-measure both seats with the IRs loaded.".format(d.commonGainDb), Jdsp.Orange)
    }
}

@Composable
private fun ResultRow(label: String, regions: List<RegionMetrics>, bad: Boolean, color: Color) {
    // A crossover that wasn't corrected for this position (no data) shows as a dash.
    val lm = regions.firstOrNull { it.crossover == Crossover.LOW_MID.label }
    val mh = regions.firstOrNull { it.crossover == Crossover.MID_HIGH.label }
    fun cell(b: Double, a: Double) = "%.1f → %.1f dB".format(b, a)
    fun c(b: Double, a: Double) = if (a < b - 0.25) Jdsp.MRed else if (a > b + 0.25) Jdsp.MGreen else Color.White
    @Composable
    fun pair(r: RegionMetrics?, mean: Boolean) {
        if (r == null) Cell("not corrected", 150, Jdsp.TextDim)
        else if (mean) Cell(cell(r.beforeMeanDb, r.afterMeanDb), 150, c(r.beforeMeanDb, r.afterMeanDb))
        else Cell(cell(r.beforeWorstDb, r.afterWorstDb), 150, c(r.beforeWorstDb, r.afterWorstDb))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Cell(label + if (bad) "  ▲ worse" else "", 260, if (bad) Jdsp.MRed else color)
        pair(lm, true); pair(lm, false); pair(mh, true); pair(mh, false)
        Cell(listOf(lm, mh).joinToString(" / ") { r -> r?.let { "%+.1f".format(it.levelChangeMaxDb) } ?: "–" } + " dB", 150)
    }
}

@Composable
fun ExportScreen(state: AppState) {
    val d = state.design
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel("Export for J_DSP", accent = Jdsp.MGreen) {
            if (d == null) {
                Note("Calculate a design first.")
                return@Panel
            }
            val o = d.options
            Note("Writes low.wav, mid.wav and high.wav (stereo float32, ${o.sampleRate} Hz, ${o.taps} taps), manifest.json, report.json, README.txt and band-ir-package.zip — the package J_DSP's convolver page imports.", Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton("Choose folder and export…", {
                    Dialogs.chooseFolder("Export band IR package", state.exportDir)?.let(state::export)
                }, accent = Jdsp.MGreen, enabled = state.busy == null)
                state.exportedZip?.let { zip ->
                    GlassButton("Open folder", { runCatching { Desktop.getDesktop().open(zip.parentFile) } }, accent = Jdsp.LightBlue)
                    Text(zip.path, color = Jdsp.MGreen, fontSize = 13.sp)
                }
            }
        }
        if (d != null) {
            val o = d.options
            Panel("Load it in J_DSP", accent = Jdsp.LightBlue) {
                (listOfNotNull(
                    "Copy band-ir-package.zip to the head unit.",
                    "Convolver page → import the package. Check Low, Mid and High all load, then select Per-band.",
                    "Set band-mode gain to 0 dB to reproduce this prediction.",
                    "Keep the measured baseline: Low/Mid ${trimNumber(o.lowMidHz)} Hz, Mid/High ${trimNumber(o.midHighHz)} Hz, same slopes, PEQ, delays, polarity and gains. Mono bass and cross-channel routing off.",
                    "Expect about %.0f ms of added latency. Check video, phone and navigation audio.".format(o.jdspLatencyMs),
                    "Don't normalise or realign the WAVs individually.",
                    "Re-measure both seats with the IRs active, and re-check the PEQ where the summed level rose.",
                )).forEachIndexed { i, s -> Note("${i + 1}. $s") }
            }
        }
    }
}

internal fun db(x: Double) = 20 * log10(maxOf(x, 1e-12))
