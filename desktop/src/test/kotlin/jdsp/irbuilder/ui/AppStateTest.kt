package jdsp.irbuilder.ui

import jdsp.irbuilder.AlignOptions
import jdsp.irbuilder.BandAlign
import jdsp.irbuilder.BandSetting
import jdsp.irbuilder.JdspTiming
import jdsp.irbuilder.Measurement
import jdsp.irbuilder.Tool
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class AppStateTest {
    private fun files(dir: File): List<Measurement> {
        val out = ArrayList<Measurement>()
        for (seat in jdsp.irbuilder.Seat.entries) for (ch in jdsp.irbuilder.Channel.entries) for (b in jdsp.irbuilder.Band.BRANCHES) {
            val file = File(dir, "${seat.name}_c_${ch.name}_${b.fileName}.txt")
            file.writeText("* Measurement data measured by REW V5.40\n" + (1..3000).joinToString("\n") { i ->
                val f = 10.0 * Math.pow(2200.0, i / 2999.0)
                val db = when (b) { jdsp.irbuilder.Band.LOW -> if (f < 150) 80.0 else 80 - 24 * Math.log(f / 150) / Math.log(2.0)
                    jdsp.irbuilder.Band.MID -> 80.0
                    else -> if (f > 3500) 80.0 else 80 - 24 * Math.log(3500 / f) / Math.log(2.0) }
                "$f $db 0"
            })
            out += Measurement(file.path, seat, "c", ch, b, if (ch == jdsp.irbuilder.Channel.L) "R" else "L")
        }
        return out
    }

    @Test
    fun reloadingMeasurementsDropsResultsFromTheOldData() {
        val dir = createTempDirectory("irb-state").toFile()
        val ms = files(dir)
        lateinit var state: AppState
        runBlocking {
            state = AppState(this)
            state.startTool(Tool.BAND_ALIGN)
            state.measurements.addAll(ms)
            state.updateAlign(AlignOptions(JdspTiming(BandSetting(1.0), BandSetting(1.0))))
            state.runChecks()
        }
        val first = assertNotNull(state.set)
        state.alignment = BandAlign.solve(first, state.align)
        // Same file names, re-exported: running the checks again re-reads them.
        files(dir)
        runBlocking { state.rescope(this); state.runChecks() }
        assertNotSame(first, state.set)
        assertNull(state.alignment)
        assertNull(state.design)
    }
}
