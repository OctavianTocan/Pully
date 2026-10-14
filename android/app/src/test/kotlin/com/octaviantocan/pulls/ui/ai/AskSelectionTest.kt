package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.data.ChatMessage
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

    @Test
    fun chatRowsShowUserAssistantAndGroupingUpdatesOnly() {
        val rows = chatRows(
            listOf(
                ChatMessage("user", "Why?"),
                ChatMessage("assistant", ""),
                ChatMessage("toolResult", "file contents"),
                ChatMessage("toolResult", "Grouping updated: 4 groups."),
                ChatMessage("assistant", "Because."),
            ),
        )
        assertEquals(listOf(ChatRow.User("Why?"), ChatRow.Grouping(4), ChatRow.Assistant("Because.")), rows)
    }
}
