package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.ui.home.NativeSharedLinkHandoff
import com.octaviantocan.pulls.ui.home.ShareImportSource
import com.octaviantocan.pulls.ui.home.SharedLinkImport
import com.octaviantocan.pulls.ui.home.decodeSharedLink
import com.octaviantocan.pulls.ui.home.encodeSharedLink
import com.octaviantocan.pulls.ui.settings.AccountObservationOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeShareArrivalOrderTest {
    @Test fun explicitColdLaunchPendingDuringVerificationWinsOverSavedShare() = runTest {
        val fixture = Fixture(backgroundScope)
        try {
            fixture.observations.resumed()
            runCurrent()
            fixture.incoming.receive("https://github.com/octo/repo/pull/22", fixture.audience)
            val explicit = fixture.incoming.pending
            fixture.verification.complete(true)
            runCurrent()
            assertSame(explicit, fixture.incoming.pending)
            assertTrue(fixture.open())
            assertEquals("octo/repo/22", (fixture.root.navigation.stack.last() as Screen.Review).target.serverTarget)
            assertNull(fixture.saved)
            assertEquals(1, fixture.reads)
        } finally { fixture.close() }
    }

    @Test fun consumedExplicitDestinationStillWinsWhenVerificationImportsSavedShare() = runTest {
        val fixture = Fixture(backgroundScope)
        try {
            fixture.incoming.receive("https://github.com/octo/repo/pull/22", fixture.audience)
            assertTrue(fixture.open())
            val explicit = fixture.root.navigation.stack.last()
            assertNull(fixture.incoming.pending)
            fixture.observations.resumed()
            runCurrent()
            fixture.verification.complete(true)
            runCurrent()
            assertNull(fixture.incoming.pending)
            assertSame(explicit, fixture.root.navigation.stack.last())
            assertEquals(2, fixture.root.navigation.stack.size)
            assertNull(fixture.saved)
            assertEquals(1, fixture.reads)
        } finally { fixture.close() }
    }

    @Test fun verifiedForegroundWithoutExplicitArrivalImportsTheSavedDestinationOnce() = runTest {
        val fixture = Fixture(backgroundScope)
        try {
            fixture.observations.resumed()
            runCurrent()
            assertEquals(0, fixture.reads)
            assertNull(fixture.incoming.pending)
            fixture.verification.complete(true)
            runCurrent()
            assertTrue(fixture.open())
            assertEquals("octo/repo/11", (fixture.root.navigation.stack.last() as Screen.Review).target.serverTarget)
            fixture.imports.sync()
            assertNull(fixture.incoming.pending)
            assertEquals(2, fixture.root.navigation.stack.size)
            assertNull(fixture.saved)
        } finally { fixture.close() }
    }

    private class Fixture(scope: CoroutineScope) {
        val incoming = IncomingLinkInbox()
        val root = PullyRootState(scope, { "all" }, AppNavigation()) { true }
        val audience = requireNotNull(incomingLinkAudience(RepoConfiguration("https://server.example", emptyMap()), "reader@example.test", true))
        val verification = CompletableDeferred<Boolean>()
        var saved: String? = requireNotNull(encodeSharedLink("https://github.com/octo/repo/pull/11"))
        var reads = 0
        private var ready = false
        private var grant = 0L
        private val native = object : NativeSharedLinkHandoff {
            override fun take(namespace: String, server: String): String? {
                assertEquals("a".repeat(64), namespace)
                assertEquals("https://server.example", server)
                reads++
                return saved.also { saved = null }
            }
            override fun revoke() = error("Same-source verification must preserve the saved share.")
        }
        val imports = SharedLinkImport(native, {
            if (ready && root.current) ShareImportSource("a".repeat(64), "https://server.example", 1, 1, grant) else null
        }, { payload -> incoming.receive(requireNotNull(decodeSharedLink(payload)), audience) })
        val observations = AccountObservationOwner(scope, { verification.await() }, {
            ready = false
            grant++
            root.pauseObservations()
        }, {
            root.resumeObservations()
            ready = true
            grant++
            imports.sync()
        })
        suspend fun open() = incoming.openPending(audience, root.navigation, { root.current }, {}, { error("The canonical fixture link must remain valid.") })
        fun close() { observations.close(); imports.close(); root.close() }
    }
}
