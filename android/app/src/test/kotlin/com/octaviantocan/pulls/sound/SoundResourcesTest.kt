package com.octaviantocan.pulls.sound

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The canonical generated recipe remains an Android asset as well as the shared test fixture source. */
class SoundResourcesTest {
    @Test
    fun theSourceFilesExist() {
        assertTrue(File("src/main/assets/cuelume/recipes.json").isFile)
    }
}
