package com.octaviantocan.pulls.ui.personal

import com.octaviantocan.pulls.data.PersonalAccountRuntime
import com.octaviantocan.pulls.data.PersonalAuthOrigin
import com.octaviantocan.pulls.data.PersonalSessionBlobStorage
import com.octaviantocan.pulls.data.PersonalSessionVault
import com.octaviantocan.pulls.data.PersonalSignInState
import com.octaviantocan.pulls.data.RpcRequest
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalAccountSheetOwnerTest {
    @Test
    fun theBoundBrowseCallbackRetiresAHeldConfirmationBeforeAnyDisposalFrame() = runTest {
        val h = Harness(this, holdConfirmation = true)
        val signIn = h.runtime.openSignIn(backgroundScope) { }!!
        var closes = 0
        var stateAtClose: PersonalSignInState? = null
        val owner = PersonalAccountSheetOwner(signIn) {
            closes++
            stateAtClose = signIn.controller.state.value
        }
        signIn.controller.start(); runCurrent()
        assertEquals(1, h.count("/session"))
        assertEquals(h.token, PersonalSessionVault(h.storage).pending().single().token)

        // These are the same production-bound callbacks used by Browse and PullsSheet.onClose.
        owner.dismiss()
        h.confirmation.complete(Unit); runCurrent()
        assertNull(h.vault.accepted(h.origin))
        assertEquals(1, closes)
        assertEquals(PersonalSignInState.Idle, stateAtClose,
            "The host callback may defer composition; it must already have lost sign-in authority")
        assertEquals(0, h.identityChanges)
        assertEquals(1, h.count("/sign-out"))
        assertEquals(0, h.count("/cancel"), "A consumed claim is cleaned up with its captured bearer")
        assertTrue(PersonalSessionVault(h.storage).pending().isEmpty())
        owner.close() // DisposableEffect is only fallback cleanup, after the held response was released.
        h.runtime.close()
    }

    @Test
    fun dismissalAfterAnAcceptedConnectionPreservesTheLegitimateAccount() = runTest {
        val h = Harness(this, holdConfirmation = false)
        val signIn = h.runtime.openSignIn(backgroundScope) { }!!
        val owner = PersonalAccountSheetOwner(signIn) { }
        signIn.controller.start(); runCurrent()
        assertEquals(h.token, h.vault.accepted(h.origin)!!.token)
        owner.dismiss(); runCurrent()
        assertEquals(h.token, h.vault.accepted(h.origin)!!.token)
        assertEquals(1, h.identityChanges)
        assertEquals(0, h.count("/sign-out"))
        h.runtime.close()
    }

    @Test
    fun unconfiguredAndAlreadyDisposedSheetsOnlyCloseOnce() {
        var callbacks = 0
        val owner = PersonalAccountSheetOwner(null) { callbacks++ }
        owner.dismiss(); owner.dismiss(); owner.close()
        assertEquals(1, callbacks)
        val disposed = PersonalAccountSheetOwner(null) { error("A disposed owner must not dismiss a replacement") }
        disposed.close(); disposed.dismiss()
    }

    private class Harness(scope: TestScope, private val holdConfirmation: Boolean) {
        val origin = PersonalAuthOrigin.parse("https://accounts.example")
        val token = "pully_" + "A".repeat(43)
        private val id = "A".repeat(43)
        private val account = """{"accountId":"account-42","githubId":42,"login":"reader","authority":"publicRead","sessionExpiresAt":3600,"accessExpiresAt":null,"refreshAvailable":false}"""
        private val provisional = account.replace("\"sessionExpiresAt\":3600", "\"sessionExpiresAt\":1800")
        private val authorize = "https://github.com/login/oauth/authorize?client_id=pully-test&redirect_uri=https%3A%2F%2Faccounts.example%2Fapi%2Fpersonal%2Fv1%2Fauth%2Fgithub%2Fcallback&response_type=code&state=$id&code_challenge=$id&code_challenge_method=S256&scope=offline_access&prompt=select_account"
        val requests = mutableListOf<RpcRequest>()
        val confirmation = CompletableDeferred<Unit>()
        val storage = object : PersonalSessionBlobStorage {
            private var bytes: ByteArray? = null
            override fun read(): ByteArray? = bytes?.copyOf()
            override fun write(contents: ByteArray) { bytes = contents.copyOf() }
        }
        val vault = PersonalSessionVault(storage)
        var identityChanges = 0
        val runtime = PersonalAccountRuntime(origin, vault, scope.backgroundScope, scope.backgroundScope,
            { 1200 + scope.testScheduler.currentTime / 1000 }, { true }, { identityChanges++ }, RpcTransport { request ->
                requests += request
                val body = when {
                    request.url.endsWith("/capabilities") -> """{"github":{"enabled":true}}"""
                    request.url.endsWith("/start") -> """{"attemptId":"$id","authorizeUrl":"$authorize","expiresAt":1800,"pollAfterMs":2000}"""
                    request.url.endsWith("/status") -> """{"phase":"ready"}"""
                    request.url.endsWith("/claim") -> """{"session":"$token","status":$provisional}"""
                    request.url.endsWith("/session") -> {
                        if (holdConfirmation) withContext(NonCancellable) { confirmation.await() }
                        account
                    }
                    request.url.endsWith("/cancel") -> """{"cancelled":true}"""
                    else -> "true"
                }
                RpcResponse(200, body, "application/json")
            })

        fun count(suffix: String) = requests.count { it.url.endsWith(suffix) }
    }
}
