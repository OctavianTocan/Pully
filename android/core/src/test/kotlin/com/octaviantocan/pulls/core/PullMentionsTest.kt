package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PullMentionsTest {
    private val listed = listOf(ListedPull("octa", "web", 5), ListedPull("octa", "api", 9))

    @Test
    fun aMentionNamesAListedPullRequestWhateverItsCase() {
        assertEquals(ListedPull("octa", "web", 5), listedPull("Octa", "WEB", 5, listed))
        assertNull(listedPull("octa", "web", 6, listed))
    }

    @Test
    fun aGithubLinkNamesTheListedPullRequestItPointsAt() {
        assertEquals(ListedPull("octa", "api", 9), listedPullOfUrl("https://github.com/octa/api/pull/9/files", listed))
        assertNull(listedPullOfUrl("https://github.com/octa/api/issues/9", listed))
        assertNull(listedPullOfUrl("https://example.com/octa/api/pull/9", listed))
    }

    @Test
    fun anAnswerNamesPullRequestsInOrderOnceEachAndOnlyListedOnes() {
        val text = "Start with [the API one](https://github.com/octa/api/pull/9), then octa/web#5. octa/web#5 again, and made/up#1."
        assertEquals(listOf(ListedPull("octa", "api", 9), ListedPull("octa", "web", 5)), mentionedPulls(text, listed))
    }

    @Test
    fun anInboxAnswerKnowsOtherNumbersInTheRepositoriesTheInboxHolds() {
        assertEquals(ListedPull("octa", "web", 4), knownPull("OCTA", "Web", 4, listed))
        assertEquals(ListedPull("octa", "api", 9), knownPull("octa", "api", 9, listed))
        assertNull(knownPull("made", "up", 1, listed))
        val text = "octa/web#4 was merged; made/up#1 is not real."
        assertEquals(listOf(ListedPull("octa", "web", 4)), mentionedPulls(text) { owner, repo, number -> knownPull(owner, repo, number, listed) })
    }

    @Test
    fun aPathWithAHashIsNotAMention() {
        assertEquals(emptyList(), mentionedPulls("see src/octa/web#5", listed))
    }

    @Test
    fun aQuestionEndsInAQuestionMarkOrStartsLikeOne() {
        assertTrue(looksLikeQuestion("failing?"))
        assertTrue(looksLikeQuestion("What is failing on dayflow"))
        assertTrue(looksLikeQuestion("show my drafts please"))
        assertFalse(looksLikeQuestion("?"))
        assertFalse(looksLikeQuestion("what"))
        assertFalse(looksLikeQuestion("fix the cache"))
        assertFalse(looksLikeQuestion("dayflow"))
    }
}
