package com.octaviantocan.pulls.ui.review

import kotlin.test.Test
import kotlin.test.assertEquals

class CommitCalendarTest {
    @Test fun missingDatesFollowTheirPrecedingGroupAndOriginalCommitIndicesStayIntact() {
        val commits = listOf("", "2026-10-20T10:00:00Z", "bad date", "2026-10-21T10:00:00Z", "2026-10-20T10:00:00Z")
            .mapIndexed { index, date -> PrCommit("$index", "$index", "Commit $index", committedDate = date) }
        assertEquals(
            listOf("Undated" to listOf(0), "Earlier" to listOf(1, 2), "Later" to listOf(3), "Earlier" to listOf(4)),
            groupCommitDays(commits) { if (it == prIsoMillis("2026-10-20T10:00:00Z")) "Earlier" else "Later" },
        )
    }
}
