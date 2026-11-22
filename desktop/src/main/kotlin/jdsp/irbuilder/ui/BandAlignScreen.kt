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
import jdsp.irbuilder.AlignResult
import jdsp.irbuilder.Band
import jdsp.irbuilder.BandAlign
import jdsp.irbuilder.BandSetting
import jdsp.irbuilder.Channel
import jdsp.irbuilder.Seat
import kotlin.math.ceil
import kotlin.math.floor

@Composable
fun BandAlignScreen(state: AppState) {
    val a = state.align
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel("Band Align", accent = Jdsp.Orange) {
            Note(
                "Finds each side's Low delay and polarity against Mid, and High's against Mid, that make the bands add up best " +
                    "through each crossover at every seat together — J_DSP's Band Align, run on these measurements. A change that " +
                    "would make either seat worse is never proposed. These are J_DSP settings, with no added latency.",
                Color.White,
            )
        }
        Panel("J_DSP delays and polarity during the sweeps", accent = Jdsp.LightBlue) {
            Note("Enter exactly what J_DSP had when you measured (Alignment › DELAY page). Proposals are moves from these values, 0–${trimNumber(BandAlign.DELAY_MAX_MS)} ms in 0.01 ms steps.")
            Note("Scored around the crossovers set on the Checks page: Low/Mid ${trimNumber(state.options.lowMidHz)} Hz, Mid/High ${trimNumber(state.options.midHighHz)} Hz. Check they match J_DSP.", Jdsp.LightBlue)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("", modifier = Modifier.width(70.dp))
                for (ch in Channel.entries) {
                    Text("${ch.label} delay (ms)", color = Jdsp.TextDim, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                    Text("${ch.label} inverted", color = Jdsp.TextDim, fontSize = 12.sp, modifier = Modifier.width(100.dp))
                }
            }
            for (band in Band.BRANCHES) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(band.label, color = bandColor(band), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(70.dp))
                    for (ch in Channel.entries) {
                        val s = a.timing.get(band, ch)
                        NumberField(s.delayMs, { v -> state.updateAlign(a.copy(timing = a.timing.with(band, ch, s.copy(delayMs = v)))) }, accent = bandColor(band))
                        Row(Modifier.width(100.dp)) {
                            GlassSwitch(s.inverted, { v -> state.updateAlign(a.copy(timing = a.timing.with(band, ch, s.copy(inverted = v)))) }, accent = Jdsp.MRed)
                        }
                    }
                }
            }
            state.alignError?.let { Note(it, Jdsp.MRed) }
        }
        Panel("Run", accent = Jdsp.MGreen) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
                Labeled("Low against Mid") { GlassSwitch(a.lowMid, { state.updateAlign(a.copy(lowMid = it)) }) }
                Labeled("High against Mid") { GlassSwitch(a.midHigh, { state.updateAlign(a.copy(midHigh = it)) }) }
                GlassButton(
                    "Find delays and polarity", { state.runAlign() }, accent = Jdsp.MGreen,
                    enabled = state.alignError == null && (a.lowMid || a.midHigh) && state.measurements.isNotEmpty() && state.busy == null,
                )
            }
        }
        val r = state.alignment ?: return@Column
        r.error?.let { Panel("Can't apply together", accent = Jdsp.MRed) { Note(it, Jdsp.MRed) } }
        Panel(if (r.hasChanges) "Enter in J_DSP" else "Result", accent = if (r.hasChanges) Jdsp.MGreen else Jdsp.LightBlue) {
            if (!r.hasChanges) {
                Note(if (r.error == null) "No change beats the current settings by ${trimNumber(BandAlign.MIN_IMPROVEMENT_DB)} dB without making a seat worse. Leave the delays and polarity as they are." else "No changes can be applied.")
            } else {
                r.changes.forEach { c ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(c.label, color = bandColor(c.band), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(80.dp))
                        Text(settingText(c.from) + "  →  " + settingText(c.to), color = Color.White, fontSize = 15.sp)
                    }
                }
                Note("Next: enter these on J_DSP's Alignment › DELAY page, re-measure both seats, and run Band Align again to confirm (it should propose no further change). For Phase IRs, start that tool from the fresh sweeps.", Jdsp.LightBlue)
                if (r.sharedOffsetMs > 0) {
                    Note("A band wanted to play earlier than 0 ms, so it's set to 0 and every other band is ${trimNumber(r.sharedOffsetMs)} ms later. Mid/High and left/right timing are unchanged relative to each other.", Jdsp.Orange)
                }
            }
        }
        r.results.forEach { ResultPanel(it, r.freqs) }
    }
}

private fun settingText(s: BandSetting) = "%.2f ms%s".format(java.util.Locale.ROOT, s.delayMs, if (s.inverted) ", inverted" else "")

@Composable
private fun ResultPanel(res: AlignResult, freqs: DoubleArray) {
    val accent = bandColor(res.pair.moving)
    Panel("${res.channel.label} · ${res.pair.label}", accent = accent) {
        val p = res.proposal
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge(if (p == null) "No change" else "${res.pair.moving.label} ${res.channel.name}: ${settingText(res.current)} → ${settingText(p)}", if (p == null) Jdsp.TextDim else Jdsp.MGreen)
            if (p != null) Text("+%.2f dB in the crossover region (seat-weighted)".format(res.scoreDb), color = Jdsp.TextDim, fontSize = 13.sp)
        }
        if (res.atRangeLimit) Note("The best delay is at the end of the range J_DSP allows; it may want more than the delays leave room for.", Jdsp.MidYellow)
        val lo = res.bandHz.start / 1.6
        val hi = res.bandHz.endInclusive * 1.6
        val idx = freqs.indices.filter { freqs[it] in lo..hi }
        if (idx.size > 2) {
            val x = DoubleArray(idx.size) { freqs[idx[it]] }
            val series = res.seats.flatMap { s ->
                val dashed = s.seat == Seat.PASSENGER
                listOf(
                    Series("${s.seat.label} before", x, DoubleArray(idx.size) { s.beforeDb[idx[it]] }, Jdsp.TextDim, dashed = dashed, width = 1.5f),
                    Series("${s.seat.label} after", x, DoubleArray(idx.size) { s.afterDb[idx[it]] }, if (dashed) Jdsp.Purple else accent, dashed = dashed, width = 2.5f),
                )
            }
            val ys = series.flatMap { it.y.asList() }.filter { it.isFinite() }
            val top = ceil((ys.max() + 2) / 5) * 5
            val bottom = maxOf(floor((ys.min() - 2) / 5) * 5, top - 45)
            Note("Seat average of all three bands, smoothed ±1/24 octave. Shaded: the scored crossover region.")
            LinePlot(series, bottom, top, "dB", shades = listOf(Shade(res.bandHz.start, res.bandHz.endInclusive, accent.copy(alpha = 0.08f))), height = 220.dp, accent = accent)
        }
        val positions = res.positions.joinToString("   ") { "${it.key.seat.label} ${it.key.position}${if (it.validationOnly) " (val.)" else ""}: %+.2f dB".format(it.scoreDb) }
        Note("Per position: $positions")
    }
}
