package com.octaviantocan.pulls.ui.design

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormatTest {
    @Test
    fun diffCountsKeepDeviceGroupingAndNumerals() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("1,234", groupedInteger(1234))
            assertEquals("-1,234", groupedInteger(-1234))
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.234", groupedInteger(1234))
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("١٬٢٣٤", groupedInteger(1234))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
