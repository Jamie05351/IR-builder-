package jdsp.irbuilder.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormatTest {
    @Test
    fun numberFieldTextParsesBackUnderCommaDecimalLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            for (v in listOf(150.5, 0.05, -3.25, 3500.0, 1.0 / 6)) {
                assertEquals(v, trimNumber(v).toDouble(), 1e-3 * maxOf(1.0, kotlin.math.abs(v)))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
