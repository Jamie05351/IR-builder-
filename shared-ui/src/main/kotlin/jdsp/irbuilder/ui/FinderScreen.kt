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
import jdsp.irbuilder.Band
import jdsp.irbuilder.BandAlign
import jdsp.irbuilder.BandSetting
import jdsp.irbuilder.Channel
import jdsp.irbuilder.Crossover
import jdsp.irbuilder.CrossoverFinder
import jdsp.irbuilder.FinderOptions
import jdsp.irbuilder.FinderResult
import jdsp.irbuilder.JdspCrossovers
import jdsp.irbuilder.Seat
import jdsp.irbuilder.SlotLimits
import jdsp.irbuilder.XoFilter
import jdsp.irbuilder.XoSlot
import jdsp.irbuilder.XoType
import kotlin.math.ceil
import kotlin.math.floor

@Composable
fun FinderScreen(state: AppState) {
    val f = state.finder
    fun set(n: FinderOptions) = state.updateFinder(n)
    fun setXo(x: JdspCrossovers) = set(f.copy(current = x))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel("Crossover Finder", accent = Jdsp.Orange) {
            Note(
                "Finds the J_DSP crossover frequency and slope for each side of each crossover, with each side's delay and polarity, " +
                    "that make the bands add up best through the crossover at both seats. It works from your band-solo sweeps: J_DSP's " +
                    "current filter is divided out exactly and each candidate put in its place, only where the sweeps can vouch for it. " +
                    "Speakers, factory crossovers and the cabin stay as measured. Nothing that would make a seat worse is proposed.",
                Color.White,
            )
            Note("The tool can't know what your speakers can safely take: set the limits below for each filter before you search.", Jdsp.Orange)
        }
        Panel("J_DSP crossovers during the sweeps", accent = Jdsp.LightBlue) {
            Note("Enter exactly what J_DSP's Crossover pages had when you measured (they're the same for left and right).")
            val x = f.current
            WrapRow(20.dp, Alignment.Bottom) {
                FilterInput(XoSlot.LOW_LOWPASS, x.lowLowpass) { setXo(x.copy(lowLowpass = it)) }
                FilterInput(XoSlot.MID_HIGHPASS, x.midHighpass) { setXo(x.copy(midHighpass = it)) }
                Labeled("Subsonic") { GlassSwitch(x.subsonic, { setXo(x.copy(subsonic = it)) }) }
                if (x.subsonic) {
                    Labeled("Subsonic (Hz)") { NumberField(x.subsonicHz, { setXo(x.copy(subsonicHz = it)) }, width = 80.dp, accent = Jdsp.LowBand) }
                    Labeled("Subsonic slope") { GlassDropdown(x.subsonicBw2, listOf(false, true), { if (it) "BW2" else "LR4" }, { setXo(x.copy(subsonicBw2 = it)) }, width = 90.dp) }
                }
            }
            WrapRow(20.dp, Alignment.Bottom) {
                Labeled("3-way") { GlassSwitch(x.threeWay, { setXo(x.copy(threeWay = it)) }) }
                if (x.threeWay) {
                    FilterInput(XoSlot.MID_LOWPASS, x.midLowpass) { setXo(x.copy(midLowpass = it)) }
                    FilterInput(XoSlot.HIGH_HIGHPASS, x.highHighpass) { setXo(x.copy(highHighpass = it)) }
                }
            }
        }
        Panel("J_DSP delays and polarity during the sweeps", accent = Jdsp.LightBlue) {
            Note("As on J_DSP's Alignment › DELAY page. A new crossover turns the bands' phase, so each side's Low (at Low/Mid) and High (at Mid/High) delay and polarity are searched with it, 0–${trimNumber(BandAlign.DELAY_MAX_MS)} ms.")
            TimingGrid(f.timing) { band, ch, delay, inverted ->
                val s = f.timing.get(band, ch)
                set(f.copy(timing = f.timing.with(band, ch, s.copy(delayMs = delay ?: s.delayMs, inverted = inverted ?: s.inverted))))
            }
        }
        Panel("Search limits", accent = Jdsp.Orange) {
            Note(
                "Per filter: the lowest and highest corner and the slopes the finder may use. It searches 1/12-octave steps up to an octave " +
                    "from the current corner, inside these limits and J_DSP's ranges. Defaults: half an octave either way; any slope for a " +
                    "lowpass, and a highpass no shallower than now (a shallower highpass sends a driver more low end).",
            )
            for (xo in Crossover.entries) {
                if (xo == Crossover.MID_HIGH && !f.current.threeWay) continue
                Text(xo.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                for (s in XoSlot.of(xo).toList()) LimitsRow(s, f) { set(f.withLimits(s, it)) }
            }
            WrapRow(12.dp) {
                GlassButton("Suggest from measurements", { state.suggestFinderLimits() }, accent = Jdsp.Orange, enabled = state.measurements.isNotEmpty() && state.finderError == null && state.busy == null)
                if (f.limits.isNotEmpty()) GlassButton("Reset limits to defaults", { set(f.copy(limits = emptyMap())) }, accent = Jdsp.TextDim)
            }
            state.bandRanges?.let { ranges ->
                Note("Measured driver paths, with J_DSP's crossover divided out (usable = within 8 dB of the band's typical level; ≤ or + = the data ran out first, so it may reach further):")
                ranges.forEach { (b, r) ->
                    Note("${b.label}: ${if (r.lowOpen) "≤" else ""}${trimNumber(Math.round(r.lowHz).toDouble())} Hz to ${trimNumber(Math.round(r.highHz).toDouble())} Hz${if (r.highOpen) "+" else ""}", bandColor(b))
                }
                Note("A highpass is kept above its driver's low edge and a lowpass below its high edge. This reads output, not excursion or distortion: it can't tell you what a speaker can safely take.", Jdsp.Orange)
            }
        }
        Panel("Run", accent = Jdsp.MGreen) {
            WrapRow(24.dp, Alignment.Bottom) {
                Labeled("Low/Mid") { GlassSwitch(f.lowMid, { set(f.copy(lowMid = it)) }) }
                Labeled("Mid/High") { GlassSwitch(f.midHigh, { set(f.copy(midHigh = it)) }) }
                GlassButton(
                    "Find crossovers", { state.runFinder() }, accent = Jdsp.MGreen,
                    enabled = state.finderError == null && state.measurements.isNotEmpty() && state.busy == null,
                )
            }
            Note("Searching takes a few seconds to a minute, depending on the limits and the number of positions.")
            state.finderError?.let { Note(it, Jdsp.MRed) }
        }
        val r = state.finding ?: return@Column
        r.error?.let { Panel("Can't apply together", accent = Jdsp.MRed) { Note(it, Jdsp.MRed) } }
        Panel(if (r.hasChanges) "Enter in J_DSP" else "Result", accent = if (r.hasChanges) Jdsp.MGreen else Jdsp.LightBlue) {
            if (!r.hasChanges) {
                Note(if (r.error == null) "Nothing within your limits beats the current settings by ${trimNumber(CrossoverFinder.MIN_IMPROVEMENT_DB)} dB without making a seat worse. Keep the crossovers as they are." else "No changes can be applied.")
            } else {
                if (r.filterChanges.isNotEmpty()) Text("Crossover pages", color = Jdsp.TextDim, fontSize = 12.sp)
                r.filterChanges.forEach { c ->
                    WrapRow(12.dp) {
                        Text(c.slot.label, color = bandColor(c.slot.band), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(130.dp))
                        Text("${c.from.label}  →  ${c.to.label}", color = Color.White, fontSize = 15.sp)
                    }
                }
                if (r.timingChanges.isNotEmpty()) Text("Alignment › DELAY page", color = Jdsp.TextDim, fontSize = 12.sp)
                r.timingChanges.forEach { c ->
                    WrapRow(12.dp) {
                        Text(c.label, color = bandColor(c.band), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(130.dp))
                        Text(settingText(c.from) + "  →  " + settingText(c.to), color = Color.White, fontSize = 15.sp)
                    }
                }
                Note("Both sides get the same crossover (J_DSP links them); delays and polarity are per side. Next: enter these, re-measure both seats, and run the finder again to confirm. Re-check level and EQ around the crossovers, then design Phase IRs from the fresh sweeps if you use them.", Jdsp.LightBlue)
                if (r.sharedOffsetMs > 0) {
                    Note("A band wanted to play earlier than 0 ms, so it's set to 0 and every other band is ${trimNumber(r.sharedOffsetMs)} ms later.", Jdsp.Orange)
                }
            }
        }
        r.results.forEach { FinderResultPanel(it, r.freqs) }
    }
}

private fun settingText(s: BandSetting) = "%.2f ms%s".format(java.util.Locale.ROOT, s.delayMs, if (s.inverted) ", inverted" else "")

@Composable
private fun FilterInput(slot: XoSlot, value: XoFilter, onChange: (XoFilter) -> Unit) {
    Labeled("${slot.label} (Hz)") { NumberField(value.hz, { onChange(value.copy(hz = it)) }, width = 90.dp, accent = bandColor(slot.band)) }
    Labeled("Slope") { GlassDropdown(value.type, XoType.entries, { it.longLabel }, { onChange(value.copy(type = it)) }, width = 150.dp, accent = bandColor(slot.band)) }
}

@Composable
private fun LimitsRow(slot: XoSlot, f: FinderOptions, onChange: (SlotLimits) -> Unit) {
    val l = f.limits(slot)
    WrapRow(14.dp, Alignment.CenterVertically) {
        Text(slot.label, color = bandColor(slot.band), fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Text("now ${f.current.get(slot).label}", color = Jdsp.TextDim, fontSize = 12.sp, modifier = Modifier.width(120.dp))
        Labeled("Lowest (Hz)") { NumberField(l.minHz, { onChange(l.copy(minHz = it)) }, width = 80.dp, accent = bandColor(slot.band)) }
        Labeled("Highest (Hz)") { NumberField(l.maxHz, { onChange(l.copy(maxHz = it)) }, width = 80.dp, accent = bandColor(slot.band)) }
        WrapRow(6.dp) {
            XoType.entries.forEach { t ->
                Chip(t.label, t in l.types, { onChange(l.copy(types = if (t in l.types) l.types - t else l.types + t)) }, bandColor(slot.band))
            }
        }
    }
}

@Composable
private fun FinderResultPanel(res: FinderResult, freqs: DoubleArray) {
    val (loSlot, upSlot) = XoSlot.of(res.crossover)
    val accent = if (res.crossover == Crossover.LOW_MID) Jdsp.LowBand else Jdsp.HighBand
    Panel(res.crossover.label, accent = accent) {
        WrapRow(16.dp, Alignment.CenterVertically) {
            Badge(if (!res.changed) "No change" else "+%.2f dB through the crossover (seat-weighted)".format(res.scoreDb), if (res.changed) Jdsp.MGreen else Jdsp.TextDim)
            Text("${loSlot.label} ${res.lower.label} · ${upSlot.label} ${res.upper.label}", color = Color.White, fontSize = 14.sp)
        }
        res.channels.forEach { c ->
            Note("${c.channel.label}: ${c.moving.label} ${settingText(c.current)}" + (if (c.proposed != c.current) " → ${settingText(c.proposed)}" else " (unchanged)") +
                "   " + c.positions.joinToString("   ") { "${it.key.seat.label} ${it.key.position}${if (it.validationOnly) " (val.)" else ""}: %+.2f dB".format(it.scoreDb) },
                Color.White)
            if (c.delayAtLimit) Note("${c.channel.label}: the best delay is at the end of J_DSP's range; it may want more.", Jdsp.MidYellow)
        }
        res.channels.forEach { c -> c.seats.forEach { s -> lossText(s.lossBefore, s.lossAfter)?.let { Note("${c.channel.label} · ${s.seat.label}: $it", Color.White) } } }
        if (res.atSearchEdge.isNotEmpty()) Note("${res.atSearchEdge.joinToString { it.label }} ended at the edge of what was searched (your limit, J_DSP's range or an octave). Widen the limit only if the speaker can take it.", Jdsp.MidYellow)
        if (res.unreliable > 0) Note("${res.unreliable} candidate(s) left out because they'd rely on parts of a sweep that are too quiet or weren't swept.")
        res.channels.forEach { c ->
            val lo = res.bandHz.start / 1.4
            val hi = res.bandHz.endInclusive * 1.4
            val idx = freqs.indices.filter { freqs[it] in lo..hi }
            if (idx.size < 3) return@forEach
            val x = DoubleArray(idx.size) { freqs[idx[it]] }
            val series = c.seats.flatMapIndexed { si, s ->
                val dashed = s.seat == Seat.PASSENGER
                listOf(
                    Series("${s.seat.label} target", x, DoubleArray(idx.size) { c.targetDb[si][idx[it]] }, Jdsp.Cyan.copy(alpha = 0.7f), dashed = dashed, width = 1f),
                    Series("${s.seat.label} now", x, DoubleArray(idx.size) { s.beforeDb[idx[it]] }, Jdsp.TextDim, dashed = dashed, width = 1.5f),
                    Series("${s.seat.label} proposed", x, DoubleArray(idx.size) { s.afterDb[idx[it]] }, if (dashed) Jdsp.Purple else accent, dashed = dashed, width = 2.5f),
                )
            }
            val ys = series.flatMap { it.y.asList() }.filter { it.isFinite() }
            if (ys.isEmpty()) return@forEach
            val top = ceil((ys.max() + 2) / 5) * 5
            val bottom = maxOf(floor((ys.min() - 2) / 5) * 5, top - 45)
            Text("${c.channel.label} side", color = Jdsp.TextDim, fontSize = 12.sp)
            LinePlot(series, bottom, top, "dB", shades = listOf(Shade(res.bandHz.start, res.bandHz.endInclusive, accent.copy(alpha = 0.08f))), height = 220.dp, accent = accent)
        }
        Note("Seat average of the band sum, smoothed ±1/24 octave. Target: the bands summed perfectly in phase. Shaded: the scored region. Dips below the target count double: EQ can't fill cancellation.")
        if (res.alternatives.isNotEmpty()) {
            Text("Other options found", color = Jdsp.TextDim, fontSize = 12.sp)
            res.alternatives.forEach { a -> Note("${loSlot.label} ${a.lower.label} · ${upSlot.label} ${a.upper.label}: %+.2f dB".format(a.scoreDb)) }
        }
    }
}
