package com.octaviantocan.pulls.ui.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.octaviantocan.pulls.sound.NativeGestureFeedback
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SheetScrollTest {
    @Test
    fun contentThatReachesItsTopCannotStartPullingThePanelDuringTheSameDrag() = runTest {
        val position = SheetPosition {}.apply {
            half = 1_000f
            measured(2_000, this@runTest, false)
            measuredContent(1_800, this@runTest, false)
        }
        val connection = SheetScroll(position, this, 500f, { false }, { error("Unexpected dismissal") })
        connection.onPostScroll(Offset(0f, 80f), Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(Offset.Zero, connection.onPostScroll(Offset.Zero, Offset(0f, 100f), NestedScrollSource.UserInput))
        assertEquals(1_000f, position.offset)
        assertFalse(position.held)
        assertEquals(Velocity.Zero, connection.onPreFling(Velocity(0f, 300f)))
        // A new finger-down at the top can move the panel.
        assertEquals(Offset(0f, 100f), connection.onPostScroll(Offset.Zero, Offset(0f, 100f), NestedScrollSource.UserInput))
        assertEquals(1_100f, position.offset)
    }

    @Test
    fun anUpwardFlingThatLiftsThePanelContinuesIntoTheContent() = runTest {
        val position = SheetPosition {}.apply {
            half = 1_000f
            measured(2_000, this@runTest, false)
            measuredContent(1_800, this@runTest, false)
        }
        val connection = SheetScroll(position, this, 500f, { false }, { error("Unexpected dismissal") })
        assertEquals(Offset(0f, -300f), connection.onPreScroll(Offset(0f, -300f), NestedScrollSource.UserInput))
        assertEquals(Velocity.Zero, connection.onPreFling(Velocity(0f, -1_500f)))
        assertEquals(0f, position.offset)
        assertFalse(position.held)
    }

    @Test
    fun sheetLandingAndDismissalThresholdsRetainSeparateTouchOnlyRoles() = runTest {
        val roles = mutableListOf<NativeGestureFeedback>()
        val position = SheetPosition(roles::add).apply {
            half = 1_000f
            measured(2_000, this@runTest, false)
            measuredContent(1_800, this@runTest, false)
        }
        position.dragBy(301f)
        position.dragBy(-5f)
        position.dragBy(-500f)
        position.settle(this, -1_500f, 500f, false) { error("Unexpected dismissal") }
        assertEquals(
            listOf(NativeGestureFeedback.Sheet, NativeGestureFeedback.Threshold, NativeGestureFeedback.Threshold, NativeGestureFeedback.Sheet),
            roles,
        )
    }
}
