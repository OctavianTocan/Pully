package com.octaviantocan.pulls.core.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Raw HTML in GitHub Markdown, as review comments and bots write it, read as the nodes GitHub shows. */
class GithubHtmlTest {
    private fun doc(md: String) = liftHtml(parseMarkdown(md))

    private fun text(node: Node): String = when (node) {
        is Text -> node.literal
        is Code -> node.literal
        else -> node.children.joinToString("") { text(it) }
    }

    @Test
    fun aDetailsAroundMarkdownBlocksFoldsThemUnderItsSummary() {
        val blocks = doc("Intro\n\n<details>\n<summary>Learn more</summary>\n\nInside **bold**.\n\nMore.\n\n</details>\n\nAfter").children
        assertEquals(3, blocks.size)
        val details = assertIs<DetailsBlock>(blocks[1])
        assertEquals("Learn more", details.summary)
        assertEquals(false, details.open)
        assertEquals(listOf("Inside bold.", "More."), details.children.map(::text))
        assertEquals("After", text(blocks[2]))
    }

    @Test
    fun nestedDetailsFoldInsideTheirParentAndOpenOnesStayOpen() {
        val outer = assertIs<DetailsBlock>(doc("<details open>\n<summary>Outer</summary>\n\n<details>\n<summary>Inner</summary>\n\nDeep\n\n</details>\n\n</details>").children.single())
        assertTrue(outer.open)
        val inner = assertIs<DetailsBlock>(outer.children.single())
        assertEquals("Inner", inner.summary)
        assertEquals("Deep", text(inner.children.single()))
    }

    @Test
    fun aLinkedBadgePictureIsAnImageInsideItsLinkWithTheDarkSource() {
        val paragraph = doc("""<!-- badge -->
<a href="https://app.devin.ai/review"><picture><source media="(prefers-color-scheme: dark)" srcset="https://x/dark.svg?v=4"><img src="https://x/light.svg?v=4" alt="Devin Review"></picture></a>""").children.single()
        val link = assertIs<Link>(paragraph.children.single())
        assertEquals("https://app.devin.ai/review", link.destination)
        val image = assertIs<Image>(link.children.single())
        assertEquals("https://x/light.svg?v=4", image.destination)
        assertEquals("dark:https://x/dark.svg?v=4", image.title)
        assertEquals("Devin Review", text(image))
    }

    @Test
    fun inlineTagsBecomeTheirEmphasisCodeAndBreaks() {
        val nodes = doc("a <b>bold</b> <kbd>Ctrl</kbd><br>next <sub>small</sub> <i>open").children.single().children
        assertIs<StrongEmphasis>(nodes[1])
        assertEquals("Ctrl", assertIs<Code>(nodes[3]).literal)
        assertIs<HardLineBreak>(nodes[4])
        assertEquals("a bold Ctrlnext small open", nodes.joinToString("") { text(it) })
    }

    @Test
    fun anHtmlBlockIsTheMarkdownItHolds() {
        val blocks = doc("<p align=\"center\"><img src=\"https://x/a.png\" alt=\"shot\" width=\"200\"></p>\n\n<!-- hidden -->").children
        val image = assertIs<Image>(blocks.single().children.single())
        assertEquals("https://x/a.png", image.destination)
    }

    @Test
    fun anAlertKeepsItsQuoteWithItsTitleInBold() {
        val quote = assertIs<BlockQuote>(doc("> [!WARNING]\n> Mind the gap.").children.single())
        val nodes = quote.children.single().children
        assertEquals("Warning", text(assertIs<StrongEmphasis>(nodes[0])))
        assertIs<HardLineBreak>(nodes[1])
        assertEquals("Mind the gap.", text(nodes[2]))
    }
}
