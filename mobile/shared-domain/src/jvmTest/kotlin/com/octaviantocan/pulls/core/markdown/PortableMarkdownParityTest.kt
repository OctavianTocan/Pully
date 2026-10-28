package com.octaviantocan.pulls.core.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun anAngleBracketDestinationIsTheTargetOfItsLinkOrImage() {
        // HTML lifted to Markdown writes `![alt](<url>)`; the iPhone dropped the URL (simulator suite, 28 Oct 2026).
        val source = "![shot](<https://x/a.png>) [docs](<https://x/b> \"Title\") [s](<https://x/a b>) ![d](<https://x/l.png> \"dark:https://x/d.png\")\n"
        assertEquals(snapshot(parseMarkdown(source)), snapshot(parsePortableMarkdown(source)))
    }
}
