package jdsp.irbuilder.ui

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "J_DSP IR Builder — phase-only band alignment",
        state = rememberWindowState(size = DpSize(1480.dp, 940.dp)),
    ) {
        JdspTheme {
            val scope = rememberCoroutineScope()
            val state = remember { AppState(scope) }
            App(state)
        }
    }
}

@Composable
fun App(state: AppState) {
    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Sidebar(state)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val scroll = rememberScrollState()
                Column(Modifier.verticalScroll(scroll).padding(20.dp)) {
                    when (state.step) {
                        Step.MEASUREMENTS -> MeasurementsScreen(state)
                        Step.CHECKS -> ChecksScreen(state)
                        Step.BAND_ALIGN -> BandAlignScreen(state)
                        Step.DESIGN -> DesignScreen(state)
                        Step.PREVIEW -> PreviewScreen(state)
                        Step.EXPORT -> ExportScreen(state)
                    }
                }
            }
            StatusBar(state)
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
        Step.entries.forEachIndexed { i, s ->
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
        Note("Phase-only. Correction-only: J_DSP's crossovers, PEQ and delays stay as measured. Predictions must be verified with fresh measurements.")
    }
}

private fun stepStatus(state: AppState, s: Step): Pair<String?, Color> = when (s) {
    Step.MEASUREMENTS -> (if (state.measurements.isEmpty()) null else "${state.measurements.size}") to Jdsp.LightBlue
    Step.CHECKS -> state.checks?.let { if (it.errors > 0) "${it.errors}✕" to Jdsp.MRed else if (it.warnings > 0) "${it.warnings}!" to Jdsp.MidYellow else "✓" to Jdsp.MGreen } ?: (null to Color.White)
    Step.BAND_ALIGN -> state.alignment?.let { if (it.error != null) "!" to Jdsp.MRed else if (it.hasChanges) "${it.changes.size}" to Jdsp.MGreen else "✓" to Jdsp.MGreen } ?: (null to Color.White)
    Step.DESIGN -> (if (state.optionsError != null) "!" else null) to Jdsp.MRed
    Step.PREVIEW -> (if (state.design != null) "✓" else null) to Jdsp.MGreen
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
