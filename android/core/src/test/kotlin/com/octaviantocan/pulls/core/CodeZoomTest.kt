package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CodeZoomTest {
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.001f)

    @Test
    fun aLineOfCodeGrowsWithTheZoomAndKeepsItsPointUnderTheFingers() {
        // 10px into a 20px line at 300px, zoomed 1.5 times: the line is 30px tall and its top moves up 5px.
        near(285f, rowTopAfterZoom(screenY = 300f, within = 10f, scale = 1.5f, grows = true))
    }

    @Test
    fun aNoteKeepsItsSize() {
        near(290f, rowTopAfterZoom(screenY = 300f, within = 10f, scale = 1.5f, grows = false))
    }

    @Test
    fun theCharacterUnderTheFingersStaysThereSideways() {
        // The code starts at 60px and is scrolled 40px; the fingers are at 200px, 180px into the code.
        // Twice as big, the code starts at 120px and that character is 360px into it.
        near(280f, codeScrollAfterZoom(offset = 40f, codeStart = 60f, newCodeStart = 120f, contentX = 200f, screenX = 200f, scale = 2f))
    }

    @Test
    fun theSidewaysScrollNeverGoesPastTheStart() {
        near(0f, codeScrollAfterZoom(offset = 0f, codeStart = 60f, newCodeStart = 36f, contentX = 70f, screenX = 300f, scale = 0.6f))
    }
}
