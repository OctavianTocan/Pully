package com.octaviantocan.pulls.core.markdown

import kotlin.test.Test
import kotlin.test.assertTrue

class PortableMarkdownParityTest {
    @Test
    fun portableGfmAstMatchesTheExistingAndroidParser() {
        val failures = markdownGoldens.mapNotNull { fixture ->
            val actual = snapshot(parsePortableMarkdown(fixture.s("source")))
            if (fixture["tree"] == actual) null else "${fixture.s("id")}: expected ${fixture["tree"]}, actual $actual"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
