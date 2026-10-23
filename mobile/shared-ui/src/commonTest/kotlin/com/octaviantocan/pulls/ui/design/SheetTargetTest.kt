package com.octaviantocan.pulls.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals

class SheetTargetTest {
    private val speed = 500f
    private val gone = 2000f

    @Test fun slowReleaseGoesToTheNearestRest() {
        val rests = listOf(0f, 1000f)
        assertEquals(0f, sheetTarget(400f, 0f, speed, rests, gone))
        assertEquals(1000f, sheetTarget(600f, 0f, speed, rests, gone))
    }

    @Test fun pulledWellPastTheLowestRestDismisses() {
        // 30% of the way from the lowest rest (1000) to gone (2000) is 1300.
        assertEquals(1000f, sheetTarget(1250f, 100f, speed, listOf(0f, 1000f), gone))
        assertEquals(gone, sheetTarget(1350f, 100f, speed, listOf(0f, 1000f), gone))
        assertEquals(849f, sheetTarget(400f, 0f, speed, listOf(0f), 849f))
        assertEquals(0f, sheetTarget(200f, 0f, speed, listOf(0f), 849f))
    }

    @Test fun aThrowDownGoesToTheNextPlaceBelow() {
        val rests = listOf(0f, 1000f)
        assertEquals(1000f, sheetTarget(0f, 1500f, speed, rests, gone))
        assertEquals(gone, sheetTarget(1000f, 1500f, speed, rests, gone))
        assertEquals(849f, sheetTarget(0f, 1500f, speed, listOf(0f), 849f))
        // A small pull and a throw is enough on a sheet with one rest.
        assertEquals(gone, sheetTarget(20f, 700f, speed, listOf(0f), gone))
    }

    @Test fun aThrowUpGoesToTheNextPlaceAbove() {
        val rests = listOf(0f, 1000f)
        assertEquals(0f, sheetTarget(1000f, -1500f, speed, rests, gone))
        assertEquals(0f, sheetTarget(700f, -1500f, speed, rests, gone))
        assertEquals(0f, sheetTarget(0f, -1500f, speed, rests, gone))
    }

    @Test fun aSlowThrowIsJustARelease() {
        assertEquals(0f, sheetTarget(300f, -400f, speed, listOf(0f, 1000f), gone))
        assertEquals(1000f, sheetTarget(700f, 400f, speed, listOf(0f, 1000f), gone))
    }

    @Test fun aShortSheetSlidesBetweenItsNormalAndTallHeights() {
        // A 600px sheet on a 2000px panel: tall is 0, normal is 1400, and 30% of what shows (180px) below normal closes it.
        val rests = listOf(0f, 1400f)
        assertEquals(0f, sheetTarget(1400f, -1500f, speed, rests, gone))
        assertEquals(1400f, sheetTarget(0f, 1500f, speed, rests, gone))
        assertEquals(gone, sheetTarget(1400f, 1500f, speed, rests, gone))
        assertEquals(0f, sheetTarget(600f, 0f, speed, rests, gone))
        assertEquals(1400f, sheetTarget(900f, 0f, speed, rests, gone))
        assertEquals(1400f, sheetTarget(1550f, 0f, speed, rests, gone))
        assertEquals(gone, sheetTarget(1600f, 0f, speed, rests, gone))
    }

    @Test fun aThrowUpPastTheOnlyRestStaysAtIt() {
        // A sheet with no tall height rests at its content only; a throw up from below goes back to that rest.
        assertEquals(1400f, sheetTarget(1500f, -1500f, speed, listOf(1400f), gone))
        assertEquals(1400f, sheetTarget(1400f, -1500f, speed, listOf(1400f), gone))
    }
}
