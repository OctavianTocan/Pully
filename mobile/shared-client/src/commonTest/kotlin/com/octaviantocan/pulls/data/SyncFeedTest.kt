package com.octaviantocan.pulls.data

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncFeedTest {
    /** Runs the background work a minute ahead: `advanceUntilIdle` does not wait for `backgroundScope`. */
    private fun TestScope.settle() { advanceTimeBy(60_000); runCurrent() }

    /** Answers `sync-wait` from [answers] in turn; past the end, it fails like an unreachable server. */
    private class FeedTransport(private val answers: List<(JsonObject) -> RpcResponse>) : RpcTransport {
        val waits = mutableListOf<JsonObject>()
        val others = mutableListOf<String>()
        override suspend fun execute(request: RpcRequest): RpcResponse {
            val envelope = Json.parseToJsonElement(request.body).jsonObject
            val name = (envelope.getValue("name") as JsonPrimitive).content
            if (name != "sync-wait") { others += name; return RpcResponse(200, """{"result":true}""") }
            val args = envelope.getValue("args").jsonObject
            waits += args
            return answers.getOrNull(waits.size - 1)?.invoke(args) ?: throw IllegalStateException("offline")
        }
    }

    private fun answer(epoch: String, version: Long, topics: List<String> = emptyList(), reset: Boolean = false): (JsonObject) -> RpcResponse = {
        RpcResponse(200, """{"result":{"epoch":"$epoch","version":$version,"topics":${topics.map { "\"$it\"" }},"reset":$reset}}""")
    }

    private fun TestScope.follow(transport: FeedTransport, inbox: Boolean = false): MutableList<SyncChange> {
        val changes = mutableListOf<SyncChange>()
        val repo = Repo({ RepoConfiguration("https://server.example") }, transport)
        backgroundScope.launch { repo.followChanges(inbox = inbox, retryMs = listOf(1_000, 2_000)) { changes += it } }
        return changes
    }

    @Test
    fun theFirstAnswerOnlyPlacesThePhoneThenEachChangeIsPassedOnWithItsPosition() = runTest {
        val transport = FeedTransport(listOf(
            answer("e1", 4, reset = true),
            answer("e1", 6, listOf("inbox", "marks")),
            answer("e1", 6),
            answer("e2", 0, reset = true),
        ))
        val changes = follow(transport, inbox = true)
        settle()
        assertEquals(listOf<SyncChange>(SyncChange.Topics(setOf("inbox", "marks")), SyncChange.Everything), changes)
        assertEquals(
            listOf(
                buildJsonObject { put("epoch", ""); put("version", 0); put("waitMs", 0); put("inbox", true) },
                buildJsonObject { put("epoch", "e1"); put("version", 4); put("waitMs", SYNC_WAIT_MS); put("inbox", true) },
                buildJsonObject { put("epoch", "e1"); put("version", 6); put("waitMs", SYNC_WAIT_MS); put("inbox", true) },
                buildJsonObject { put("epoch", "e1"); put("version", 6); put("waitMs", SYNC_WAIT_MS); put("inbox", true) },
            ),
            transport.waits.take(4),
        )
        assertTrue(transport.others.isEmpty(), "following reads nothing by itself")
    }

    @Test
    fun anOlderServerWithoutTheFeedEndsItAtOnce() = runTest {
        val transport = FeedTransport(listOf({ _ -> RpcResponse(404, """{"error":"That method is not available to the app."}""") }))
        val changes = follow(transport)
        settle()
        assertEquals(1, transport.waits.size)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun failuresWaitLongerEachTimeAndGiveUpAfterTheLastWait() = runTest {
        val transport = FeedTransport(listOf(answer("e1", 1, reset = true)))
        follow(transport)
        runCurrent()
        assertEquals(2, transport.waits.size)
        advanceTimeBy(999); runCurrent()
        assertEquals(2, transport.waits.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, transport.waits.size)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(4, transport.waits.size)
        settle()
        assertEquals(4, transport.waits.size, "after the last wait the screen falls back to what it did before")
    }

    @Test
    fun aChangeOfAccountStopsFollowing() = runTest {
        var generation = 0L
        val transport = FeedTransport(listOf(answer("e1", 1, reset = true), { args -> generation = 1; answer("e1", 2, listOf("inbox"))(args) }))
        val changes = mutableListOf<SyncChange>()
        val repo = Repo({ RepoConfiguration("https://server.example", generation = generation) }, transport)
        backgroundScope.launch { repo.followChanges { changes += it } }
        settle()
        assertEquals(2, transport.waits.size)
        assertTrue(changes.isEmpty(), "an answer for the old account is not passed on")
    }
}
