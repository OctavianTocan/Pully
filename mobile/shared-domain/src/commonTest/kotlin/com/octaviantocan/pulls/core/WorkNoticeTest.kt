package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkNoticeTest {
    private fun task(id: String, kind: String = "tour", status: String = "running", startedAt: Long = 1000) =
        WorkTask("github:owner/repo#12", kind, status, startedAt = startedAt, id = id)

    @Test fun tracksRealWorkAndClearsEveryTerminalOutcome() {
        assertEquals("Writing tour", workNotice(listOf(task("a")), 5000)?.title)
        for (status in listOf("done", "failed", "cancelled")) {
            assertNull(workNotice(listOf(task("a", status = status)), 5000))
        }
        assertNull(workNotice(listOf(task("ask", kind = "ask")), 5000))
    }

    @Test fun keepsConcurrentTasksUntilTheLastEndsAndUsesTheirOldestStart() {
        val analysis = task("a", kind = "analyze", startedAt = 2000)
        val tour = task("t", startedAt = 1000)
        val notice = workNotice(listOf(analysis, tour, tour), 5000)!!
        assertEquals("2 AI tasks running", notice.title)
        assertEquals(1000L, notice.startedAt)
        assertEquals(2, notice.lines.size)
        assertEquals("Analyzing pull request", workNotice(listOf(analysis, tour.copy(status = "done")), 5000)?.title)
    }

    @Test fun neverInventsAnElapsedTimeOrShowsADiffKeyAsItsTitle() {
        assertNull(workNotice(listOf(task("a", startedAt = 0)), 5000)?.startedAt)
        assertNull(workNotice(listOf(task("a", startedAt = 6000)), 5000)?.startedAt)
        assertEquals(listOf("owner/repo #12"), workNotice(listOf(task("a")), 5000)?.lines)
        assertEquals(listOf("Pully"), workNotice(listOf(task("a").copy(key = "posted:private-id")), 5000)?.lines)
    }

    @Test fun sourceSwitchAndSignOutRejectLateRequestsEvenAfterReturningToTheSameServer() {
        val state = WorkNoticeState<String>()
        val old = state.capture("server-a/account-a")
        assertTrue(state.replace(listOf(task("old")), old, "server-a/account-a", 5000))
        state.capture("server-b/account-b")
        assertNull(state.notice(5001))
        assertFalse(state.activity(task("late"), old, "server-b/account-b"))
        val back = state.capture("server-a/account-a")
        assertFalse(state.replace(listOf(task("late")), old, "server-a/account-a", 5002))
        assertTrue(state.replace(listOf(task("new")), back, "server-a/account-a", 5003))
        state.clear() // Signing out invalidates even a response carrying the old identity.
        assertFalse(state.replace(listOf(task("late")), back, "server-a/account-a", 5004))
        assertNull(state.notice(5004))
    }

    @Test fun anotherTasksPageUpdatesCannotKeepAStaleGlobalSnapshotAlive() {
        val state = WorkNoticeState<String>()
        val source = state.capture("server/account")
        state.replace(listOf(task("a"), task("b")), source, "server/account", 5000)
        assertTrue(state.activity(task("b"), source, "server/account"))
        assertTrue(state.activity(task("b", status = "done"), source, "server/account"))
        assertNull(state.notice(5000 + WORK_NOTICE_FRESH_MS))
        // Only a successful whole-server snapshot can renew the notification.
        state.replace(listOf(task("a", status = "done"), task("b")), source, "server/account", 40_000)
        assertEquals("Writing tour", state.notice(40_001)?.title)
    }
}
