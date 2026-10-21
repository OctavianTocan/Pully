package com.octaviantocan.pulls.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountRestorationTest {
    @Test
    fun aFreshInstallNeedsConnectionDiscovery() {
        val state = restore()
        assertFalse(state.verified)
        assertNull(state.email)
        assertNull(state.hasAccount)
        assertNull(state.googleClientId)
    }

    @Test
    fun anUnmarkedOldCacheCannotBringServerAsGoogleSignInToServerB() {
        val state = restore(
            configured = "https://b.example", sessionServer = "https://a.example",
            email = "ana@example.com", oldAccount = true, google = "server-a-client",
        )
        assertFalse(state.verified)
        assertNull(state.email)
        assertNull(state.hasAccount)
        assertNull(state.googleClientId)
    }

    @Test
    fun aMatchingSavedSessionKeepsOfflineAccessWhileLegacyCapabilitiesAreRediscovered() {
        val state = restore(
            configured = "https://a.example/", sessionServer = "https://A.example:443",
            email = "ana@example.com", oldAccount = true, google = "unmarked-client",
        )
        assertTrue(state.verified)
        assertEquals("ana@example.com", state.email)
        assertNull(state.hasAccount)
        assertNull(state.googleClientId)
    }

    @Test
    fun sourceMarkedCapabilitiesAndAnOlderConnectedServerSurviveOffline() {
        val account = restore(statusServer = "https://a.example", oldAccount = true, google = "current-client")
        assertTrue(account.verified)
        assertEquals(true, account.hasAccount)
        assertEquals("current-client", account.googleClientId)
        assertNull(account.email)
        val older = restore(verifiedServer = "https://a.example")
        assertTrue(older.verified)
        assertNull(older.hasAccount)
    }

    @Test
    fun capabilitiesAndVerifiedMarkersNeverCrossServersOrInvalidAddresses() {
        for ((configured, statusServer) in listOf("https://b.example" to "https://a.example", "not a URL" to "not a URL")) {
            val state = restore(configured = configured, statusServer = statusServer, oldAccount = false, google = "old-client", verifiedServer = statusServer)
            assertFalse(state.verified)
            assertNull(state.hasAccount)
            assertNull(state.googleClientId)
        }
    }

    private fun restore(
        configured: String = "https://a.example",
        sessionServer: String? = null,
        email: String? = null,
        statusServer: String? = null,
        oldAccount: Boolean? = null,
        google: String? = null,
        verifiedServer: String? = null,
    ) = restoreAccountConnection(configured, email != null, sessionServer, email, statusServer, oldAccount, google, verifiedServer)
}
