package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiResultNotificationsTest {
    private val running = AiResultSnapshot("task-1", "github:a/r:1", "tour", "running")
    private val followed = followAiResult(running, 100, "https://github.com/a/r/pull/1")!!

    @Test fun requiresExplicitAcceptedRunningWork() {
        for (status in listOf("done", "failed", "cancelled", "unknown")) assertNull(followAiResult(running.copy(status = status), 100, followed.route))
        assertNull(followAiResult(running.copy(kind = "chat"), 100, followed.route))
        assertNull(followAiResult(running.copy(id = "pending-1"), 100, followed.route))
        assertNull(followAiResult(running.copy(id = "failed-1"), 100, followed.route))
        assertEquals(emptyList(), aiResultOutcomes(emptyList(), listOf(running.copy(status = "done"))))
    }

    @Test fun persistedOwnershipCanDiscoverDelayedCompletionAfterRestart() {
        val restored = followed.copy()
        val outcome = aiResultOutcomes(listOf(restored), listOf(running.copy(status = "done"))).single()
        assertEquals(restored.id, outcome.followed.id)
        assertTrue(outcome.alerts)
        assertEquals(emptyList(), aiResultOutcomes(listOf(restored.copy(settled = true)), listOf(running.copy(status = "done"))))
    }

    @Test fun onlyTheMatchingOwnedTaskCanAlert() {
        for (foreign in listOf(running.copy(id = "other"), running.copy(key = "other"), running.copy(kind = "analyze"))) {
            assertEquals(emptyList(), aiResultOutcomes(listOf(followed), listOf(foreign.copy(status = "failed"))))
        }
        assertTrue(aiResultOutcomes(listOf(followed), listOf(running.copy(status = "failed"))).single().alerts)
    }

    @Test fun cancellationSettlesSilentlyButMissingOrNetworkInterruptionDoesNotSettle() {
        assertFalse(aiResultOutcomes(listOf(followed), listOf(running.copy(status = "cancelled"))).single().alerts)
        assertEquals(emptyList(), aiResultOutcomes(listOf(followed), emptyList()))
        for (status in listOf("running", "interrupted", "unknown", "")) {
            assertEquals(emptyList(), aiResultOutcomes(listOf(followed), listOf(running.copy(status = status))))
        }
    }

    @Test fun parallelTasksSettleIndependently() {
        val other = running.copy(id = "task-2", kind = "critique")
        val watches = listOf(followed, followAiResult(other, 110, followed.route)!!)
        assertEquals(listOf("task-2"), aiResultOutcomes(watches, listOf(running, other.copy(status = "done"))).map { it.followed.id })
    }

    @Test fun expiryPreventsOldCompletionAndMissingTaskPollingAfterOfflineRestart() {
        val now = 100 + AI_RESULT_RETAIN_MS + 1
        val restored = retainedAiResults(listOf(followed), now)
        assertTrue(restored.isEmpty())
        assertEquals(emptyList(), aiResultOutcomes(restored, listOf(running.copy(status = "done"))))
        assertEquals(listOf(followed), retainedAiResults(listOf(followed), now - 1))
        assertTrue(retainedAiResults(listOf(followed.copy(followedAt = now + 1)), now).isEmpty())
    }
}
