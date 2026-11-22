package jdsp.irbuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SumLossTest {
    private val fs = 48000.0
    private val f = geomspace(20.0, 20000.0, 768)
    private fun xo(t: XoType, hz: Double, high: Boolean) = JdspFilters.response(XoFilter(hz, t), high, f, fs)
    private fun near(hz: Double) = f.indices.minBy { kotlin.math.abs(f[it] - hz) }

    @Test
    fun anInPhaseLr4PairLosesNothingEvenAtItsSteepCorner() {
        val c = SumLoss.curveDb(listOf(xo(XoType.LR4, 150.0, false), xo(XoType.LR4, 150.0, true)), f)
        for (i in f.indices) if (f[i] in 40.0..600.0) assertTrue(c[i] > -0.1, "${f[i]} Hz: ${c[i]} dB")
        val r = SumLoss.read(listOf(xo(XoType.LR4, 150.0, false), xo(XoType.LR4, 150.0, true)), f, 60.0..375.0)!!
        assertTrue(r.averageDb > -0.05 && r.worstDb > -0.1, r.toString())
    }

    @Test
    fun aBw2PairInPhaseCancelsAtTheCornerAndInvertedDoesNot() {
        val lp = xo(XoType.BW2, 150.0, false)
        val hp = xo(XoType.BW2, 150.0, true)
        val same = SumLoss.read(listOf(lp, hp), f, 60.0..375.0)!!
        val inv = SumLoss.read(listOf(lp, CVec(DoubleArray(f.size) { -hp.re[it] }, DoubleArray(f.size) { -hp.im[it] })), f, 60.0..375.0)!!
        assertTrue(same.worstDb < -10, "in phase worst ${same.worstDb}")
        assertTrue(inv.worstDb > -0.1, "inverted worst ${inv.worstDb}")
        assertEquals(0.0, SumLoss.curveDb(listOf(lp, hp), f)[near(5000.0)], 0.05)
    }

    @Test
    fun noiseFloorsAreLeftOut() {
        // Two bands that both stop below 1 kHz: above ~3 kHz both are 40+ dB down, so no loss is read there.
        val a = xo(XoType.LR4, 150.0, false)
        val b = xo(XoType.LR4, 300.0, false)
        val c = SumLoss.curveDb(listOf(a, b), f)
        assertTrue(c[near(8000.0)].isNaN())
        assertTrue(!c[near(100.0)].isNaN())
    }
}
