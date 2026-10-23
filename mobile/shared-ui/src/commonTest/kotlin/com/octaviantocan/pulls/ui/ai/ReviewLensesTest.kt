package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.review.DiffHarness
import com.octaviantocan.pulls.ui.review.NoSessionEffects
import com.octaviantocan.pulls.ui.review.ReviewSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewLensesTest {
    @Test
    fun aSourceSharesItsCachedFirstSkillsAcrossActualReviewRoutesAndKeepsTheListAfterARefreshFailure() = runTest {
        val h = DiffHarness(backgroundScope)
        var requests = 0
        var fail = false
        h.handler = { name, args -> if (name == "lens-list") {
            requests++
            if (fail) h.serverError("Offline")
            Json.parseToJsonElement("""[{"name":"security","title":"Security","description":"Sample skill"}]""")
        } else h.default(name, args) }
        h.page.load(); runCurrent()
        val catalog = assertNotNull(reviewLenses(h.page))
        catalog.load(h.page); catalog.load(h.page); runCurrent()
        assertEquals(1, requests)
        assertEquals("Security", catalog.titleOf("security"))
        val another = ReviewSession(OpenTarget.Pull("owner", "repo", 2, "github.com"), h.page.repo, backgroundScope, h.page.settings, cache = h.cache, effects = NoSessionEffects)
        assertSame(catalog, reviewLenses(another))
        assertEquals("Security", reviewLenses(another)?.list?.single()?.title)
        fail = true
        catalog.load(another); runCurrent()
        assertEquals("Offline", catalog.error)
        assertEquals("Security", catalog.list?.single()?.title)
        another.detach(); h.page.detach()
    }

    @Test
    fun replacedSourceAndCancelledRouteCannotPublishLateSkillsOrErrors() = runTest {
        for (replaceSource in listOf(false, true)) for (failed in listOf(false, true)) {
            val h = DiffHarness(backgroundScope)
            val gate = CompletableDeferred<Unit>()
            h.handler = { name, args -> if (name == "lens-list") {
                withContext(NonCancellable) { gate.await() }
                if (failed) h.serverError("Old source offline")
                Json.parseToJsonElement("""[{"name":"old","title":"Old private skill"}]""")
            } else h.default(name, args) }
            h.page.load(); runCurrent()
            val catalog = assertNotNull(reviewLenses(h.page))
            catalog.load(h.page); runCurrent()
            if (replaceSource) h.generation++ else h.page.detach()
            gate.complete(Unit); runCurrent()
            assertNull(catalog.list)
            assertNull(catalog.error)
            assertNull(reviewLenses(h.page))
            h.page.detach()
        }
    }
}
