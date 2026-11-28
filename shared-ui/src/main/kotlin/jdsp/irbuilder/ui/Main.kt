package jdsp.irbuilder.ui

import jdsp.irbuilder.Tool

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun StartScreen(state: AppState) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel("What do you want to do?", accent = Jdsp.Orange, trailing = {
            GlassButton("Open project…", { Dialogs.openFiles("Open project", listOf("jirb"), multiple = false) { it.firstOrNull()?.let(state::openProject) } }, accent = Jdsp.Purple)
        }) {
            Note("Four independent tools, all working from REW sweeps at both seats. For the best result: Crossover Finder (or Band Align if the crossovers stay), enter the changes in J_DSP and re-measure; then Phase IRs if you use them; then the EQ Wizard last, on the finished system.", Color.White)
        }
        val cards = listOf(
            CardSpec(
                Tool.BAND_ALIGN, Jdsp.MGreen, "Delay and polarity in J_DSP",
                listOf(
                    "Finds each side's Low and High delay and polarity against Mid that make the bands add up best at both seats.",
                    "Scored by sum loss (cancellation at the crossover); never makes a seat worse.",
                    "Result: a short list of settings to enter on J_DSP's DELAY page.",
                    "No added latency.",
                ),
                "Steps: Measurements → Checks → Band Align",
            ),
            CardSpec(
                Tool.PHASE_IRS, Jdsp.HighPink, "Phase-alignment IRs for J_DSP's convolver",
                listOf(
                    "Designs Low/Mid/High phase-only FIRs that make the bands sum coherently through both crossovers at both seats.",
                    "Leaves band magnitude alone: tone stays with your PEQ.",
                    "Result: band-ir-package.zip for the convolver page's Per-band mode.",
                    "Adds about 192 ms latency by default (adjustable).",
                ),
                "Steps: Measurements → Checks → Design → Preview → Export",
            ),
            CardSpec(
                Tool.CROSSOVER_FINDER, Jdsp.Orange, "Which J_DSP crossovers to use",
                listOf(
                    "Searches each crossover's frequency and slope, with each side's delay and polarity, for the bands to add up best at both seats.",
                    "Reuses your band-solo sweeps: J_DSP's current filter is divided out exactly, others tried where the data is reliable.",
                    "Stays within the frequency limits and slopes you allow; never makes a seat worse.",
                    "Result: crossover, delay and polarity settings to enter in J_DSP. No added latency.",
                ),
                "Steps: Measurements → Checks → Crossover Finder",
            ),
            CardSpec(
                Tool.EQ_WIZARD, Jdsp.Cyan, "PEQ bands toward a target: Pre EQ and Low/Mid/High EQ",
                listOf(
                    "Fits peaking bands so the system's response at both seats follows a house curve (built in, or your own).",
                    "Pre EQ gets only what both channels need alike (the factory DSP's colouring), as L+R bands.",
                    "The rest goes to the Low, Mid and High EQs, per side, each where its band carries the sound.",
                    "Result: an Equalizer APO file per EQ to import (Append). Boosts are capped and never fill a narrow dip.",
                ),
                "Steps: Measurements → Checks → EQ Wizard",
            ),
        )
        if (LocalCompact.current) {
            cards.forEach { ToolCard(state, it, Modifier.fillMaxWidth()) }
        } else cards.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { ToolCard(state, it, Modifier.weight(1f)) }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        if (state.measurements.isNotEmpty()) Note("${state.measurements.size} imported measurement(s) stay loaded when you switch tools.")
    }
}

private class CardSpec(val tool: Tool, val accent: Color, val subtitle: String, val points: List<String>, val steps: String)

@Composable
private fun ToolCard(state: AppState, c: CardSpec, modifier: Modifier) {
    Panel(c.tool.title, modifier = modifier, accent = c.accent) {
        Text(c.subtitle, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        c.points.forEach { Note("• $it", Color.White) }
        Note(c.steps)
        GlassButton("Start ${c.tool.title}", { state.startTool(c.tool) }, accent = c.accent)
    }
}

@Composable
fun App(state: AppState) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val compact = maxWidth < COMPACT_WIDTH
        androidx.compose.runtime.CompositionLocalProvider(LocalCompact provides compact) {
            if (compact) {
                Column(Modifier.fillMaxSize()) {
                    TopBar(state)
                    Box(Modifier.weight(1f).fillMaxWidth()) { Content(state, 10.dp) }
                    StatusBar(state)
                }
            } else Row(Modifier.fillMaxSize()) {
                Sidebar(state)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { Content(state, 20.dp) }
                    StatusBar(state)
                }
            }
        }
    }
}

@Composable
private fun Content(state: AppState, padding: androidx.compose.ui.unit.Dp) {
    val scroll = rememberScrollState()
    // A new tool or step starts at the top.
    androidx.compose.runtime.LaunchedEffect(state.tool, state.step) { scroll.scrollTo(0) }
    Column(Modifier.verticalScroll(scroll).padding(padding)) {
        if (state.tool == null) StartScreen(state) else when (state.step) {
            Step.MEASUREMENTS -> MeasurementsScreen(state)
            Step.CHECKS -> ChecksScreen(state)
            Step.BAND_ALIGN -> BandAlignScreen(state)
            Step.FINDER -> FinderScreen(state)
            Step.EQ -> EqScreen(state)
            Step.DESIGN -> DesignScreen(state)
            Step.PREVIEW -> PreviewScreen(state)
            Step.EXPORT -> ExportScreen(state)
        }
    }
}

/** The phone's header: the title, the tool (tap to change), and the steps as swipeable tabs. */
@Composable
private fun TopBar(state: AppState) {
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF1B2027), Jdsp.Gunmetal))).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tool = state.tool
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("J_DSP", color = Jdsp.LightBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("  IR BUILDER", color = Jdsp.TextDim, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Box(
                Modifier.glassBox(if (tool == null) Jdsp.Orange else Jdsp.TextDim, borderAlpha = if (tool == null) 1f else 0.5f)
                    .clickable { state.tool = null }.padding(horizontal = 10.dp, vertical = 6.dp),
            ) { Text(tool?.let { "${it.title} · change" } ?: "Choose a tool", color = Color.White, fontSize = 12.sp) }
        }
        if (tool != null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            stepsFor(tool).forEachIndexed { i, s ->
                val selected = state.step == s
                val (badge, color) = stepStatus(state, s)
                Row(
                    Modifier.glassBox(if (selected) Jdsp.LightBlue else Jdsp.TextDim, borderAlpha = if (selected) 1f else 0.3f)
                        .clickable { state.step = s }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("${i + 1}", color = if (selected) Jdsp.LightBlue else Jdsp.TextDim, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(s.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (badge != null) Text(badge, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Sidebar(state: AppState) {
    Column(
        Modifier.width(250.dp).fillMaxHeight().background(Brush.verticalGradient(listOf(Color(0xFF1B2027), Jdsp.Gunmetal))).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("J_DSP", color = Jdsp.LightBlue, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("IR BUILDER", color = Jdsp.TextDim, fontSize = 12.sp, letterSpacing = 3.sp)
        Box(Modifier.padding(top = 8.dp))
        val tool = state.tool
        Row(
            Modifier.fillMaxWidth().glassBox(if (tool == null) Jdsp.Orange else Jdsp.TextDim, borderAlpha = if (tool == null) 1f else 0.3f)
                .clickable { state.tool = null }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Start", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(tool?.let { "Tool: ${it.title} · change" } ?: "Choose a tool", color = Jdsp.TextDim, fontSize = 11.sp)
            }
        }
        if (tool != null) stepsFor(tool).forEachIndexed { i, s ->
            val selected = state.step == s
            val (badge, color) = stepStatus(state, s)
            Row(
                Modifier.fillMaxWidth().glassBox(if (selected) Jdsp.LightBlue else Jdsp.TextDim, borderAlpha = if (selected) 1f else 0.3f)
                    .clickable { state.step = s }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${i + 1}", color = if (selected) Jdsp.LightBlue else Jdsp.TextDim, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f)) {
                    Text(s.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(s.subtitle, color = Jdsp.TextDim, fontSize = 11.sp)
                }
                if (badge != null) Text(badge, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(Modifier.weight(1f))
        Note(
            when (tool) {
                Tool.BAND_ALIGN -> "Band Align proposes J_DSP delay and polarity changes. Make them, then re-measure before designing IRs."
                Tool.PHASE_IRS -> "Phase-only, correction-only IRs: J_DSP's crossovers, PEQ and delays stay as measured. Verify with fresh measurements."
                Tool.CROSSOVER_FINDER -> "Crossover Finder proposes J_DSP crossover, delay and polarity changes within your limits. Make them, then re-measure."
                Tool.EQ_WIZARD -> "EQ Wizard proposes PEQ bands on top of your current tune: shared ones for Pre EQ, the rest per band. Import them (Append), then re-measure."
                null -> "Four separate tools. Each starts from its own measurements."
            },
        )
    }
}

private fun stepStatus(state: AppState, s: Step): Pair<String?, Color> = when (s) {
    Step.MEASUREMENTS -> (if (state.measurements.isEmpty()) null else "${state.measurements.size}") to Jdsp.LightBlue
    Step.CHECKS -> state.checks?.let { if (it.errors > 0) "${it.errors}✕" to Jdsp.MRed else if (it.warnings > 0) "${it.warnings}!" to Jdsp.MidYellow else "✓" to Jdsp.MGreen } ?: (null to Color.White)
    Step.BAND_ALIGN -> state.alignment?.let { if (it.error != null) "!" to Jdsp.MRed else if (it.hasChanges) "${it.changes.size}" to Jdsp.MGreen else "✓" to Jdsp.MGreen } ?: (null to Color.White)
    Step.FINDER -> state.finding?.let { if (it.error != null) "!" to Jdsp.MRed else if (it.hasChanges) "${it.filterChanges.size + it.timingChanges.size}" to Jdsp.MGreen else "✓" to Jdsp.MGreen } ?: ((if (state.finderError != null) "!" else null) to Jdsp.MRed)
    Step.EQ -> state.eqResult?.let { (if (state.eqExported.isNotEmpty()) "✓" else "${it.bands.size}") to Jdsp.MGreen } ?: ((if (state.eqError != null) "!" else null) to Jdsp.MRed)
    Step.DESIGN -> (if (state.optionsError != null) "!" else null) to Jdsp.MRed
    Step.PREVIEW -> (if (state.hasDesign) "✓" else null) to Jdsp.MGreen
    Step.EXPORT -> (if (state.exportedZip != null) "✓" else null) to Jdsp.MGreen
}

@Composable
private fun StatusBar(state: AppState) {
    Column(Modifier.fillMaxWidth().background(Jdsp.Gunmetal)) {
        if (state.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Jdsp.LightBlue, trackColor = Color.Transparent)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.busy ?: state.status, color = if (state.errorMessage != null && state.busy == null) Jdsp.MRed else Jdsp.TextDim, fontSize = 13.sp)
        }
    }
}
