package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CodeZoomTest {
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.001f)

    @Test
    fun thePointUnderTheFingersStaysUnderThemAsTheyPinch() {
        val frame = PinchFrame().next(zoom = 2f, fromX = 100f, fromY = 300f, toX = 100f, toY = 300f, minScale = 0.3f, maxScale = 3f)
        near(2f, frame.scale)
        near(100f, frame.contentX(100f))
        near(300f, frame.contentY(300f))
    }

    @Test
    fun twoFingersMovingTogetherPanTheCode() {
        val frame = PinchFrame().next(zoom = 1f, fromX = 100f, fromY = 300f, toX = 140f, toY = 250f, minScale = 0.3f, maxScale = 3f)
        near(1f, frame.scale)
        near(40f, frame.x)
        near(-50f, frame.y)
        near(100f, frame.contentX(140f))
        near(300f, frame.contentY(250f))
    }

    @Test
    fun aPinchAndAPanTogetherKeepTheSamePointUnderTheFingers() {
        val start = PinchFrame().next(1.5f, 200f, 400f, 210f, 380f, 0.3f, 3f)
        val point = start.contentX(210f) to start.contentY(380f)
        val next = start.next(1.2f, 210f, 380f, 190f, 420f, 0.3f, 3f)
        near(point.first, next.contentX(190f))
        near(point.second, next.contentY(420f))
    }

    @Test
    fun theScaleStopsAtItsLimits() {
        near(3f, PinchFrame().next(10f, 0f, 0f, 0f, 0f, 0.3f, 3f).scale)
        near(0.3f, PinchFrame().next(0.01f, 0f, 0f, 0f, 0f, 0.3f, 3f).scale)
    }

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
