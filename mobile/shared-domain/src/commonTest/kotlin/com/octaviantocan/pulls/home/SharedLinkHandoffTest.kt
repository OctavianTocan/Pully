package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.core.LinkPlace
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.home.HomeLink
import com.octaviantocan.pulls.ui.home.HomeLinks
import com.octaviantocan.pulls.ui.home.decodeSharedLink
import com.octaviantocan.pulls.ui.home.encodeSharedLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SharedLinkHandoffTest {
    private val pull = "https://github.com/pully/mobile-fixtures/pull/76"
    private val file = "53259ec17662dee09f6fa100315d96c65435f26fa0861870b9033a099df74830"

    @Test
    fun sameParserDestinationsSurviveTheActualStringProcessBoundary() {
        val links = listOf(
            pull, "$pull#issuecomment-72", "$pull#pullrequestreview-73", "$pull#discussion_r74", "$pull#issue-75", "$pull#event-76",
            "$pull/files/abcdef1..abcdef2#diff-${file}L42", "$pull/commits/abcdef1#diff-${file}R19",
            "$pull/commits", "$pull/checks?check_run_id=77",
            "https://github.com/pully/mobile-fixtures/compare/main...feature%2Fshared#diff-${file}R9",
            "https://github.com/pully/mobile-fixtures/commit/abcdef1#diff-${file}L9",
            "https://github.com/pully/mobile-fixtures/pulls", "https://server.example/-/abc1234",
            "https://server.example/pully/mobile-fixtures/76", "https://server.example/branch/feature%2Fshared",
        )
        for (link in links) {
            val payload = assertNotNull(encodeSharedLink(link), link)
            assertEquals(HomeLinks.parseShared(link), decodeSharedLink(payload), link)
            assertEquals(payload, encodeSharedLink(link), "The stable wire shape must not contain random process state.")
        }
        assertEquals(HomeLink.Target(OpenTarget.Pull("pully", "mobile-fixtures", 76, "github.com"), LinkPlace.Files(file, 42, true, "abcdef1..abcdef2")),
            decodeSharedLink(assertNotNull(encodeSharedLink("$pull/files/abcdef1..abcdef2#diff-${file}L42"))))
    }

    @Test
    fun rawShareProseAndArbitraryQueryCredentialsNeverEnterTheStoredPayload() {
        val payload = assertNotNull(encodeSharedLink("Private sample prose sample-person@example.test: $pull/checks?check_run_id=77&session=sample-secret&CF-Access-Client-Secret=sample-access"))
        for (raw in listOf("Private sample prose", "sample-person", "sample-secret", "sample-access", "CF-Access", "https://", "?")) {
            assertFalse(payload.contains(raw), raw)
        }
        assertEquals(HomeLink.Target(OpenTarget.Pull("pully", "mobile-fixtures", 76, "github.com"), LinkPlace.Checks(77)), decodeSharedLink(payload))
    }

    @Test
    funMalformedOrFutureDataCannotReenterTheSharedStack() {
        val valid = assertNotNull(encodeSharedLink(pull))
        for (payload in listOf(
            "not JSON", "x".repeat(16 * 1024 + 1), valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"number\":76", "\"number\":0"), valid.replace("\"number\":76", "\"number\":2147483648"),
            valid.replace("\"number\":76", "\"number\":\"76\""), valid.replace("\"owner\":\"pully\"", "\"owner\":\"other/owner\""),
            valid.dropLast(1) + ",\"session\":\"sample-secret\"}",
            valid.replace("\"kind\":\"githubPull\"", "\"kind\":\"local\""),
            valid.replace("\"number\":76", "\"number\":76,\"name\":\"sample-secret\""),
        )) assertNull(decodeSharedLink(payload), payload.take(120))
        assertNull(encodeSharedLink("$pull" + "9".repeat(100)))
        assertNull(encodeSharedLink("https://github.com/pully/mobile-fixtures/issues/76"))
        assertNull(encodeSharedLink("https://github.com/a/b/commit/not-a-commit"))
        assertNull(encodeSharedLink("x".repeat(16 * 1024 + 1)))
    }
}
