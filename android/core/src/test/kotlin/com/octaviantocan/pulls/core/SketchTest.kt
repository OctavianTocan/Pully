package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The call trees, file trees and pseudocode a tour stop draws (rule 95), in the fences the tour prompt asks for. */
class SketchTest {
    @Test
    fun aCallTreeIndentedTwoSpacesPerLevelReadsItsDepths() {
        // The tour prompt's own example (packages/core/src/analyze/adapters/llm/tour.test.ts).
        assertEquals(
            listOf(SketchRow(0, "save", ""), SketchRow(1, "parse", ""), SketchRow(2, "cache.get", "")),
            sketchRows("save\n  parse\n    cache.get"),
        )
    }

    @Test
    fun aTreeDrawnWithGuidesReadsTheSameAsOneIndentedWithSpaces() {
        val guides = sketchRows("src/\n├── api/\n│   └── client.ts   # fetch wrapper\n└── App.tsx         # mounts the widget")
        val spaces = sketchRows("src/\n    api/\n        client.ts   # fetch wrapper\n    App.tsx         # mounts the widget")
        val expected = listOf(
            SketchRow(0, "src/", ""),
            SketchRow(1, "api/", ""),
            SketchRow(2, "client.ts", "fetch wrapper"),
            SketchRow(1, "App.tsx", "mounts the widget"),
        )
        assertEquals(expected, guides)
        assertEquals(expected, spaces)
    }

    @Test
    fun asciiGuidesBulletsAndArrowsAreNotPartOfTheName() {
        assertEquals(
            listOf(SketchRow(0, "root", ""), SketchRow(1, "a", ""), SketchRow(1, "b", ""), SketchRow(2, "c()", "")),
            sketchRows("root\n|-- a\n`-- b\n    -> c()"),
        )
        assertEquals(listOf(SketchRow(0, "load()", ""), SketchRow(1, "mount()", "")), sketchRows("- load()\n  → mount()"))
    }

    @Test
    fun aNameThatStartsWithAMarkKeepsIt() {
        assertEquals(listOf(SketchRow(0, "routes/", ""), SketchRow(1, "+page.svelte", "the page")), sketchRows("routes/\n  +page.svelte  # the page"))
        assertEquals(listOf(SketchRow(0, "parse()", "")), sketchRows("`parse()`"))
    }

    @Test
    fun theCommentIsWhateverFollowsItsMark() {
        assertEquals(SketchRow(0, "client.ts", "fetch wrapper"), sketchRows("client.ts // fetch wrapper").single())
        assertEquals(SketchRow(0, "client.ts", "fetch wrapper"), sketchRows("client.ts — fetch wrapper").single())
        assertEquals(SketchRow(0, "client.ts", "new"), sketchRows("client.ts  ← new").single())
        assertEquals(SketchRow(0, "onElement(el)", "binds the listeners"), sketchRows("onElement(el): binds the listeners").single())
        assertEquals(SketchRow(0, "client.ts", "lined up"), sketchRows("client.ts      lined up").single())
        // One space is part of the name: a call tree may name a request.
        assertEquals(SketchRow(0, "fetch POST /items", ""), sketchRows("fetch POST /items").single())
        // A hash inside a name is not a comment.
        assertEquals(SketchRow(0, "owner/repo#12", ""), sketchRows("owner/repo#12").single())
    }

    @Test
    fun aLineThatIsOnlyACommentKeepsItsWords() {
        assertEquals(listOf(SketchRow(0, "", "entry points"), SketchRow(0, "main.ts", "")), sketchRows("# entry points\nmain.ts"))
    }

    @Test
    fun blankLinesAndBareGuidesAreSkipped() {
        assertEquals(listOf(SketchRow(0, "a", ""), SketchRow(1, "b", "")), sketchRows("a\n│\n\n  b"))
    }

    @Test
    fun aRowBackOutAtAColumnNeverSeenTakesTheNearestLevel() {
        assertEquals(listOf(0, 1, 1, 0), sketchRows("a\n    b\n   c\nd").map { it.depth })
    }

    @Test
    fun guidesRunDownWhileASiblingIsStillToCome() {
        // a
        // ├ b
        // │ └ c
        // └ d
        //   └ e
        assertEquals(
            listOf(emptyList(), listOf(true), listOf(true, false), listOf(false), listOf(false, false)),
            treeGuides(listOf(0, 1, 2, 1, 2)),
        )
    }

    @Test
    fun aFolderHasASlashOrRowsUnderIt() {
        val rows = sketchRows("src\n  a.ts\nlib/\nREADME.md")
        assertTrue(isFolderRow(rows, 0))
        assertFalse(isFolderRow(rows, 1))
        assertTrue(isFolderRow(rows, 2))
        assertFalse(isFolderRow(rows, 3))
    }

    @Test
    fun aRowsPathJoinsItsFolders() {
        val rows = sketchRows("./\n  src/\n    api/\n      client.ts\n    App.tsx\n  README.md")
        assertEquals("src/api/client.ts", treePath(rows, 3))
        assertEquals("src/App.tsx", treePath(rows, 4))
        assertEquals("README.md", treePath(rows, 5))
        assertEquals(".github/ci.yml", treePath(sketchRows(".github/\n  ci.yml"), 1))
    }

    private fun kinds(line: String) = pseudoTokens(line).filter { it.text.isNotBlank() }.map { it.text.trim() to it.kind }

    @Test
    fun pseudocodeIsColouredLikeCode() {
        assertEquals(
            listOf(
                "result" to PseudoKind.Plain, "=" to PseudoKind.Operator, "await" to PseudoKind.Keyword, "el." to PseudoKind.Plain,
                "connect" to PseudoKind.Call, "({ interactive:" to PseudoKind.Plain, "true" to PseudoKind.Literal, "})" to PseudoKind.Plain,
            ),
            kinds("result = await el.connect({ interactive: true })"),
        )
        assertEquals(
            listOf("if" to PseudoKind.Keyword, "unmounted" to PseudoKind.Plain, "and" to PseudoKind.Keyword, "result" to PseudoKind.Plain, "==" to PseudoKind.Operator, "\"stale\"" to PseudoKind.Text),
            kinds("if unmounted and result == \"stale\""),
        )
    }

    @Test
    fun commentsNumbersAndApostrophes() {
        assertEquals(listOf("wait" to PseudoKind.Plain, "300" to PseudoKind.Number, "ms" to PseudoKind.Plain, "// then retry" to PseudoKind.Comment), kinds("wait 300ms // then retry"))
        assertEquals(listOf("retry" to PseudoKind.Call, "()" to PseudoKind.Plain, "# once" to PseudoKind.Comment), kinds("retry()  # once"))
        // A hash in a name and a URL's slashes are not comments; an apostrophe in a word is not a quote.
        assertEquals(PseudoKind.Plain, pseudoTokens("owner/repo#12").first { it.text.contains("#") }.kind)
        assertTrue(pseudoTokens("fetch https://example.com").none { it.kind == PseudoKind.Comment })
        assertTrue(pseudoTokens("else: leave the widget's recovery").none { it.kind == PseudoKind.Text })
    }

    @Test
    fun theRunsJoinedGiveBackTheLine() {
        val line = "else if result in { refused, declined }: onFailed(reason) // 'x' 1.5"
        assertEquals(line, pseudoTokens(line).joinToString("") { it.text })
    }

    @Test
    fun theProbeHasEachKindWhereItSays() {
        val at = PSEUDO_PROBE_AT
        assertEquals("if", PSEUDO_PROBE.substring(at.getValue(PseudoKind.Keyword), 2))
        assertEquals("f(", PSEUDO_PROBE.substring(at.getValue(PseudoKind.Call), 5))
        assertEquals(" ", PSEUDO_PROBE.substring(at.getValue(PseudoKind.Plain), 3))
        assertEquals("==", PSEUDO_PROBE.substring(at.getValue(PseudoKind.Operator), 10))
        assertEquals("s", PSEUDO_PROBE.substring(at.getValue(PseudoKind.Text), 13))
        assertEquals("1", PSEUDO_PROBE.substring(at.getValue(PseudoKind.Number), 20))
        assertEquals("True", PSEUDO_PROBE.substring(at.getValue(PseudoKind.Literal), 28))
        assertEquals("c", PSEUDO_PROBE.substring(at.getValue(PseudoKind.Comment)))
        assertEquals(PseudoKind.entries.toSet(), at.keys)
    }
}
