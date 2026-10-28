package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Writes the phone already shows but the server has not confirmed, sent one at a time in the order they were made
 * (Zero's pending mutations). A write is sent until the server takes it: no connection, a timeout, a server that is
 * down or busy (5xx, 408, 429) or an answer that is not the server's own keeps it and tries again after the next of
 * [retryMs], the last one repeating. Only the server refusing it (its own 4xx answer) drops it, with [rejected], so the
 * screen can read the server's state back.
 * A screen that reads state from the server lays [pending] over it, so a write is never shown undone before it arrived.
 * Everything runs on [scope]'s dispatcher, the main one in the apps; [clear] drops every write of an old account.
 */
class Outbox(
    private val scope: CoroutineScope,
    private val send: suspend (Mutation) -> Unit,
    private val rejected: (Mutation, String) -> Unit = { _, _ -> },
    private val retryMs: List<Long> = listOf(1_000L, 2_000L, 5_000L, 15_000L, 30_000L),
) {
    /** One write: the phone method and its arguments, numbered in the order the phone made them. */
    data class Mutation(val id: Long, val name: String, val args: JsonObject)

    private val queue = ArrayDeque<Mutation>()
    private var nextId = 1L
    private var sender: Job? = null

    /** The writes still on their way, oldest first. */
    val pending: List<Mutation> get() = queue.toList()

    /** Queues a write the screen has already shown, and sends it once those before it are in. */
    fun add(name: String, args: JsonObject): Mutation {
        val mutation = Mutation(nextId++, name, args)
        queue.addLast(mutation)
        if (sender?.isActive != true) sender = scope.launch { drain() }
        return mutation
    }

    /** Drops every queued write; one already sent may still land. */
    fun clear() {
        queue.clear()
        sender?.cancel()
        sender = null
    }

    private suspend fun drain() {
        var failures = 0
        while (true) {
            val next = queue.firstOrNull() ?: return
            val failure = try {
                send(next)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure
            }
            if (queue.firstOrNull() !== next) continue
            when {
                failure == null -> { queue.removeFirst(); failures = 0 }
                retryable(failure) -> delay(retryMs[minOf(failures++, retryMs.lastIndex)])
                else -> {
                    queue.removeFirst()
                    failures = 0
                    rejected(next, failure.message ?: "The server refused this change.")
                }
            }
        }
    }

    /** Only the server's own 4xx answer is a refusal; a sign-in page, a proxy's reply or no reply is not. */
    private fun retryable(failure: Exception): Boolean =
        failure !is ServerError || failure.status !in 400..499 || failure.status == 408 || failure.status == 429 ||
            failure.kind !in setOf(ServerErrorKind.Server, ServerErrorKind.Unauthorized, ServerErrorKind.Forbidden)
}
