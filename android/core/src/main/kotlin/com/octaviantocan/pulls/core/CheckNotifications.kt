package com.octaviantocan.pulls.core

import java.net.URI
import kotlinx.serialization.Serializable

/** Only hashes/epochs cross the delivery boundary; never raw credentials. */
data class CheckNotificationSource(val key: String, val auth: String, val epoch: Long) {
    fun accepts(current: CheckNotificationSource, requestedRevision: String, currentRevision: String?): Boolean =
        this == current && requestedRevision.isNotEmpty() && requestedRevision == currentRevision
}

@Serializable
data class ObservedCheck(
    val id: String,
    val name: String,
    val conclusion: String,
    val startedAt: String = "",
    val completedAt: String = "",
    val url: String = "",
)

data class CheckSnapshot(val head: String, val checks: List<ObservedCheck>, val complete: Boolean = true)

@Serializable
data class CheckObservation(val epoch: Int = 0, val failed: Boolean = false)

@Serializable
data class CheckNotificationState(
    val initialized: Boolean = false,
    val head: String = "",
    val observations: Map<String, CheckObservation> = emptyMap(),
    val baseline: Set<String> = emptySet(),
    val delivered: Set<String> = emptySet(),
)

enum class CheckFailure(val words: String) {
    Failed("failed"), TimedOut("timed out"), ActionRequired("needs action"), StartupFailure("could not start"),
}

fun checkFailure(conclusion: String): CheckFailure? = when (conclusion.lowercase()) {
    "failure", "error" -> CheckFailure.Failed
    "timed_out" -> CheckFailure.TimedOut
    "action_required" -> CheckFailure.ActionRequired
    "startup_failure" -> CheckFailure.StartupFailure
    else -> null
}

data class CheckFailureEvent(val occurrence: String, val check: ObservedCheck, val failure: CheckFailure)
data class CheckEvaluation(val state: CheckNotificationState, val events: List<CheckFailureEvent>)

/** Evaluates a complete current-head snapshot; the first successful one is a silent baseline. */
fun evaluateChecks(previous: CheckNotificationState, snapshot: CheckSnapshot): CheckEvaluation {
    if (!snapshot.complete || snapshot.head.isBlank()) return CheckEvaluation(previous, emptyList())
    val observations = mutableMapOf<String, CheckObservation>()
    val events = mutableListOf<CheckFailureEvent>()
    val baseline = previous.baseline.toMutableSet()
    for (check in snapshot.checks.distinctBy { it.id }.filter { it.id.isNotBlank() }) {
        val failure = checkFailure(check.conclusion)
        val old = previous.observations[check.id].takeIf { previous.head == snapshot.head }
        // Reused ids can rerun without an exposed workflow run_attempt. An observed departure from
        // failure gives that later failure a new local epoch; timestamps distinguish missed reruns.
        val knownDeparture = check.conclusion.lowercase() in setOf("queued", "in_progress", "requested", "waiting", "pending", "expected", "success", "neutral", "skipped", "cancelled", "stale")
        val epoch = (old?.epoch ?: 0) + if (old?.failed == true && knownDeparture) 1 else 0
        observations[check.id] = if (failure == null && !knownDeparture && old != null) old else CheckObservation(epoch, failure != null)
        if (failure == null) continue
        val occurrence = listOf(snapshot.head, check.id, check.startedAt, check.completedAt, epoch.toString()).joinToString("\u0000")
        if (!previous.initialized) baseline += occurrence
        else if (occurrence !in previous.baseline && occurrence !in previous.delivered) events += CheckFailureEvent(occurrence, check, failure)
    }
    return CheckEvaluation(previous.copy(
        initialized = true, head = snapshot.head, observations = observations,
        baseline = baseline.takeLastSet(500),
    ), events)
}

/** Delivery is recorded separately, after the OS accepts the grouped notification. */
fun CheckNotificationState.delivered(events: List<CheckFailureEvent>): CheckNotificationState =
    copy(delivered = (delivered + events.map { it.occurrence }).takeLastSet(500))

private fun <T> Set<T>.takeLastSet(limit: Int): Set<T> = toList().takeLast(limit).toSet()

/** Exact in-app check links only; third-party Actions/details URLs fall back to this PR's checks. */
fun checkNotificationUrl(owner: String, repo: String, number: Int, candidate: String?): String {
    val base = "https://github.com/$owner/$repo/pull/$number/checks"
    val uri = candidate?.let { runCatching { URI(it) }.getOrNull() } ?: return base
    if (uri.scheme != "https" || uri.host !in setOf("github.com", "www.github.com") || uri.userInfo != null || uri.port != -1 || uri.fragment != null || uri.path != "/$owner/$repo/pull/$number/checks") return base
    val match = Regex("check_run_id=([0-9]+)").matchEntire(uri.rawQuery.orEmpty()) ?: return base
    return "$base?check_run_id=${match.groupValues[1]}"
}
