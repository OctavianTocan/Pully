package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeObservationsTest {
    @Test
    fun pauseCancelsActualReadsWithoutACompositionFrameAndResumeRejectsTheOldReply() = runTest {
        val authority = HomeObservations(false)
        val release = CompletableDeferred<Unit>()
        var first = true
        val repo = Repo({ RepoConfiguration("https://fixtures.pully.invalid") }, RpcTransport {
            if (first) {
                first = false
                withContext(NonCancellable) { release.await() }
            }
            RpcResponse(200, """{"result":[]}""")
        })
        authority.resume()
        var oldPublished = false
        val old = backgroundScope.async {
            authority.read { repo.allTasks() }
            oldPublished = true
        }
        runCurrent()
        assertFalse(old.isCompleted)
        authority.pause()
        assertFalse(authority.ready)
        authority.resume()
        val current = backgroundScope.async { authority.read { repo.allTasks() } }
        runCurrent()
        try {
            assertTrue(current.isCompleted)
            assertTrue(current.await().isEmpty())
            assertFalse(oldPublished)
        } finally {
            release.complete(Unit)
        }
        runCurrent()
        assertTrue(old.isCancelled)
        assertFalse(oldPublished)
        authority.close()
    }

    @Test
    fun pauseLeavesAnAcceptedActualServerWriteAliveAndNeverSendsRemoteCancel() = runTest {
        val authority = HomeObservations()
        val accepted = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val repo = Repo({ RepoConfiguration("https://fixtures.pully.invalid") }, RpcTransport { request ->
            val name = Json.parseToJsonElement(request.body).jsonObject.getValue("name").jsonPrimitive.content
            calls += name
            assertEquals("ai-task-start", name)
            accepted.await()
            RpcResponse(200, """{"result":"accepted-server-task"}""")
        })
        val write = backgroundScope.async { repo.startTask("inbox", "inbox") }
        runCurrent()
        authority.pause()
        runCurrent()
        assertFalse(write.isCancelled)
        accepted.complete(Unit)
        runCurrent()
        assertEquals("accepted-server-task", write.await())
        assertEquals(listOf("ai-task-start"), calls)
        authority.close()
    }

    @Test
    fun registrationReportsCurrentEligibilityAndReentrantResumeCannotDeliverAnOldPause() {
        val authority = HomeObservations()
        val events = mutableListOf<String>()
        val first = authority.observe { active ->
            events += "first:$active"
            if (!active) authority.resume()
        }
        val second = authority.observe { events += "second:$it" }
        authority.pause()
        assertEquals(listOf("first:true", "second:true", "first:false", "first:true", "second:true"), events)
        first.cancel()
        second.cancel()
        authority.pause()
        assertEquals(5, events.size)
        authority.close()
    }

    @Test
    fun retirementCannotBeResumedOrStartANewRead() = runTest {
        val authority = HomeObservations(false)
        var executed = false
        authority.close()
        authority.resume()
        val read = backgroundScope.async { authority.read { executed = true } }
        runCurrent()
        assertTrue(read.isCancelled)
        assertFalse(executed)
        assertFalse(authority.ready)
        var active: Boolean? = null
        authority.observe { active = it }.cancel()
        assertEquals(false, active)
    }
}
