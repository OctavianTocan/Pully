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
    fun soundsAreSilentBeforeTheEngineIsBuilt() {
        // Nothing is installed in a unit test, and a tap must still never throw.
        Sounds.play(Cue.Tap)
        Sounds.tap()
        Sounds.sample(Cue.Success, Material.Bubble)
    }
}
