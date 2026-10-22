package com.octaviantocan.pulls.sound

import androidx.compose.ui.semantics.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CueModifiersTest {
    @Test
    fun aControlsRoleChoosesItsCue() {
        assertEquals(Cue.Toggle, cueFor(Role.Switch))
        assertEquals(Cue.Toggle, cueFor(Role.Checkbox))
        assertEquals(Cue.Select, cueFor(Role.Tab))
        assertEquals(Cue.Select, cueFor(Role.RadioButton))
        assertEquals(Cue.Tap, cueFor(Role.Button))
        assertEquals(Cue.Tap, cueFor(null))
    }

    @Test
    fun aLaterTabIsForwardAndAnEarlierOneIsBack() {
        assertEquals(Direction.Forward, directionOf(0, 2))
        assertEquals(Direction.Back, directionOf(3, 1))
        assertNull(directionOf(1, 1))
    }

    @Test
    fun aSwitchTurningOnIsForward() {
        assertEquals(Direction.Forward, directionOf(true))
        assertEquals(Direction.Back, directionOf(false))
    }

    @Test
    fun committedActivationKeepsNativeFeedbackBeforeTheActionAndCarriesTouchInput() {
        val order = mutableListOf<String>()
        ControlFeedback.install(object : NativeControlFeedback {
            override fun play(cue: Cue, emphasis: Emphasis, direction: Direction?, input: Input?) {
                assertEquals(Cue.Tap, cue)
                assertEquals(Emphasis.Subtle, emphasis)
                assertEquals(Input.Touch, input)
                order += "feedback"
            }
            override fun hold() { error("A tap is not a hold") }
            override fun gesture(role: NativeGestureFeedback) { error("A tap is not a gesture threshold") }
        })
        cued(onClick = { order += "action" })()
        assertEquals(listOf("feedback", "action"), order)
    }
}
