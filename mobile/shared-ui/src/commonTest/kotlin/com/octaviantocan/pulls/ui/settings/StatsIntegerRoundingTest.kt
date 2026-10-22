package com.octaviantocan.pulls.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class StatsIntegerRoundingTest {
    @Test
    fun integerUnitsKeepAdjacentPositiveHalfValuesDistinct() {
        for ((value, expected) in listOf(
            0.49999999999999994 to 0,
            0.5 to 1,
            0.5000000000000001 to 1,
            1.4999999999999998 to 1,
            1.5 to 2,
        )) {
            assertEquals("$expected ms", StatsFormat.ms(value), "milliseconds: $value")
            assertEquals("$expected s", StatsFormat.seconds(value), "seconds: $value")
        }
    }

    @Test
    fun negativeTiesStillRoundTowardPositiveInfinity() {
        for ((value, expected) in listOf(
            -0.5000000000000001 to -1,
            -0.5 to 0,
            -0.49999999999999994 to 0,
            -1.5000000000000002 to -2,
            -1.5 to -1,
        )) {
            assertEquals("$expected ms", StatsFormat.ms(value), "milliseconds: $value")
            assertEquals("$expected s", StatsFormat.seconds(value), "seconds: $value")
        }
    }
}
