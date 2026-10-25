package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SubscriptionSignInLifecycleTest {
    @Test fun pauseRetainsTheServicesOwnerButDeniesDispatchAndResumeUsesTheSameAccountSource() = runTest {
        var foreground = true
        var generation = 1L
        val requests = mutableListOf<RpcRequest>()
        val repo = Repo({ RepoConfiguration("https://fixture.example", generation = generation) }, RpcTransport { request ->
            requests += request
            RpcResponse(200, """{"result":{"signedIn":true}}""", "application/json")
        })
        val services = SourceAiSignInServices(repo, { generation == 1L }, {}, {}, { foreground })
        val source = repo.source()
        foreground = false
        assertTrue(services.current(), "An ordinary pause is not source replacement")
        assertFalse(services.dispatchReady)
        assertFailsWith<CancellationException> { services.finish(AiCli.Claude, "fixture-code") }
        assertTrue(requests.isEmpty())
        foreground = true
        assertTrue(services.dispatchReady)
        assertTrue(services.finish(AiCli.Claude, "fixture-code"))
        assertEquals(source, repo.source())
        assertTrue(requests.single().body.contains("fixture-code"))
        generation++
        assertFalse(services.current())
        assertFailsWith<CancellationException> { services.finish(AiCli.Claude, "stale-code") }
        assertEquals(1, requests.size)
    }

    @Test fun privateProviderStateClearsOnActualRetirementAndTheListenerCanDetach() {
        var current = true
        var retire: (() -> Unit)? = null
        val services = SourceAiSignInServices(
            Repo({ RepoConfiguration("https://fixture.example") }, RpcTransport { error("No request expected") }),
            { current }, {}, {}, retirement = { action -> retire = action; { retire = null } },
        )
        val state = AiSignInState().apply {
            started = AiSignInStart("https://claude.ai/fixture", null, true)
            code = "private-draft"
            busy = true
        }
        val stop = services.onRetired(state::clear)
        current = false
        assertNotNull(retire).invoke()
        assertNull(state.started)
        assertEquals("", state.code)
        assertFalse(state.busy)
        stop()
        assertNull(retire)
    }
}
