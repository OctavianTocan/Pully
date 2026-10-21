package com.octaviantocan.pulls.sound

import kotlin.test.Test

class NativeSoundInitializationTest {
    @Test
    fun soundsAreSilentBeforeTheEngineIsBuilt() {
        // Nothing is installed in a unit test, and a tap must still never throw.
        Sounds.play(Cue.Tap)
        Sounds.tap()
        Sounds.sample(Cue.Success, Material.Bubble)
    }
}
