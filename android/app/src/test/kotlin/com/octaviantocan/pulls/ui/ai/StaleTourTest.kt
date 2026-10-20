package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.data.Tour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class StaleTourTest {
    private val original = "a".repeat(40)
    private val current = "b".repeat(40)
    private val tour = Tour("A tour", "An overview", emptyList(), emptyList(), original, null, "en", "2026-10-20T12:00:00Z", null)

    @Test
    fun missingCommitMetadataDoesNotClaimTheTourIsStale() {
        assertNull(staleTourIdentity(null, current))
        for (missing in listOf(null, "", " ")) {
            assertNull(staleTourIdentity(tour.copy(headSha = missing), current))
            assertNull(staleTourIdentity(tour, missing))
        }
        assertNull(staleTourIdentity(tour, original))
    }

    @Test
    fun refreshedTourObjectsKeepTheSamePromptIdentity() {
        val identity = staleTourIdentity(tour, current)
        assertEquals(StaleTourIdentity(original, tour.generatedAt, current), identity)
        // A quiet server refresh, or another view of the same tour, must not force the prompt again.
        assertEquals(identity, staleTourIdentity(tour.copy(title = "An updated title", overview = "More words"), current))
    }

    @Test
    fun aNewGenerationOrAnotherCommitGetsItsOwnPromptIdentity() {
        val identity = staleTourIdentity(tour, current)
        assertNotEquals(identity, staleTourIdentity(tour.copy(generatedAt = "2026-10-20T13:00:00Z"), current))
        assertNotEquals(identity, staleTourIdentity(tour.copy(headSha = "c".repeat(40)), current))
        assertNotEquals(identity, staleTourIdentity(tour, "d".repeat(40)))
    }
}
