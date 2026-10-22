package com.octaviantocan.pulls.ios

import com.octaviantocan.pulls.data.PersonalAuthError
import com.octaviantocan.pulls.data.RpcRequest
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IosPersonalSessionStoreTest {
    @Test
    fun anUnconfiguredAppCannotOpenPersonalWorkAndNativeReadFailuresStayRetryable() = runTest {
        val storage = MemoryStorage()
        val store = IosPersonalSessionStore(storage, "", RpcTransport { error("No personal request") })
        assertFalse(store.configured)
        assertNull(store.open(backgroundScope) { true })
        storage.unavailable = true
        assertFailsWith<PersonalAuthError> { store.verifyStorage() }
        storage.unavailable = false
        store.verifyStorage()
        storage.unavailable = true
        assertFailsWith<PersonalAuthError> { store.verifyStorage() }
        assertEquals(0, storage.writes)
    }

    @Test
    fun malformedDurableDataDoesNotBecomeAnEmptySuccessfulVaultOrGetOverwritten() {
        val storage = MemoryStorage().apply { bytes = "private-invalid-vault".encodeToByteArray() }
        val store = IosPersonalSessionStore(storage, "https://accounts.example", RpcTransport { error("No request") })
        val failure = assertFailsWith<PersonalAuthError> { store.verifyStorage() }
        assertFalse(failure.message.orEmpty().contains("private-invalid"))
        assertEquals("private-invalid-vault", storage.bytes!!.decodeToString())
        assertEquals(0, storage.writes)
    }

    @Test
    fun presentEmptyNativeContentsStayInvalidAndAMissingReadCanBeRetried() {
        val storage = MemoryStorage().apply { bytes = byteArrayOf() }
        val store = IosPersonalSessionStore(storage, "", RpcTransport { error("No personal request") })
        assertFailsWith<PersonalAuthError> { store.verifyStorage() }
        assertEquals(0, assertNotNull(storage.bytes).size)
        assertEquals(0, storage.writes)
        storage.bytes = null
        store.verifyStorage()
        assertEquals(0, storage.writes)
    }

    @Test
    fun signOutRevokesEveryScenesRuntimeBeforeCallbacksAndRejectsALateHeldProfile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val storage = MemoryStorage().apply {
            bytes = """{"version":1,"accepted":{"origin":"https://accounts.example","token":"pully_${"A".repeat(43)}","accountId":"account-42","githubId":42},"pending":[]}""".encodeToByteArray()
        }
        val requests = mutableListOf<RpcRequest>()
        val release = CompletableDeferred<Unit>()
        var hold = false
        val transport = RpcTransport { request ->
            requests += request
            if (request.url.endsWith("/session")) {
                if (hold) withContext(NonCancellable) { release.await() }
                RpcResponse(200, """{"accountId":"account-42","githubId":42,"login":"reader","authority":"publicRead","sessionExpiresAt":9999999999,"accessExpiresAt":null,"refreshAvailable":false}""", "application/json")
            } else RpcResponse(503, """{"error":{"kind":"unavailable"}}""", "application/json")
        }
        val store = IosPersonalSessionStore(storage, "https://accounts.example", transport)
        val first = assertNotNull(store.open(backgroundScope) { true })
        val second = assertNotNull(store.open(backgroundScope) { true })
        first.foreground(true)
        second.foreground(true)
        var notified = 0
        val detachFirst = store.observeIdentityChanged {
            assertNull(second.openSignIn(backgroundScope) { error("The old scene cannot launch a browser") })
            notified++
            error("One retiring window must not keep another window current")
        }
        val detachSecond = store.observeIdentityChanged { notified++ }
        try {
            first.restore(); runCurrent()
            assertEquals("reader", first.view.value.account?.login)
            hold = true
            second.restore(); runCurrent()
            assertTrue(second.view.value.checking)
            first.signOut()
            assertEquals(2, notified, "Both windows retire synchronously before the next frame")
            assertNull(first.view.value.account)
            assertNull(second.openSignIn(backgroundScope) { error("No browser") })
            release.complete(Unit); runCurrent()
            assertNull(second.view.value.account, "An already-delivered old response cannot republish the profile")
            assertTrue(requests.all { it.url.startsWith("https://accounts.example/api/personal/v1/") })
            assertTrue(requests.all { it.headers["Authorization"] == "Bearer pully_${"A".repeat(43)}" })
            assertTrue(storage.bytes!!.decodeToString().contains("\"accepted\":null"))
            assertTrue(storage.bytes!!.decodeToString().contains("pully_"), "Uncertain revocation keeps captured cleanup authority")
        } finally {
            release.complete(Unit)
            detachFirst(); detachSecond(); first.close(); second.close(); runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class MemoryStorage : NativePersonalSessionStorage {
        var bytes: ByteArray? = null
        var unavailable = false
        var writes = 0
        override fun read(): NativePersonalSessionRead { check(!unavailable); return NativePersonalSessionRead(bytes?.copyOf()) }
        override fun write(contents: ByteArray) { check(!unavailable); writes++; bytes = contents.copyOf() }
    }
}
