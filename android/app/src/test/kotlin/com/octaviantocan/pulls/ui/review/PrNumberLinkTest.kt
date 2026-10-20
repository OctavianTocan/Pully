package com.octaviantocan.pulls.ui.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrNumberLinkTest {
    @Test
    fun onlyKnownPullRequestsGetCanonicalGithubLinks() {
        assertEquals("https://github.com/owner/repo/pull/42", canonicalPrUrl("#42", "owner", "repo"))
        assertNull(canonicalPrUrl("#42", null, null))
        assertNull(canonicalPrUrl("abc1234", "owner", "repo"))
        assertNull(canonicalPrUrl("main...feature", "owner", "repo"))
        assertNull(canonicalPrUrl("posted:42", "owner", "repo"))
        assertNull(canonicalPrUrl("#0", "owner", "repo"))
    }
}
