package jdsp.irbuilder

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SweepRecordingTest {
    private val fs = 44100

    /** A car-like impulse: pre-roll noise, a direct sound, a 40 Hz cabin mode ringing for ~0.4 s, a diffuse tail. */
    private fun carImpulse(): DoubleArray {
        val r = Random(1)
        val x = DoubleArray(fs * 3) { r.nextDouble(-1.0, 1.0) * 1e-4 }
        val p = fs
        x[p] = 1.0
        for (n in 1 until fs) {
            val t = n.toDouble() / fs
            x[p + n] += 0.15 * exp(-t / 0.08) * sin(2 * PI * 40 * t) + 0.05 * exp(-t / 0.02) * r.nextDouble(-1.0, 1.0)
        }
        return x
    }

    /** A recorded 4 s log sweep, 20 Hz to 16 kHz, with louder bass (like a car) and background noise. */
    private fun sweepRecording(): DoubleArray {
        val r = Random(2)
        val t0 = 2 * fs
        val len = 4 * fs
        val x = DoubleArray(9 * fs) { r.nextDouble(-1.0, 1.0) * 4e-3 }
        val k = ln(16000.0 / 20.0)
        for (n in 0 until len) {
            val t = n.toDouble() / fs
            val f = 20.0 * exp(k * t / 4.0)
            val phase = 2 * PI * 20.0 * 4.0 / k * (exp(k * t / 4.0) - 1)
            val gain = if (f < 120) 0.25 else 0.05
            x[t0 + n] += gain * sin(phase)
        }
        return x
    }

    @Test
    fun aRecordedSweepIsToldApartFromAnImpulse() {
        assertTrue(Importer.looksLikeSweepRecording(sweepRecording(), fs))
        assertFalse(Importer.looksLikeSweepRecording(carImpulse(), fs))
    }

    @Test
    fun aSlowlyBuildingRingingImpulseIsNotASweep() {
        // One second of silence, then t·e^(−t/0.2)·sin(2π·40·t): the energy is spread out like a
        // sweep's, but every frequency arrives at once.
        val r = Random(3)
        val x = DoubleArray(3 * fs) { r.nextDouble(-1.0, 1.0) * 1e-5 }
        for (n in fs until 3 * fs) {
            val t = (n - fs).toDouble() / fs
            x[n] += t * exp(-t / 0.2) * sin(2 * PI * 40 * t)
        }
        assertFalse(Importer.looksLikeSweepRecording(x, fs))
    }
}
