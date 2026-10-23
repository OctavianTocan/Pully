package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.sound.Cue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AiConnectionsStateTest {
    @Test
    fun anonymousEligibilityUsesExplicitProviderCapabilitiesWithoutGitHubOrPrivateAuthority() = runTest {
        val h = AiConnectionsHarness(backgroundScope)
        val mount = h.open(AiConnectionKind.PiEndpoint); runCurrent()
        assertNull(h.account)
        assertEquals(AiSharedDefaultStatus.Selected, h.state.sharedDefault)
        assertEquals(AiConnectionKind.entries.toSet(), AiConnectionKind.entries.mapNotNull { h.state.view(it)?.kind }.toSet())
        assertEquals(AiConnectionStatus.Unsupported, h.state.view(AiConnectionKind.ClaudeSubscription)?.status)
        assertEquals(AiConnectionStatus.Unsupported, h.state.view(AiConnectionKind.ChatGPTSubscription)?.status)
        val drawn = h.actions(mount)
        drawn.endpoint("https://pi.example.com/v1")
        drawn.model("sample-model")
        drawn.credential("sample-entered-key")
        assertTrue(drawn.canSavePi)
        drawn.savePi()
        assertEquals("", h.state.credentialInput) // Consume secret input before even an immediate transport can return.
        runCurrent()
        val sent = h.configurations.single()
        assertNull(sent.second)
        assertEquals("https://pi.example.com/v1", sent.first.endpoint)
        assertEquals("sample-model", sent.first.preferredModel)
        assertEquals("sample-entered-key", (sent.first.credential as AiCredentialWrite.Replace).value)
        assertFalse(sent.first.toString().contains("sample-entered-key"))
        assertFalse(sent.first.toString().contains("pi.example.com"))
        val configured = assertNotNull(h.state.view(AiConnectionKind.PiEndpoint))
        assertEquals(AiConnectionStatus.Unverified, configured.status)
        assertFalse(configured.capabilities.execute)
        assertEquals("https://pi.example.com", configured.endpointOrigin?.value)
        assertEquals(AiSharedDefaultStatus.Selected, h.state.sharedDefault)
        h.close()
    }

    @Test
    fun endpointAndCredentialChangesKeepTheirExplicitWriteSemanticsAndExpectedRevision() = runTest {
        for (mode in AiPiCredentialMode.entries) {
            val h = AiConnectionsHarness(backgroundScope)
            h.snapshot = h.snapshot.withPi(configuredPi(h.account))
            val mount = h.open(AiConnectionKind.PiEndpoint); runCurrent()
            val drawn = h.actions(mount)
            assertEquals(AiPiCredentialMode.Keep, h.state.credentialMode)
            assertEquals("", h.state.endpointInput) // An origin is never substituted for the stored full URL.
            drawn.endpoint("https://replacement.example.com/v2")
            assertFalse(drawn.canSavePi)
            drawn.savePi(); runCurrent()
            assertTrue(h.configurations.isEmpty())
            if (mode == AiPiCredentialMode.Keep) drawn.endpoint("") else drawn.credentialMode(mode)
            if (mode == AiPiCredentialMode.Replace) drawn.credential("replacement-sample-key")
            drawn.model("") // Empty explicitly clears; null in the service request is reserved for keeping.
            assertTrue(drawn.canSavePi)
            drawn.savePi(); runCurrent()
            val (change, expected) = h.configurations.single()
            assertEquals(AiConnectionVersion("sample-pi", 1), expected)
            assertEquals(if (mode == AiPiCredentialMode.Keep) null else "https://replacement.example.com/v2", change.endpoint)
            assertEquals("", change.preferredModel)
            when (mode) {
                AiPiCredentialMode.Keep -> assertEquals(AiCredentialWrite.Keep, change.credential)
                AiPiCredentialMode.Clear -> assertEquals(AiCredentialWrite.Clear, change.credential)
                AiPiCredentialMode.Replace -> assertEquals("replacement-sample-key", (change.credential as AiCredentialWrite.Replace).value)
            }
            assertEquals("", h.state.credentialInput)
            h.close()
        }
    }

    @Test
    fun theFormPreservesTheActual256ByteModelDomainIncludingUtf8Identifiers() = runTest {
        for (model in listOf("m".repeat(256), "é".repeat(128))) {
            val h = AiConnectionsHarness(backgroundScope)
            val mount = h.open(AiConnectionKind.PiEndpoint); runCurrent()
            val drawn = h.actions(mount)
            drawn.endpoint("https://pi.example.com/v1"); drawn.credential("sample-key"); drawn.model(model)
            assertTrue(drawn.canSavePi)
            drawn.savePi(); runCurrent()
            assertEquals(model, h.configurations.single().first.preferredModel)
            assertEquals(model, h.state.view(AiConnectionKind.PiEndpoint)?.preferredModel)
            h.close()
        }
    }

    @Test
    fun invalidEndpointAndCredentialInputCannotReachTheServiceOrReplaceItsCurrentStatus() = runTest {
        val h = AiConnectionsHarness(backgroundScope)
        val mount = h.open(AiConnectionKind.PiEndpoint); runCurrent()
        val drawn = h.actions(mount)
        drawn.endpoint("https://pi.example.com/v1")
        for (invalidKey in listOf("", "a key with spaces", "tab\tkey", "non-ascii-é")) {
            drawn.credential(invalidKey)
            assertFalse(drawn.canSavePi)
            drawn.savePi(); runCurrent()
        }
        drawn.credential("valid-sample-key")
        for (endpoint in listOf("http://pi.example.com/v1", "https://secret@example.com/v1", "https://pi.example.com/v1?key=sample")) {
            drawn.endpoint(endpoint)
            assertFalse(drawn.canSavePi)
            drawn.savePi(); runCurrent()
        }
        assertTrue(h.configurations.isEmpty())
        assertEquals(AiConnectionStatus.Disconnected, h.state.view(AiConnectionKind.PiEndpoint)?.status)
        assertNull(h.state.error(AiConnectionKind.PiEndpoint))
        assertTrue(h.cues.isEmpty())
        mount.close()
        assertEquals("", h.state.credentialInput)
        h.close()
    }

    @Test
    fun unsupportedSubscriptionKindsCannotStartAnInventedLoginOrFallback() = runTest {
        val h = AiConnectionsHarness(backgroundScope)
        for (kind in listOf(AiConnectionKind.ClaudeSubscription, AiConnectionKind.ChatGPTSubscription)) {
            val mount = h.open(kind); runCurrent()
            val drawn = h.actions(mount)
            assertTrue(h.state.view(kind)?.approvalRequired == true)
            assertFalse(drawn.can(AiConnectionOperation.Connect))
            drawn.connect(); drawn.remove(); drawn.savePi(); runCurrent()
            mount.close()
        }
        assertTrue(h.connections.isEmpty())
        assertTrue(h.removals.isEmpty())
        assertTrue(h.configurations.isEmpty())
        assertTrue(h.cues.isEmpty())
        h.close()
    }

    @Test
    fun anUnsupportedProviderCanStillRemoveItsStoredConnectionWhenTheRealServiceAllowsIt() = runTest {
        val h = AiConnectionsHarness(backgroundScope, "pully-account")
        val version = AiConnectionVersion("stored-claude-connection", 3)
        h.snapshot = h.snapshot.map { if (it.kind == AiConnectionKind.ClaudeSubscription) it.copy(
            version = version, hasCredential = true, operations = setOf(AiConnectionOperation.Remove),
        ) else it }
        val mount = h.open(AiConnectionKind.ClaudeSubscription); runCurrent()
        val drawn = h.actions(mount)
        assertFalse(drawn.can(AiConnectionOperation.Connect))
        assertTrue(drawn.can(AiConnectionOperation.Remove))
        drawn.connect(); drawn.remove(); runCurrent()
        assertTrue(h.connections.isEmpty())
        assertEquals(listOf(AiConnectionKind.ClaudeSubscription to version), h.removals)
        assertEquals(AiConnectionStatus.Disconnected, h.state.view(AiConnectionKind.ClaudeSubscription)?.status)
        assertFalse(h.state.view(AiConnectionKind.ClaudeSubscription)?.hasCredential == true)
        h.close()
    }

    @Test
    fun queuedChangesRecheckTheDrawnControlReadAuthorityAndSourceBeforeDispatch() = runTest {
        for (retirement in listOf("control", "page", "gate", "source")) {
            val h = AiConnectionsHarness(backgroundScope)
            val mount = h.open(AiConnectionKind.PiEndpoint); runCurrent()
            val drawn = h.actions(mount)
            drawn.endpoint("https://pi.example.com/v1"); drawn.credential("queued-sample-key")
            drawn.savePi()
            when (retirement) {
                "control" -> drawn.close()
                "page" -> mount.close()
                "gate" -> h.reads.pause()
                else -> h.current = false
            }
            runCurrent()
            assertTrue(h.configurations.isEmpty(), retirement)
            assertEquals("", h.state.credentialInput)
            assertNull(h.state.error(AiConnectionKind.PiEndpoint))
            assertTrue(h.cues.isEmpty())
            h.close()
        }
    }

    @Test
    fun aRetainedConnectionVersionCannotEditOrRemoveItsReplacement() = runTest {
        val h = AiConnectionsHarness(backgroundScope)
        h.snapshot = h.snapshot.withPi(configuredPi(h.account))
        val mount = h.open(AiConnectionKind.PiEndpoint); runCurrent()
        val retained = h.actions(mount)
        retained.model("My unsent model")
        h.snapshot = h.snapshot.withPi(configuredPi(h.account, 2))
        retained.refresh(); runCurrent()
        assertFalse(retained.current)
        retained.model("Stale words"); retained.credential("stale-key"); retained.remove(); retained.connect(); retained.savePi()
        runCurrent()
        assertEquals("My unsent model", h.state.modelInput)
        assertEquals("", h.state.credentialInput)
        assertTrue(h.configurations.isEmpty())
        assertTrue(h.removals.isEmpty())
        val fresh = h.actions(mount)
        fresh.remove(); runCurrent()
        assertEquals(listOf(AiConnectionKind.PiEndpoint to AiConnectionVersion("sample-pi", 2)), h.removals)
        assertEquals(AiConnectionStatus.Disconnected, h.state.view(AiConnectionKind.PiEndpoint)?.status)
        h.close()
    }

    @Test
    fun acceptedChangesSettleAfterPauseAndDismissalAndNeverReplayAnErrorCue() = runTest {
        for (reject in listOf(false, true)) {
            val h = AiConnectionsHarness(backgroundScope)
            val reply = CompletableDeferred<Unit>()
            h.configure = { change, expected ->
                withContext(NonCancellable) { reply.await() }
                if (reject) throw AiConnectionException(AiConnectionProblem.CredentialRejected)
                h.acceptConfiguration(change, expected)
            }
            val mount = h.open(AiConnectionKind.PiEndpoint); runCurrent()
            val drawn = h.actions(mount)
            drawn.endpoint("https://pi.example.com/v1"); drawn.credential("accepted-sample-key"); drawn.savePi(); runCurrent()
            assertEquals(1, h.configurations.size)
            val reads = h.loadCount
            h.reads.pause(); drawn.close(); mount.close()
            reply.complete(Unit); runCurrent()
            assertEquals("", h.state.credentialInput)
            assertEquals(reads, h.loadCount)
            assertFalse(h.state.busy(AiConnectionKind.PiEndpoint))
            if (reject) assertEquals(AiConnectionProblem.CredentialRejected, h.state.error(AiConnectionKind.PiEndpoint))
            else assertEquals(AiConnectionStatus.Unverified, h.state.view(AiConnectionKind.PiEndpoint)?.status)
            assertTrue(h.cues.isEmpty())
            h.reads.resume(); h.open(AiConnectionKind.PiEndpoint); runCurrent()
            if (reject) assertEquals(AiConnectionProblem.CredentialRejected, h.state.error(AiConnectionKind.PiEndpoint))
            assertEquals(1, h.configurations.size)
            assertTrue(h.cues.isEmpty())
            h.close()
        }
    }

    @Test
    fun sourceRetirementSynchronouslyErasesPrivateInputAndRejectsAnAcceptedLateReply() = runTest {
        for (explicit in listOf(false, true)) {
            val h = AiConnectionsHarness(backgroundScope, "pully-account")
            val reply = CompletableDeferred<Unit>()
            h.configure = { change, expected -> withContext(NonCancellable) { reply.await() }; h.acceptConfiguration(change, expected) }
            val mount = h.open(AiConnectionKind.PiEndpoint); runCurrent()
            val drawn = h.actions(mount)
            drawn.endpoint("https://private.example.com/v1"); drawn.model("private-model"); drawn.credential("private-sample-key")
            drawn.savePi(); runCurrent()
            drawn.credential("a-new-unsent-key")
            if (explicit) h.state.close() else h.current = false
            assertNull(h.state.view(AiConnectionKind.PiEndpoint))
            assertEquals("", h.state.endpointInput)
            assertEquals("", h.state.modelInput)
            assertEquals("", h.state.credentialInput)
            assertFalse(drawn.current)
            reply.complete(Unit); runCurrent()
            assertNull(h.state.view(AiConnectionKind.PiEndpoint))
            assertNull(h.state.error(AiConnectionKind.PiEndpoint))
            assertTrue(h.cues.isEmpty())
            h.close()
        }
    }

    @Test
    fun aNewerDraftSurvivesTheAcceptedReceiptAndSubmittedCredentialsAreNeverRestored() = runTest {
        for (reject in listOf(false, true)) {
            val h = AiConnectionsHarness(backgroundScope)
            val reply = CompletableDeferred<Unit>()
            h.configure = { change, expected ->
                withContext(NonCancellable) { reply.await() }
                if (reject) throw IllegalStateException("Do not display sample-secret or https://private.example/key")
                h.acceptConfiguration(change, expected)
            }
            val mount = h.open(AiConnectionKind.PiEndpoint); runCurrent()
            val drawn = h.actions(mount)
            drawn.endpoint("https://pi.example.com/v1"); drawn.credential("submitted-sample-key"); drawn.savePi(); runCurrent()
            assertEquals("", h.state.credentialInput)
            drawn.endpoint("https://next.example.com/v2"); drawn.model("next-model"); drawn.credential("new-unsent-key")
            assertFalse(drawn.canSavePi)
            reply.complete(Unit); runCurrent()
            assertEquals("https://next.example.com/v2", h.state.endpointInput)
            assertEquals("next-model", h.state.modelInput)
            assertEquals("new-unsent-key", h.state.credentialInput)
            if (reject) {
                assertEquals(AiConnectionProblem.Unavailable, h.state.error(AiConnectionKind.PiEndpoint))
                assertEquals(listOf(Cue.Error), h.cues)
            } else assertTrue(h.cues.isEmpty())
            mount.close()
            assertEquals("", h.state.credentialInput)
            assertEquals("https://next.example.com/v2", h.state.endpointInput)
            h.close()
        }
    }

    @Test
    fun cancelledLateReadSuccessOrFailureCannotReplaceANewerMutationReceipt() = runTest {
        for (reject in listOf(false, true)) {
            val h = AiConnectionsHarness(backgroundScope)
            h.snapshot = h.snapshot.withPi(configuredPi(h.account))
            val mount = h.open(AiConnectionKind.PiEndpoint); runCurrent()
            val drawn = h.actions(mount)
            val old = h.snapshot
            val release = CompletableDeferred<Unit>()
            var captured = false
            h.load = {
                if (!captured) {
                    captured = true
                    withContext(NonCancellable) { release.await() }
                    if (reject) throw IllegalStateException("late-private-error")
                    old
                } else h.snapshot
            }
            drawn.refresh(); runCurrent()
            assertTrue(captured)
            drawn.credentialMode(AiPiCredentialMode.Clear)
            drawn.model("updated-model"); drawn.savePi(); runCurrent()
            assertEquals(AiConnectionVersion("sample-pi", 2), h.state.view(AiConnectionKind.PiEndpoint)?.version)
            release.complete(Unit); runCurrent()
            assertEquals(AiConnectionVersion("sample-pi", 2), h.state.view(AiConnectionKind.PiEndpoint)?.version)
            assertEquals("updated-model", h.state.view(AiConnectionKind.PiEndpoint)?.preferredModel)
            assertNull(h.state.loadError)
            assertTrue(h.cues.isEmpty())
            h.close()
        }
    }

    @Test
    fun heldVerificationAndPauseBeforeAFrameControlTheRealTrackedReadLifetime() = runTest {
        val h = AiConnectionsHarness(backgroundScope, initialReady = false)
        val mount = h.open(null); runCurrent()
        assertEquals(0, h.loadCount)
        assertFalse(h.actions(mount).current)
        h.reads.resume(); runCurrent()
        assertEquals(1, h.loadCount)
        val release = CompletableDeferred<Unit>()
        h.load = { withContext(NonCancellable) { release.await() }; h.snapshot }
        h.actions(mount).refresh(); runCurrent()
        assertTrue(h.state.loading)
        h.reads.pause()
        assertFalse(h.state.loading)
        assertFalse(h.actions(mount).current)
        release.complete(Unit); runCurrent()
        assertNull(h.state.loadError)
        h.load = { h.snapshot }
        h.reads.resume(); runCurrent()
        assertEquals(3, h.loadCount)
        assertNotNull(h.state.view(AiConnectionKind.PiEndpoint))
        h.close()
    }

    @Test
    fun foreignAccountOrIncompleteReadsAndRawFailuresNeverBecomeCurrentConnectionState() = runTest {
        for (malformation in listOf("account", "missing", "raw-error")) {
            val h = AiConnectionsHarness(backgroundScope, "opening-account")
            val mount = h.open(null); runCurrent()
            val original = assertNotNull(h.state.view(AiConnectionKind.PiEndpoint))
            h.load = {
                when (malformation) {
                    "account" -> h.snapshot.map { it.copy(accountId = "other-account") }
                    "missing" -> h.snapshot.dropLast(1)
                    else -> throw IllegalStateException("private-key-and-endpoint-must-not-reach-UI")
                }
            }
            h.actions(mount).refresh(); runCurrent()
            assertEquals(original, h.state.view(AiConnectionKind.PiEndpoint))
            assertEquals(AiConnectionProblem.Unavailable, h.state.loadError)
            assertTrue(h.cues.isEmpty())
            h.close()
        }
    }
}

internal class AiConnectionsHarness(scope: CoroutineScope, val account: String? = null, initialReady: Boolean = true) {
    var current = true
    val reads = AiConnectionsReadAuthority(initialReady)
    val cues = mutableListOf<Cue>()
    var loadCount = 0
    var snapshot = listOf(
        AiConnectionView(AiConnectionKind.ClaudeSubscription, AiConnectionStatus.Unsupported, account, approvalRequired = true,
            notice = AiConnectionNotice.ClaudeApprovalRequired),
        AiConnectionView(AiConnectionKind.ChatGPTSubscription, AiConnectionStatus.Unsupported, account, approvalRequired = true,
            notice = AiConnectionNotice.ChatGPTRegistrationRequired),
        disconnectedPi(account),
    )
    var load: suspend () -> List<AiConnectionView> = { snapshot }
    val configurations = mutableListOf<Pair<AiPiChange, AiConnectionVersion?>>()
    val connections = mutableListOf<Pair<AiConnectionKind, AiConnectionVersion?>>()
    val removals = mutableListOf<Pair<AiConnectionKind, AiConnectionVersion>>()
    var configure: suspend (AiPiChange, AiConnectionVersion?) -> AiConnectionView = ::acceptConfiguration
    val state = AiConnectionsState(object : AiConnectionsServices {
        override val accountId = account
        override val sharedDefault = AiSharedDefaultStatus.Selected
        override val reads = this@AiConnectionsHarness.reads
        override fun current() = this@AiConnectionsHarness.current
        override suspend fun load(): List<AiConnectionView> { loadCount++; return this@AiConnectionsHarness.load() }
        override suspend fun connect(kind: AiConnectionKind, expected: AiConnectionVersion?): AiConnectionView {
            connections += kind to expected
            error("This source has no installed subscription authorization")
        }
        override suspend fun configurePi(change: AiPiChange, expected: AiConnectionVersion?): AiConnectionView {
            configurations += change to expected
            return configure(change, expected)
        }
        override suspend fun remove(kind: AiConnectionKind, expected: AiConnectionVersion): AiConnectionView {
            removals += kind to expected
            val removed = if (kind == AiConnectionKind.PiEndpoint) disconnectedPi(account) else snapshot.single { it.kind == kind }.copy(
                status = AiConnectionStatus.Disconnected, version = null, hasCredential = false,
                capabilities = AiProviderCapabilities(), operations = emptySet(),
            )
            snapshot = snapshot.map { if (it.kind == kind) removed else it }
            return removed
        }
    }, scope, AiConnectionsFeedback { cue, _ -> cues += cue })

    fun open(kind: AiConnectionKind?) = AiConnectionsMount(state, kind).also { it.attach() }
    fun actions(mount: AiConnectionsMount) = AiConnectionsActions(state, mount, mount.kind)
    fun close() { state.close(); reads.close() }
    fun acceptConfiguration(change: AiPiChange, expected: AiConnectionVersion?): AiConnectionView {
        val before = snapshot.single { it.kind == AiConnectionKind.PiEndpoint }
        return configuredPi(account, (expected?.revision ?: 0) + 1).copy(
            endpointOrigin = change.endpoint?.let { AiEndpointOrigin.parse("https://" + it.substringAfter("://").substringBefore('/')) } ?: before.endpointOrigin,
            preferredModel = if (change.preferredModel == null) before.preferredModel else change.preferredModel.ifEmpty { null },
            hasCredential = when (change.credential) {
                AiCredentialWrite.Keep -> before.hasCredential
                AiCredentialWrite.Clear -> false
                is AiCredentialWrite.Replace -> true
            },
        ).also { snapshot = snapshot.withPi(it) }
    }
}

internal fun configuredPi(account: String?, revision: Long = 1) = AiConnectionView(
    AiConnectionKind.PiEndpoint, AiConnectionStatus.Unverified, account,
    version = AiConnectionVersion("sample-pi", revision), endpointOrigin = AiEndpointOrigin.parse("https://pi.example.com"),
    preferredModel = "sample-model", hasCredential = true, capabilities = AiProviderCapabilities(configure = true),
    operations = setOf(AiConnectionOperation.ConfigurePi, AiConnectionOperation.Remove), notice = AiConnectionNotice.PiUnverified,
)
private fun disconnectedPi(account: String?) = AiConnectionView(
    AiConnectionKind.PiEndpoint, AiConnectionStatus.Disconnected, account,
    capabilities = AiProviderCapabilities(configure = true), operations = setOf(AiConnectionOperation.ConfigurePi),
)
internal fun List<AiConnectionView>.withPi(view: AiConnectionView) = map { if (it.kind == AiConnectionKind.PiEndpoint) view else it }
