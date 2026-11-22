package jdsp.irbuilder

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Synthetic Linkwitz-Riley 4th-order software bands, with optional per-band delays. */
object Fixtures {
    data class C(val re: Double, val im: Double) {
        operator fun times(o: C) = C(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun div(o: C): C { val d = o.re * o.re + o.im * o.im; return C((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d) }
        operator fun plus(o: C) = C(re + o.re, im + o.im)
        fun scale(x: Double) = C(re * x, im * x)
    }

    private fun bw2(f: Double, f0: Double, high: Boolean): C {
        val s = C(0.0, f / f0)
        val den = s * s + s.scale(sqrt(2.0)) + C(1.0, 0.0)
        return (if (high) s * s else C(1.0, 0.0)) / den
    }

    private fun lr4(f: Double, f0: Double, high: Boolean) = bw2(f, f0, high).let { it * it }
    private fun delay(f: Double, seconds: Double) = C(cos(-2 * PI * f * seconds), sin(-2 * PI * f * seconds))

    fun band(b: Band, f: Double, lowMid: Double = 150.0, midHigh: Double = 3500.0, delaySeconds: Double = 0.0, gainDb: Double = 0.0): C {
        val base = when (b) {
            Band.LOW -> lr4(f, lowMid, false)
            Band.MID -> lr4(f, lowMid, true) * lr4(f, midHigh, false)
            Band.HIGH -> lr4(f, midHigh, true)
            Band.COMBINED -> error("use sum")
        }
        // A common 3 ms acoustic arrival relative to the timing reference.
        return (base * delay(f, 0.003 + delaySeconds)).scale(Math.pow(10.0, (80 + gainDb) / 20))
    }

    /**
     * Writes a REW-style text export. [removedDelayMs] mimics REW taking that delay out of the
     * phase and noting it in the header ("Delay x ms"), which the importer must put back.
     */
    fun writeRew(
        file: File, response: (Double) -> C, minHz: Double = 10.0, maxHz: Double = 22000.0, points: Int = 3000,
        removedDelayMs: Double? = null, smoothing: String = "1/48 octave",
    ) {
        val sb = StringBuilder("* Measurement data measured by REW V5.40\n* Smoothing: $smoothing\n")
        if (removedDelayMs != null) sb.append("* Delay %.4f ms (using estimated IR delay relative to start of IR)\n".format(java.util.Locale.ROOT, removedDelayMs))
        sb.append("* Freq(Hz) SPL(dB) Phase(degrees)\n")
        val d = (removedDelayMs ?: 0.0) / 1000.0
        for (i in 0 until points) {
            val f = minHz * Math.pow(maxHz / minHz, i / (points - 1.0))
            val h = response(f) * C(cos(2 * PI * f * d), sin(2 * PI * f * d))
            sb.append("%.6f %.6f %.6f\n".format(java.util.Locale.ROOT, f, 20 * log10(hypot(h.re, h.im)), Math.toDegrees(atan2(h.im, h.re))))
        }
        file.writeText(sb.toString())
    }

    /**
     * Writes Low/Mid/High (and optionally combined) REW files for each seat/position/channel.
     * [delays] gives each band's extra delay per seat.
     */
    fun measurementSet(
        dir: File = createTempDirectory("irb").toFile(),
        positions: List<String> = listOf("centre", "p2"),
        delays: (Seat, Band) -> Double = { _, _ -> 0.0 },
        validationPositions: Set<String> = emptySet(),
        combined: Boolean = false,
        reference: (Channel) -> String = { if (it == Channel.L) "R-ref" else "L-ref" },
        inverted: (Seat, Band) -> Boolean = { _, _ -> false },
    ): List<Measurement> {
        fun sign(seat: Seat, b: Band) = if (inverted(seat, b)) -1.0 else 1.0
        val out = ArrayList<Measurement>()
        for (seat in Seat.entries) for (pos in positions) for (ch in Channel.entries) {
            val wiggle = (pos.hashCode() % 5) * 0.02
            for (b in Band.BRANCHES) {
                val file = File(dir, "${seat.name}_${pos}_${ch.name}_${b.fileName}.txt")
                writeRew(file, { f -> band(b, f, delaySeconds = delays(seat, b) * (1 + wiggle)).scale(sign(seat, b)) })
                out += Measurement(file.path, seat, pos, ch, b, reference(ch), pos in validationPositions)
            }
            if (combined) {
                val file = File(dir, "${seat.name}_${pos}_${ch.name}_combined.txt")
                writeRew(file, { f -> Band.BRANCHES.map { b -> band(b, f, delaySeconds = delays(seat, b) * (1 + wiggle)).scale(sign(seat, b)) }.reduce { a, c -> a + c } })
                out += Measurement(file.path, seat, pos, ch, Band.COMBINED, reference(ch), pos in validationPositions)
            }
        }
        return out
    }
}
