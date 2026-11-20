package jdsp.irbuilder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

class Series(val label: String, val x: DoubleArray, val y: DoubleArray, val color: Color, val dashed: Boolean = false, val width: Float = 2f)

/** A frequency span to shade (the correction regions). */
class Shade(val from: Double, val to: Double, val color: Color)

private val GridColor = Color(0x22FFFFFF)
private val AxisText = TextStyle(color = Jdsp.TextDim, fontSize = 10.sp)

/**
 * Line plot on a log-frequency (or linear) x axis with dB/degree/sample y axis, drawn inside a
 * J_DSP glass box. y range is fixed by the caller so before/after comparisons stay honest.
 */
@Composable
fun LinePlot(
    series: List<Series>,
    yMin: Double,
    yMax: Double,
    yUnit: String,
    modifier: Modifier = Modifier,
    logX: Boolean = true,
    xUnit: String = "Hz",
    shades: List<Shade> = emptyList(),
    height: Dp = 260.dp,
    accent: Color = Jdsp.LightBlue,
) {
    val measurer = rememberTextMeasurer()
    val xs = series.flatMap { it.x.asList() }.filter { !logX || it > 0 }
    if (xs.isEmpty()) return
    val xMin = xs.min()
    val xMax = xs.max()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(height).glassBox(accent, borderAlpha = 0.5f)) {
            val left = 46f
            val right = size.width - 12f
            val top = 10f
            val bottom = size.height - 22f
            fun tx(x: Double): Float {
                val t = if (logX) (log10(x) - log10(xMin)) / (log10(xMax) - log10(xMin)) else (x - xMin) / (xMax - xMin)
                return (left + t * (right - left)).toFloat()
            }
            fun ty(y: Double): Float = (bottom - (y.coerceIn(yMin, yMax) - yMin) / (yMax - yMin) * (bottom - top)).toFloat()

            shades.forEach { s ->
                val a = tx(s.from.coerceIn(xMin, xMax))
                val b = tx(s.to.coerceIn(xMin, xMax))
                drawRect(s.color, Offset(a, top), Size(b - a, bottom - top))
            }
            // Y grid.
            val yStep = niceStep((yMax - yMin) / 6)
            var y = ceil(yMin / yStep) * yStep
            while (y <= yMax + 1e-9) {
                drawLine(GridColor, Offset(left, ty(y)), Offset(right, ty(y)), 1f)
                val label = trimNumber(y)
                val m = measurer.measure(label, AxisText)
                drawText(m, topLeft = Offset(left - m.size.width - 6f, ty(y) - m.size.height / 2f))
                y += yStep
            }
            // X grid.
            val ticks = if (logX) logTicks(xMin, xMax) else linearTicks(xMin, xMax)
            ticks.forEach { (x, major) ->
                drawLine(if (major) GridColor.copy(alpha = 0.25f) else GridColor.copy(alpha = 0.08f), Offset(tx(x), top), Offset(tx(x), bottom), 1f)
                if (major) {
                    val label = if (logX) hzLabel(x) else trimNumber(x)
                    val m = measurer.measure(label, AxisText)
                    drawText(m, topLeft = Offset(tx(x) - m.size.width / 2f, bottom + 4f))
                }
            }
            val unit = measurer.measure(yUnit, AxisText)
            drawText(unit, topLeft = Offset(4f, top))
            val xu = measurer.measure(xUnit, AxisText)
            drawText(xu, topLeft = Offset(right - xu.size.width, bottom + 4f))

            series.forEach { s ->
                val path = Path()
                var started = false
                for (i in s.x.indices) {
                    if (logX && s.x[i] <= 0) continue
                    if (!s.y[i].isFinite()) { started = false; continue }
                    val px = tx(s.x[i])
                    val py = ty(s.y[i])
                    if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
                }
                drawPath(path, s.color, style = Stroke(s.width, pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null))
            }
        }
        Legend(series)
    }
}

@Composable
fun Legend(series: List<Series>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        series.distinctBy { it.label }.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(18.dp).height(3.dp).drawBehind {
                    drawLine(s.color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height,
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(5f, 3f)) else null)
                })
                Text(s.label, color = Jdsp.TextDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun Swatch(color: Color) = Box(Modifier.size(10.dp).drawBehind { drawCircle(color) })

private fun niceStep(raw: Double): Double {
    val p = 10.0.pow(floor(log10(raw)))
    val m = raw / p
    return p * when {
        m <= 1 -> 1.0
        m <= 2 -> 2.0
        m <= 5 -> 5.0
        else -> 10.0
    }
}

private fun logTicks(min: Double, max: Double): List<Pair<Double, Boolean>> {
    val out = ArrayList<Pair<Double, Boolean>>()
    var decade = 10.0.pow(floor(log10(min)))
    while (decade <= max) {
        for (k in 1..9) {
            val x = decade * k
            if (x in min..max) out += x to (k == 1 || k == 2 || k == 5)
        }
        decade *= 10
    }
    return out
}

private fun linearTicks(min: Double, max: Double): List<Pair<Double, Boolean>> {
    val step = niceStep((max - min) / 8)
    val out = ArrayList<Pair<Double, Boolean>>()
    var x = ceil(min / step) * step
    while (x <= max) { out += x to true; x += step }
    return out
}

private fun hzLabel(x: Double) = if (x >= 1000) trimNumber(x / 1000) + "k" else trimNumber(x)
