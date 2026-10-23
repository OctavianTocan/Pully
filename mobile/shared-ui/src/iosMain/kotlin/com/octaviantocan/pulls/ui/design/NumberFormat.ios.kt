package com.octaviantocan.pulls.ui.design

import platform.Foundation.*

actual fun groupedInteger(value: Int): String = NSNumberFormatter().apply {
    locale = NSLocale.currentLocale
    numberStyle = NSNumberFormatterDecimalStyle
    minimumFractionDigits = 0uL
    maximumFractionDigits = 0uL
}.stringFromNumber(NSNumber(int = value)) ?: value.toString()
