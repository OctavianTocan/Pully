package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.ui.settings.AccountConnection
import com.octaviantocan.pulls.ui.settings.StoredAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PullyAccountOwnerTest {
    private val verified = StoredAccount("owner@example.com", false, null)
    private fun connection(headers: Map<String, String> = mapOf("x-pully-session" to "fixture-session")) =
        AccountConnection("https://pully.example", headers, "codex", "", 1)

    @Test fun retainedSessionCannotOwnDataBeforeTheServerConfirmsAccounts() {
        assertNull(eligibleAccountOwner(null, verified, connection()))
        assertNull(eligibleAccountOwner(false, verified, connection()))
        assertEquals("owner@example.com", eligibleAccountOwner(true, verified, connection()))
    }

    @Test fun restoredOrUnavailableCredentialsNeverGrantPrivateOwnership() {
        assertNull(eligibleAccountOwner(true, StoredAccount(verified.email, true, null), connection()))
        assertNull(eligibleAccountOwner(true, StoredAccount(verified.email, false, "Unlock the device."), connection()))
        assertNull(eligibleAccountOwner(true, verified, null))
        assertNull(eligibleAccountOwner(true, verified, connection(emptyMap())))
        assertNull(eligibleAccountOwner(true, verified, connection(mapOf("X-Pully-Session" to " "))))
    }
}
