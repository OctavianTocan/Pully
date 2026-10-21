package com.octaviantocan.pulls.core

/** A task explicitly requested on this device, scoped to one server/account by its owning store. */
data class FollowedAiResult(
    val id: String,
    val key: String,
    val kind: String,
    val followedAt: Long,
    val route: String,
    val settled: Boolean = false,
)

/** Only authoritative server snapshots belong here; request/network errors are not task failures. */
data class AiResultSnapshot(val id: String, val key: String, val kind: String, val status: String)

data class AiResultOutcome(val followed: FollowedAiResult, val status: String) {
    val alerts: Boolean get() = status == "done" || status == "failed"
}

private val RESULT_KINDS = setOf("analyze", "tour", "brief", "critique")
const val AI_RESULT_RETAIN_MS = 7 * 24 * 60 * 60_000L

/** Expiry applies before evaluation, including after a long offline/process-dead period. */
fun retainedAiResults(followed: List<FollowedAiResult>, now: Long): List<FollowedAiResult> =
    followed.filter { it.followedAt in (now - AI_RESULT_RETAIN_MS)..now }.takeLast(100)

/** An accepted running start may be followed; enabling alerts never adopts pre-existing server work. */
fun followAiResult(snapshot: AiResultSnapshot, now: Long, route: String): FollowedAiResult? =
    snapshot.takeIf {
        it.status == "running" && it.kind in RESULT_KINDS && it.id.isNotBlank() && it.key.isNotBlank() &&
            !it.id.startsWith("pending-") && !it.id.startsWith("failed-") && route.isNotBlank()
    }?.let { FollowedAiResult(it.id, it.key, it.kind, now, route) }

/** Missing, foreign, interrupted or unrecognized states do not imply completion or failure. */
fun aiResultOutcomes(followed: List<FollowedAiResult>, snapshots: List<AiResultSnapshot>): List<AiResultOutcome> {
    val latest = snapshots.associateBy { it.id }
    return followed.filterNot { it.settled }.mapNotNull { watch ->
        latest[watch.id]?.takeIf {
            it.key == watch.key && it.kind == watch.kind && it.status in setOf("done", "failed", "cancelled")
        }?.let { AiResultOutcome(watch, it.status) }
    }
}
