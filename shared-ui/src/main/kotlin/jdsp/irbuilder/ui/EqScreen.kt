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
import jdsp.irbuilder.CurvePoint
import jdsp.irbuilder.EqBank
import jdsp.irbuilder.EqOptions
import jdsp.irbuilder.HouseCurve
import jdsp.irbuilder.EqSide
import jdsp.irbuilder.EqSideResult
import jdsp.irbuilder.EqWizard
import jdsp.irbuilder.Seat
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
                    "Pre EQ gets only what both channels need alike, as L+R bands: the factory DSP's own colouring. What one side needs and " +
                    "the other doesn't, or what one band gets wrong, goes to the Low, Mid and High EQs, each only where its band carries the " +
                    "sound (the crossover regions are Band Align's and the IRs' job). It works on what you measured through the current tune, " +
                    "so the bands are corrections on top of your EQ: import them with Append. Cuts are free; boosts are capped and never chase " +
                    "a narrow dip, which is cancellation EQ can't fill. Use it last, on the finished crossovers, delays and IRs.",
                Color.White,
            )
        }
        HouseCurvePanel(state)
        Panel("Fit and limits", accent = Jdsp.LightBlue) {
            WrapRow(18.dp, Alignment.Bottom) {
                Labeled("Fit from (Hz)") { NumberField(e.minHz, { set(e.copy(minHz = it)) }, width = 90.dp) }
                Labeled("Fit to (Hz)") { NumberField(e.maxHz, { set(e.copy(maxHz = it)) }, width = 90.dp) }
            }
            WrapRow(18.dp, Alignment.Bottom) {
                Labeled("Max boost (dB)") { NumberField(e.maxBoostDb, { set(e.copy(maxBoostDb = it)) }, width = 80.dp) }
                Labeled("Max cut (dB)") { NumberField(e.maxCutDb, { set(e.copy(maxCutDb = it)) }, width = 80.dp) }
                Labeled("Max Q (cuts)") { NumberField(e.maxQ, { set(e.copy(maxQ = it)) }, width = 80.dp) }
            }
            Note("The house curve is levelled to each side's response (the median over the fit range): only its shape matters, and each side keeps its own level.")
        }
        Panel("Pre EQ and band EQ", accent = Jdsp.LightBlue) {
            WrapRow(18.dp, Alignment.Bottom) {
                Labeled("Pre EQ (shared L+R)") { GlassSwitch(e.preEq, { set(e.copy(preEq = it)) }) }
                Labeled("Pre EQ bands") { NumberField(e.preBands.toDouble(), { set(e.copy(preBands = it.toInt())) }, width = 80.dp) }
            }
            Note("Only the correction left and right both need, the smaller of the two where they agree: what the factory DSP does to the whole system. Needs both sides measured.")
            WrapRow(18.dp, Alignment.Bottom) {
                Labeled("Low/Mid/High EQ") { GlassSwitch(e.bandEq, { set(e.copy(bandEq = it)) }) }
                Labeled("Bands per band, per side") { NumberField(e.maxBands.toDouble(), { set(e.copy(maxBands = it.toInt())) }, width = 80.dp) }
                Labeled("Same band EQ both sides") { GlassSwitch(e.linked, { set(e.copy(linked = it)) }) }
            }
            WrapRow(18.dp, Alignment.Bottom) {
                Labeled("Low/Mid split (Hz)") { NumberField(e.lowMidHz, { set(e.copy(lowMidHz = it)) }, width = 90.dp, accent = Jdsp.LowBand) }
                Labeled("Mid/High split (Hz)") { NumberField(e.midHighHz, { set(e.copy(midHighHz = it)) }, width = 90.dp, accent = Jdsp.HighBand) }
            }
            Note(
                "The rest, per side: each band's EQ works only where that band has at least 80 % of the sound, read from your band solos. " +
                    "With only Combined sweeps, the bands are split at these two frequencies instead: set them to J_DSP's crossovers. " +
                    "Each J_DSP bank holds 16 bands: per side that's 8 each, minus any you already use.",
            )
            state.eqError?.let { Note(it, Jdsp.MRed) }
            GlassButton("Fit EQ", { state.runEq() }, accent = Jdsp.MGreen, enabled = state.eqError == null && state.measurements.isNotEmpty() && state.busy == null)
        }
        val r = state.eqResult ?: return@Column
        r.warnings.forEach { Note("• $it", Jdsp.MidYellow) }
        Panel("Export for J_DSP", accent = Jdsp.MGreen) {
            val banks = EqBank.entries.filter { r.bank(it).isNotEmpty() }
            if (banks.isEmpty()) Note("Nothing to export: the response already follows the target within the limits set.", Color.White)
            banks.forEach { bank ->
                WrapRow(12.dp, Alignment.CenterVertically) {
                    GlassButton("Save ${bank.label} file…", { Dialogs.saveFile("Save ${bank.label} file", bank.fileName) { state.exportEq(bank, it) } }, accent = Jdsp.MGreen, enabled = state.busy == null)
                    Text("${r.bank(bank).size} band(s)", color = Jdsp.TextDim, fontSize = 13.sp)
                    state.eqExported[bank]?.let { file ->
                        GlassButton(Dialogs.REVEAL_LABEL, { Dialogs.reveal(file) }, accent = Jdsp.LightBlue)
                        Text(file.path, color = Jdsp.MGreen, fontSize = 13.sp)
                    }
                }
            }
            if (banks.isNotEmpty()) listOf(
                "Copy the files to the head unit. Keep their names: \"input\" routes a file to Pre EQ, \"low\", \"mid\" and \"high\" to that band's EQ.",
                "Parametric EQ → each EQ → Import → pick its file → Append (adds these bands on top of the ones you measured with).",
                banks.joinToString(" ") { b ->
                    val p = r.preamp(b)
                    if (p < 0) "${b.label}'s bands boost up to %.1f dB somewhere: lower its preamp by that much (Append leaves the preamp alone).".format(-p)
                    else "${b.label}'s bands only cut: no preamp change."
                },
                "Re-measure both seats and run the wizard again; it should find little left to do.",
            ).forEachIndexed { i, s -> Note("${i + 1}. $s") }
        }
        r.sides.forEach { SidePanel(it, r.freqs, e) }
    }
}

@Composable
private fun HouseCurvePanel(state: AppState) {
    val e = state.eq
    fun set(n: EqOptions) = state.updateEq(n)
    Panel("House curve", accent = Jdsp.Cyan) {
        WrapRow(10.dp, Alignment.CenterVertically) {
            Chip("Built-in", !e.useCurve, { set(e.copy(useCurve = false)) }, Jdsp.Cyan)
            Chip("Custom curve", e.useCurve, {
                set(e.copy(useCurve = true, curve = e.curve.ifEmpty { HouseCurve.fromBuiltIn(e) }))
            }, Jdsp.Cyan)
        }
        if (!e.useCurve) {
            WrapRow(18.dp, Alignment.Bottom) {
                Labeled("Bass lift (dB)") { NumberField(e.bassLiftDb, { set(e.copy(bassLiftDb = it)) }, width = 90.dp, accent = Jdsp.LowBand) }
                Labeled("Treble tilt 1–10 kHz (dB)") { NumberField(e.trebleTiltDb, { set(e.copy(trebleTiltDb = it)) }, width = 90.dp, accent = Jdsp.HighBand) }
            }
            Note("A low-shelf lift centred near 100 Hz and a straight tilt from 1 kHz to 10 kHz. For any other shape, use a custom curve.")
        } else {
            WrapRow(10.dp) {
                GlassButton("Import curve…", { Dialogs.openFiles("Import house curve", listOf("txt", "csv", "frd", "cal"), multiple = false) { it.firstOrNull()?.let(state::importCurve) } }, accent = Jdsp.Cyan, enabled = state.busy == null)
                GlassButton("Save curve…", { Dialogs.saveFile("Save house curve", "house-curve.txt") { state.saveCurve(it) } }, enabled = e.curve.size >= 2 && state.busy == null)
                GlassButton("Start from built-in", { set(e.copy(curve = HouseCurve.fromBuiltIn(e))) }, accent = Jdsp.TextDim)
                GlassButton("Add point", {
                    val last = e.curve.maxByOrNull { it.hz }
                    val hz = if (last == null) 1000.0 else minOf(20000.0, Math.round(last.hz * 2).toDouble())
                    set(e.copy(curve = (e.curve + CurvePoint(hz, last?.db ?: 0.0)).distinctBy { it.hz }.sortedBy { it.hz }))
                }, accent = Jdsp.TextDim)
            }
            Note("REW house curve files (frequency and dB per line) and most text target curves import. Points are joined straight in log frequency and held flat beyond the ends.")
            val pts = e.curve.sortedBy { it.hz }
            if (pts.size >= 2) {
                val x = jdsp.irbuilder.geomspaceOf(20.0, 20000.0, 200)
                val y = DoubleArray(x.size) { HouseCurve.at(pts, x[it]) }
                val top = ceil((y.max() + 2) / 2) * 2
                val bottom = floor((y.min() - 2) / 2) * 2
                LinePlot(listOf(Series("House curve", x, y, Jdsp.Cyan, width = 2.5f)), bottom, top, "dB", height = 180.dp, accent = Jdsp.Cyan)
            }
            if (pts.size <= 40) {
                pts.forEachIndexed { i, p ->
                    WrapRow(12.dp, Alignment.CenterVertically) {
                        Text("${i + 1}", color = Jdsp.TextDim, fontSize = 12.sp, modifier = Modifier.width(24.dp))
                        NumberField(p.hz, { v -> set(e.copy(curve = pts.map { if (it == p) it.copy(hz = v) else it }.sortedBy { it.hz })) }, width = 100.dp)
                        Text("Hz", color = Jdsp.TextDim, fontSize = 12.sp)
                        NumberField(p.db, { v -> set(e.copy(curve = pts.map { if (it == p) it.copy(db = v) else it })) }, width = 80.dp, accent = Jdsp.Cyan)
                        Text("dB", color = Jdsp.TextDim, fontSize = 12.sp)
                        GlassButton("✕", { set(e.copy(curve = pts.filter { it != p })) }, accent = Jdsp.MRed, enabled = pts.size > 2)
                    }
                }
            } else Note("${pts.size} points (too many to edit here: edit the file and import it again).")
        }
    }
}

@Composable
private fun SidePanel(s: EqSideResult, f: DoubleArray, e: EqOptions) {
    val accent = when (s.side) { EqSide.LEFT -> Jdsp.LightBlue; EqSide.RIGHT -> Jdsp.HighPink; EqSide.BOTH -> Jdsp.Cyan }
    val name = when (s.side) { EqSide.LEFT -> "Left side"; EqSide.RIGHT -> "Right side"; EqSide.BOTH -> "Both sides (L+R)" }
    Panel(name, accent = accent) {
        WrapRow(16.dp) {
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
            LinePlot(listOf(ser("All EQ", s.eqDb, accent, 2.5f), ser("Pre EQ", s.preDb, Jdsp.Cyan, 1.5f), ser("Wanted", s.wantedDb, Jdsp.TextDim, 1.5f, dashed = true)), -14.0, 8.0, "dB", height = 160.dp, accent = accent)
            Note("Lower plot: what all the EQ does to this side's sum, Pre EQ's part of it, and what was asked for (target − response, boosts held back where the octave-wide response doesn't lack it). The gap between All EQ and Pre EQ is this side's band EQs.")
        }
        Table {
            TableRow {
                listOf("#" to 30, "EQ" to 70, "Type" to 50, "Side" to 50, "Frequency" to 110, "Gain" to 90, "Q" to 70).forEach { (t, w) -> Text(t, color = Jdsp.TextDim, fontSize = 12.sp, modifier = Modifier.width(w.dp)) }
            }
            s.bands.forEachIndexed { i, b ->
                TableRow {
                    Cell("${i + 1}", 30); Cell(b.bank.label, 70, bankColor(b.bank)); Cell("PK", 50); Cell(b.side.label, 50)
                    Cell("${trimNumber(b.hz)} Hz", 110, Color.White)
                    Cell("%+.1f dB".format(b.gainDb), 90, if (b.gainDb > 0) Jdsp.MidYellow else Jdsp.MGreen)
                    Cell(trimNumber(b.q), 70)
                }
            }
        }
        Text("", fontSize = 4.sp, fontWeight = FontWeight.Normal)
    }
}

private fun bankColor(b: EqBank) = when (b) {
    EqBank.PRE -> Jdsp.Cyan
    EqBank.LOW -> Jdsp.LowBand
    EqBank.MID -> Jdsp.MidBand
    EqBank.HIGH -> Jdsp.HighBand
}
