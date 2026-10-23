package com.octaviantocan.pulls.ui.review

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

internal actual fun currentCommitDays(commits: List<PrCommit>): List<Pair<String, List<Int>>> {
    val zone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(zone).date
    return groupCommitDays(commits) { millis ->
        commitDayLabel(Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date, today)
    }
}
