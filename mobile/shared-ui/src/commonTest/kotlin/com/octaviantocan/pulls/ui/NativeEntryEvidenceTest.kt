package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.data.RpcObserver
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import com.octaviantocan.pulls.ui.settings.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativeEntryEvidenceTest {
    @Test
    fun restoredPreferencesAndKeychainAloneCannotRecreateAPrivateWorkspace() {
        val preferences = MemorySettingsPreferences()
        preferences.edit().putString("mode", NativeEntryMode.ExistingServer.name).apply()
        val storage = EntryAccountStorage().apply { restored = true }
        assertEquals(NativeEntryMode.Explore, NativeEntryEvidence(preferences, storage).entry().mode)
        assertEquals(0, storage.observers.size)
        assertEquals("fixture-session", storage.token)
    }

    @Test
    fun actualVerifiedConnectionReturnsButExplicitExplorePreservesItsSavedAccount() = runTest {
        val preferences = MemorySettingsPreferences()
        val storage = EntryAccountStorage()
        val evidence = NativeEntryEvidence(preferences, storage)
        val runtime = runtime(storage)
        evidence.verified(runtime)
        assertFalse(preferences.contains("verified"))
        runtime.start()
        runCurrent()
        assertEquals(AccountPhase.Ready, runtime.state.phase)
        evidence.verified(runtime)
        val marker = preferences.getString("verified", null)!!
        assertTrue(marker.matches(Regex("[0-9a-f]{64}")))
        assertFalse(marker.contains("reader"))
        assertFalse(marker.contains("fixture"))
        storage.restored = true // A real process relaunch keeps its local proof, withholds the Keychain token.
        assertEquals(NativeEntryMode.ExistingServer, evidence.entry().mode)
        evidence.selected(NativeEntryMode.Explore)
        assertEquals(NativeEntryMode.Explore, evidence.entry().mode)
        assertEquals("fixture-session", storage.token)
        evidence.selected(NativeEntryMode.ExistingServer)
        assertEquals(NativeEntryMode.ExistingServer, evidence.entry().mode)
        runtime.close()
    }

    @Test
    fun endpointAccessAndLocalSignOutCannotReuseThePriorVerificationMarker() = runTest {
        val preferences = MemorySettingsPreferences()
        val storage = EntryAccountStorage()
        val evidence = NativeEntryEvidence(preferences, storage)
        val runtime = runtime(storage)
        runtime.start(); runCurrent(); evidence.verified(runtime); runtime.close()
        storage.restored = true
        storage.server = "https://different.example"
        assertEquals(NativeEntryMode.Explore, evidence.entry().mode)
        storage.server = "https://private.example"
        storage.access = "different-access"
        assertEquals(NativeEntryMode.Explore, evidence.entry().mode)
        storage.access = "fixture-access"
        assertEquals(NativeEntryMode.ExistingServer, evidence.entry().mode)
        storage.token = null
        storage.restored = false
        assertEquals(NativeEntryMode.Explore, evidence.entry().mode)
    }

    @Test
    fun localVerifiedStorageFailureKeepsRecoveryButFreshStorageFailureStaysPublic() = runTest {
        val preferences = MemorySettingsPreferences()
        val storage = EntryAccountStorage()
        val evidence = NativeEntryEvidence(preferences, storage)
        val runtime = runtime(storage)
        runtime.start(); runCurrent(); evidence.verified(runtime); runtime.close()
        storage.unavailable = true
        storage.problem = "Unlock the device and retry."
        assertEquals(NativeEntryMode.ExistingServer, evidence.entry().mode)
        assertEquals(NativeEntryMode.Explore, NativeEntryEvidence(MemorySettingsPreferences(), storage).entry().mode)
    }

    @Test
    fun verifiedAccountlessConnectionNeedsNoRestoredSessionButFailedDiscoveryCannotMarkOne() = runTest {
        val preferences = MemorySettingsPreferences()
        val storage = EntryAccountStorage().apply { token = null }
        var offline = true
        val runtime = AccountRuntime(storage, RpcTransport {
            if (offline) error("Fixture offline")
            RpcResponse(200, """{"result":{"hasAccount":false}}""")
        }, backgroundScope, { "" }, {}, {}, RpcObserver { _, _, _, _ -> })
        val evidence = NativeEntryEvidence(preferences, storage)
        runtime.start(); runCurrent(); evidence.verified(runtime)
        assertEquals(NativeEntryMode.Explore, evidence.entry().mode)
        offline = false
        runtime.refresh(); runCurrent(); evidence.verified(runtime)
        assertEquals(NativeEntryMode.ExistingServer, evidence.entry().mode)
        assertFalse(preferences.getBoolean("requiresSession", true))
        runtime.close()
    }

    private fun kotlinx.coroutines.test.TestScope.runtime(storage: EntryAccountStorage): AccountRuntime = AccountRuntime(
        storage, RpcTransport { request ->
            val method = Json.parseToJsonElement(request.body).jsonObject.getValue("name").jsonPrimitive.content
            RpcResponse(200, when (method) {
                "account-status" -> """{"result":{"hasAccount":true}}"""
                "account-me" -> """{"result":{"email":"reader@example.com"}}"""
                else -> error("Unexpected fixture RPC: $method")
            })
        }, backgroundScope, { "" }, {}, {}, RpcObserver { _, _, _, _ -> },
    )
}

/** No test credentials are accepted or mutated by a silent successful stub. */
internal class EntryAccountStorage : AccountStorage {
    var server = "https://private.example"
    var access = "fixture-access"
    var token: String? = "fixture-session"
    var restored = false
    var unavailable = false
    var problem: String? = null
    val observers = mutableListOf<AccountStorageObserver>()
    override fun connection(verification: Boolean): AccountConnection? = if (unavailable) null else AccountConnection(
        server, buildMap {
            put("CF-Access-Client-Id", "fixture-client")
            put("CF-Access-Client-Secret", access)
            if (verification || !restored) token?.let { put("X-Pully-Session", it) }
        }, "claude-code", "", 1,
    )
    override fun account() = StoredAccount(if (!restored && token != null && !unavailable) "reader@example.com" else null, restored && token != null, problem)
    override fun observe(observer: AccountStorageObserver): AccountStorageSubscription {
        observers += observer
        return object : AccountStorageSubscription { override fun cancel() { observers -= observer } }
    }
    override fun acceptSession(session: String, email: String, expectedGeneration: Long): String = error("Unexpected fixture mutation")
    override fun confirmRestoredSession(email: String?, expectedGeneration: Long): String = error("Unexpected fixture mutation")
    override fun forgetSession(): String = error("Unexpected fixture mutation")
    override fun saveServer(server: String, accessId: String, accessSecret: String): String = error("Unexpected fixture mutation")
    override fun saveAnalysis(engine: String, model: String, expectedGeneration: Long): String = error("Unexpected fixture mutation")
    override fun resetCredentials(): String = error("Unexpected fixture mutation")
    override fun retryStorage(): String = error("Unexpected fixture mutation")
    override fun googleAvailable(serverClientId: String) = false
    override fun googleToken(serverClientId: String, expectedGeneration: Long, completed: AccountTokenObserver): AccountStorageSubscription = error("Unexpected native selection")
}
