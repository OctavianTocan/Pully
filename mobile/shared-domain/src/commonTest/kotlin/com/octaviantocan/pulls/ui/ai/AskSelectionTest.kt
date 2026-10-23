package com.octaviantocan.pulls.ui.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors `components/ask/selection.test.ts`, plus the critique helpers ported from core and the store. */
class AskSelectionTest {
    @Test
    fun keepsARangeOnOneSide() {
        assertEquals(AskSelection("a.ts", "additions", 10, 20), askSelectionOf("a.ts", "additions", 20, 10))
    }

    @Test
    fun turnsASingleLineIntoAOneLineRange() {
        assertEquals(AskSelection("a.ts", "deletions", 4, 4), askSelectionOf("a.ts", "deletions", 4, null))
    }

    @Test
    fun neverStartsAfterTheEnd() {
        assertEquals(AskSelection("a.ts", "additions", 5, 5), askSelectionOf("a.ts", "additions", 5, 9))
    }

    @Test
    fun labelsOneLineAndARange() {
        assertEquals("a.ts:L3", lineRangeLabel(AskSelection("a.ts", "additions", 3, 3)))
        assertEquals("a.ts:L10-L20", lineRangeLabel(AskSelection("a.ts", "additions", 10, 20)))
    }

    @Test
    fun namesTheLinesInWords() {
        assertEquals("line 7", linesPhrase(7, 7))
        assertEquals("lines 356–358", linesPhrase(356, 358))
        assertEquals("lines 356–358", linesPhrase(358, 356))
        assertEquals("Line 7", linesPhrase(7, 7, label = true))
        assertEquals("Lines 356–358", linesPhrase(356, 358, label = true))
    }

    private val path = "src/new name.ts"
    private val previous = "src/old.ts"

    @Test
    fun linksAddedLinesAtTheHead() {
        assertEquals(
            "https://github.com/o/r/blob/head1/src/new%20name.ts#L10-L20",
            githubBlobUrl("o", "r", "github-pr", "head1", "base1", path, previous, AskSelection(path, "additions", 10, 20)),
        )
    }

    @Test
    fun linksRemovedLinesAtTheBaseUnderTheOldPath() {
        assertEquals(
            "https://github.com/o/r/blob/base1/src/old.ts#L7",
            githubBlobUrl("o", "r", "github-pr", "head1", "base1", path, previous, AskSelection(path, "deletions", 7, 7)),
        )
    }

    @Test
    fun hasNoLinkOutsideGithubOrWithoutTheCommit() {
        assertNull(githubBlobUrl(null, null, "local", "head1", "base1", path, previous, AskSelection(path, "additions", 1, 1)))
        assertNull(githubBlobUrl("o", "r", "github-pr", "", "base1", path, previous, AskSelection(path, "additions", 1, 1)))
    }

    @Test
    fun linksKeepPathBoundariesWhileEncodingUnicodeAndLiteralSpecialCharacters() {
        assertEquals(
            "https://github.com/o/r/blob/head1/src/caf%C3%A9%20%2B%23%25%F0%9F%98%80.kt#L4",
            githubBlobUrl("o", "r", "github-pr", "head1", "base1", "src/café +#%😀.kt", null, AskSelection("src/café +#%😀.kt", "additions", 4, 4)),
        )
    }

    @Test
    fun threadIdsAreUniqueAndServerValid() {
        val a = newAskThreadId(1000)
        val b = newAskThreadId(1000)
        assertTrue(a != b)
        assertTrue(Regex("^[\\w-]{1,80}$").matches(a))
        assertTrue(a.startsWith("ask-rs-"))
    }

    @Test
    fun critiqueVariantJoinsHeadModelEffortAndLens() {
        assertEquals("abc:claude-code:haiku:high:security", critiqueVariant("abc", "claude-code", "haiku", "high", "security"))
        assertEquals("abc:codex:::", critiqueVariant("abc", "codex", "", null, null))
    }

    @Test
    fun reviewBodyNamesTheEngineModelAndLens() {
        assertEquals(
            "Sound.\n\n<sub>Reviewed with codex/gpt-5, through the security lens on pulls.review.</sub>",
            critiqueReviewBody("Sound.", "codex", "gpt-5", "security"),
        )
        assertEquals("S\n\n<sub>Reviewed with claude-code on pulls.review.</sub>", critiqueReviewBody("S", "claude-code", null, null))
    }

}
