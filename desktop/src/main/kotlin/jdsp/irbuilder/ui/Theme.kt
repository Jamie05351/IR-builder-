package jdsp.irbuilder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** J_DSP's dashboard palette (SiphonDSP_J `BmwDashboardSkin`). */
object Jdsp {
    val LightBlue = Color(0xFF46B5E8)
    val LightBlueBright = Color(0xFF87CFF0)
    val MBlue = Color(0xFF135BA7)
    val MRed = Color(0xFFE32B3B)
    val MGreen = Color(0xFF3DDC84)
    val MidYellow = Color(0xFFFFCA28)
    val HighPink = Color(0xFFFF1F8F)
    val Gunmetal = Color(0xFF171B21)
    val LowBand = Color(0xFF3D6BFF)
    val MidBand = Color(0xFFFFE500)
    val HighBand = Color(0xFFFF2EB4)
    val Purple = Color(0xFFB14DFF)
    val Orange = Color(0xFFFF6A00)
    val Cyan = Color(0xFF00E5FF)
    val ToggleGreen = Color(0xFF39FF14)
    val TextDim = Color(0xFF9AA3AD)
    val Panel = Color(0xFF0E1115)
    val Hairline = Color(0x22FFFFFF)

    val BodyTop = Color(0xFF050608)
    val BodyMid = Color(0xFF0B0D10)
    val BodyBottom = Color(0xFF14171B)
}

private val scheme = darkColorScheme(
    primary = Jdsp.LightBlue,
    onPrimary = Color.Black,
    secondary = Jdsp.LightBlueBright,
    error = Jdsp.MRed,
    background = Color.Black,
    surface = Jdsp.Gunmetal,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = Jdsp.Panel,
    onSurfaceVariant = Jdsp.TextDim,
)

@Composable
fun JdspTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

/** The recessed glass display box J_DSP draws behind titles and readouts. */
fun Modifier.glassBox(accent: Color, showBorder: Boolean = true, borderAlpha: Float = 1f, corner: Dp = 5.dp): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawBehind
    val r = CornerRadius(corner.toPx())
    val a = borderAlpha.coerceIn(0f, 1f)
    if (showBorder) {
        val g = 1.dp.toPx()
        drawRoundRect(accent.copy(alpha = 0.12f * a), Offset(-g, -g), Size(w + 2 * g, h + 2 * g), CornerRadius(r.x + g), style = Stroke(3.dp.toPx()))
    }
    drawRoundRect(Brush.verticalGradient(0f to Jdsp.BodyTop, 0.35f to Jdsp.BodyMid, 1f to Jdsp.BodyBottom), cornerRadius = r)
    clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, r)) }) {
        val sh = 7.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent), startY = 0f, endY = sh), size = Size(w, sh))
        if (showBorder) drawRect(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.16f * a)), startY = h * 0.35f, endY = h))
        drawRect(Color.White.copy(alpha = 0.10f), Offset(r.x, h - 1.dp.toPx()), Size(w - 2f * r.x, 1.dp.toPx()))
    }
    if (showBorder) {
        val hair = 2.dp.toPx()
        drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(hair, hair), Size(w - 2 * hair, h - 2 * hair), CornerRadius((r.x - 1.5.dp.toPx()).coerceAtLeast(0f)), style = Stroke(1.dp.toPx()))
        val ring = 1.25.dp.toPx()
        drawRoundRect(accent.copy(alpha = a), Offset(ring / 2, ring / 2), Size(w - ring, h - ring), r, style = Stroke(ring))
    }
}

/** A gunmetal panel with an accent-titled glass header, like J_DSP's workspace cards. */
@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = Jdsp.LightBlue,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2027), Jdsp.Gunmetal)))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.glassBox(accent, borderAlpha = 0.6f).padding(horizontal = 12.dp, vertical = 5.dp)) {
                Text(title.uppercase(), color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            }
            Box(Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = Jdsp.LightBlue, enabled: Boolean = true) {
    val c = if (enabled) accent else Jdsp.TextDim.copy(alpha = 0.5f)
    Box(
        modifier
            .glassBox(c, borderAlpha = if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color.White else Jdsp.TextDim, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

/** A single-line text field drawn as a glass readout. */
@Composable
fun GlassField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, accent: Color = Jdsp.LightBlue, width: Dp = 110.dp, error: Boolean = false) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
        cursorBrush = SolidColor(accent),
        modifier = modifier
            .width(width)
            .glassBox(if (error) Jdsp.MRed else accent, borderAlpha = 0.7f)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    )
}

/** Field bound to a number; keeps the raw text while editing and reports parse failures. */
@Composable
fun NumberField(value: Double, onValue: (Double) -> Unit, width: Dp = 110.dp, accent: Color = Jdsp.LightBlue, format: (Double) -> String = { trimNumber(it) }) {
    var text by remember(value) { mutableStateOf(format(value)) }
    val parsed = text.trim().toDoubleOrNull()
    GlassField(text, {
        text = it
        it.trim().toDoubleOrNull()?.takeIf { v -> v.isFinite() }?.let(onValue)
    }, width = width, accent = accent, error = parsed == null)
}

fun trimNumber(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.4g", v).let { s ->
    if (s.contains('e')) s else s.trimEnd('0').trimEnd('.')
}

@Composable
fun <T> GlassDropdown(value: T, options: List<T>, label: (T) -> String, onSelect: (T) -> Unit, width: Dp = 120.dp, accent: Color = Jdsp.LightBlue) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.width(width).glassBox(accent, borderAlpha = 0.7f).clickable { open = true }.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label(value), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text("▾", color = accent, fontSize = 12.sp)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(Jdsp.Gunmetal)) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(label(o), color = Color.White) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

/** J_DSP-style glass switch: a capsule with a lit thumb. */
@Composable
fun GlassSwitch(checked: Boolean, onChange: (Boolean) -> Unit, accent: Color = Jdsp.ToggleGreen) {
    Box(
        Modifier.width(54.dp).height(22.dp).glassBox(if (checked) accent else Jdsp.TextDim, borderAlpha = if (checked) 1f else 0.5f, corner = 11.dp)
            .clickable { onChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.width(16.dp).height(16.dp).clip(RoundedCornerShape(8.dp)).background(if (checked) accent else Color(0xFF3A4048)))
    }
}

@Composable
fun Labeled(label: String, modifier: Modifier = Modifier, hint: String? = null, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Jdsp.TextDim, fontSize = 12.sp)
        content()
        if (hint != null) Text(hint, color = Jdsp.TextDim.copy(alpha = 0.75f), fontSize = 11.sp, modifier = Modifier.widthIn(max = 260.dp))
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, accent: Color = Jdsp.LightBlue) {
    Box(
        Modifier.glassBox(if (selected) accent else Jdsp.TextDim, borderAlpha = if (selected) 1f else 0.35f).clickable(onClick = onClick)
            .padding(PaddingValues(horizontal = 12.dp, vertical = 6.dp)),
    ) { Text(text, color = if (selected) Color.White else Jdsp.TextDim, fontSize = 13.sp) }
}

@Composable
fun Note(text: String, color: Color = Jdsp.TextDim) = Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth())

/** "Sum loss −1.2 → −0.4 dB, worst dip −6.0 → −2.1 dB" for a seat. */
fun lossText(before: jdsp.irbuilder.SumLoss.Reading?, after: jdsp.irbuilder.SumLoss.Reading?): String? {
    if (before == null || after == null) return null
    return "sum loss %.1f → %.1f dB, worst dip %.1f → %.1f dB".format(java.util.Locale.ROOT, before.averageDb, after.averageDb, before.worstDb, after.worstDb)
}

fun bandColor(b: jdsp.irbuilder.Band) = when (b) {
    jdsp.irbuilder.Band.LOW -> Jdsp.LowBand
    jdsp.irbuilder.Band.MID -> Jdsp.MidBand
    jdsp.irbuilder.Band.HIGH -> Jdsp.HighBand
    jdsp.irbuilder.Band.COMBINED -> Jdsp.Cyan
}
