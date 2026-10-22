package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerPageServicesTest {
    @Test
    fun anExplicitSaveProbeCannotFollowTheNextSourcesCredentials() = runTest {
        var generation = 0L
        var requests = 0
        val repo = Repo({ RepoConfiguration("https://pully.example", generation = generation) }, RpcTransport {
            requests++
            RpcResponse(200, "{\"result\":{\"hasAccount\":false}}")
        })
        val original = serverConnectionCheck(repo)
        generation++
        assertFailsWith<CancellationException> { original.verify() }
        assertEquals(0, requests)
        serverConnectionCheck(repo).verify()
        assertEquals(1, requests)
    }

    @Test
    fun anInvalidSavedAddressRemainsAProbeErrorWithoutSendingANativeRequest() = runTest {
        var requests = 0
        val repo = Repo({ RepoConfiguration("not a server") }, RpcTransport {
            requests++
            error("An invalid server must never reach native transport")
        })
        // Saving succeeds; the UI's existing "Saved, but the server did not answer" path owns this error.
        val probe = serverConnectionCheck(repo)
        assertFailsWith<IllegalArgumentException> { probe.verify() }
        assertEquals(0, requests)
    }
}
