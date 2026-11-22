package jdsp.irbuilder.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import jdsp.irbuilder.Band
import jdsp.irbuilder.Channel
import jdsp.irbuilder.Checks
import jdsp.irbuilder.Designer
import jdsp.irbuilder.Measurement
import jdsp.irbuilder.MeasurementSet
import jdsp.irbuilder.Seat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders every step with synthetic data: a smoke test that each screen composes and draws. */
class ScreensRenderTest {
    private fun lr4(f: Double, f0: Double, high: Boolean): Pair<Double, Double> {
        // Butterworth 2nd order squared, s = j f/f0.
        val w = f / f0
        val dr = 1 - w * w
        val di = sqrt(2.0) * w
        val nr = if (high) -w * w else 1.0
        val d2 = dr * dr + di * di
        val hr = (nr * dr) / d2
        val hi = (-nr * di) / d2
        return (hr * hr - hi * hi) to (2 * hr * hi)
    }

    private fun mul(a: Pair<Double, Double>, b: Pair<Double, Double>) = (a.first * b.first - a.second * b.second) to (a.first * b.second + a.second * b.first)

    private fun synthetic(dir: File): List<Measurement> {
        val out = ArrayList<Measurement>()
        val delays = mapOf(Band.LOW to 0.0, Band.MID to 0.0011, Band.HIGH to 0.00125)
        for (seat in Seat.entries) for ((pi, pos) in listOf("centre", "pos2", "held").withIndex()) for (ch in Channel.entries) {
            for (b in Band.entries) {
                val file = File(dir, "${seat.name.lowercase()}_${pos}_${ch.name}_${b.fileName}.txt")
                val sb = StringBuilder("* Measurement data measured by REW V5.40\n")
                for (i in 0 until 2000) {
                    val f = 10.0 * Math.pow(2200.0, i / 1999.0)
                    fun band(x: Band): Pair<Double, Double> {
                        val base = when (x) {
                            Band.LOW -> lr4(f, 150.0, false)
                            Band.MID -> mul(lr4(f, 150.0, true), lr4(f, 3500.0, false))
                            else -> lr4(f, 3500.0, true)
                        }
                        val d = 0.003 + delays.getValue(x) * (1 + 0.1 * pi) + (if (seat == Seat.PASSENGER) 0.0002 else 0.0)
                        return mul(base, cos(-2 * PI * f * d) to sin(-2 * PI * f * d))
                    }
                    val h = if (b == Band.COMBINED) Band.BRANCHES.map(::band).reduce { a, c -> (a.first + c.first) to (a.second + c.second) } else band(b)
                    sb.append("$f ${80 + 20 * log10(hypot(h.first, h.second))} ${Math.toDegrees(atan2(h.second, h.first))}\n")
                }
                file.writeText(sb.toString())
                out += Measurement(file.path, seat, pos, ch, b, if (ch == Channel.L) "R speaker" else "L speaker", pos == "held")
            }
        }
        return out
    }

    @Test
    fun everyStepRenders() {
        val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val state = AppState(CoroutineScope(Dispatchers.Unconfined))
        state.measurements.addAll(synthetic(createTempDirectory("irb-ui").toFile()))
        val set = MeasurementSet.load(state.measurements.toList(), state.options)
        state.set = set
        state.checks = Checks.run(set)
        state.updateAlign(jdsp.irbuilder.AlignOptions(jdsp.irbuilder.JdspTiming(
            lowL = jdsp.irbuilder.BandSetting(2.0), lowR = jdsp.irbuilder.BandSetting(2.0),
            highL = jdsp.irbuilder.BandSetting(1.0), highR = jdsp.irbuilder.BandSetting(1.0),
        )))
        state.alignment = jdsp.irbuilder.BandAlign.solve(set, state.align)
        state.design = Designer.design(set, state.alignment)
        for (step in Step.entries) {
            state.step = step
            val scene = ImageComposeScene(1480, 2400, Density(1f)) { JdspTheme { App(state) } }
            scene.render()
            val image = scene.render(100_000_000)
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(outDir, "${step.ordinal + 1}-${step.name.lowercase()}.png").writeBytes(png)
            scene.close()
            assertTrue(png.size > 10_000)
        }
    }
}
