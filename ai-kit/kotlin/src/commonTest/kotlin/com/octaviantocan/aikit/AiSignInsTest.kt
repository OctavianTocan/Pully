package com.octaviantocan.aikit

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSignInsTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun theServersAnswersAreRead() {
        val signIns = json("""{"account":true,"claude":{"signedIn":true,"email":"a@example.com","plan":"max"},"codex":{"signedIn":false,"email":""}}""").asAiSignIns()
        assertEquals(AiSignIns(true, AiSignIn(true, "a@example.com", "max"), AiSignIn(false)), signIns)
        assertEquals("Max", signIns[AiCli.Claude].planName)
        assertEquals(AiSignIns(false, AiSignIn(false), AiSignIn(false)), json("{}").asAiSignIns())
        assertEquals(
            AiSignInStart("https://auth.openai.com/codex/device", "ABCD-EFGH", false),
            json("""{"url":"https://auth.openai.com/codex/device","code":"ABCD-EFGH"}""").asAiSignInStart(),
        )
        assertFailsWith<IllegalArgumentException> { json("""{"url":"http://example.com"}""").asAiSignInStart() }
        assertTrue(json("""{"signedIn":true}""").asAiSignInFinished())
        assertFalse(json("""{"signedIn":"yes"}""").asAiSignInFinished())
    }

    @Test
    fun onlyThisSignInsClaudeCodeIsSent() {
        val code = "${"a".repeat(16)}#${"b".repeat(16)}"
        val url = "https://claude.ai/oauth/authorize?code=true&state=${"b".repeat(16)}"
        assertTrue(isClaudeSignInCode(" $code "))
        assertFalse(isClaudeSignInCode("short#code"))
        assertFalse(isClaudeSignInCode(null))
        assertTrue(isCodeFor(url, code))
        assertFalse(isCodeFor(url, "${"a".repeat(16)}#${"c".repeat(16)}"))
        assertFalse(isCodeFor("https://claude.ai/oauth/authorize", code))
    }

    @Test
    fun thePageStepFollowsTheStatusAndTheStartedSignIn() {
        val signedOut = AiSignIns(true, AiSignIn(false), AiSignIn(true))
        val paste = AiSignInStart("https://claude.ai/x", null, true)
        val device = AiSignInStart("https://auth.openai.com/codex/device", "ABCD", false)
        assertEquals(AiSignInStep.Loading, aiSignInStep(AiCli.Claude, null, null, null))
        assertEquals(AiSignInStep.Unavailable, aiSignInStep(AiCli.Claude, null, null, "offline"))
        assertEquals(AiSignInStep.NeedsAccount, aiSignInStep(AiCli.Claude, signedOut.copy(account = false), paste, null))
        assertEquals(AiSignInStep.PasteCode, aiSignInStep(AiCli.Claude, signedOut, paste, null))
        assertEquals(AiSignInStep.EnterCode, aiSignInStep(AiCli.Codex, signedOut, device, null))
        assertEquals(AiSignInStep.SignedOut, aiSignInStep(AiCli.Claude, signedOut, null, null))
        assertEquals(AiSignInStep.SignedIn, aiSignInStep(AiCli.Codex, signedOut, null, null))
    }

    private class Fake(
        private val answers: MutableList<() -> Boolean>,
        private val now: AiSignIns? = null,
        var ready: Boolean = true,
    ) : AiSignInServices {
        var asked = 0
        override fun current() = ready
        override suspend fun status() = now ?: error("offline")
        override suspend fun start(cli: AiCli) = error("unused")
        override suspend fun finish(cli: AiCli, code: String?): Boolean { asked++; return answers.removeAt(0)() }
        override suspend fun cancel(cli: AiCli) {}
        override suspend fun signOut(cli: AiCli) {}
        override fun openPage(url: String) {}
        override fun copy(text: String) {}
    }

    @Test
    fun waitingForCodexEndsDoneSignedInMeanwhileFailedOrStopped() = runTest {
        val waiting = Fake(mutableListOf({ false }, { false }, { true }))
        assertEquals(DeviceSignInEnd.Done, waiting.awaitDeviceSignIn(AiCli.Codex))
        assertEquals(3, waiting.asked)

        val signedIn = AiSignIns(true, AiSignIn(false), AiSignIn(true))
        val meanwhile = Fake(mutableListOf({ error("The sign-in ended.") }), now = signedIn)
        assertEquals(DeviceSignInEnd.FinishedMeanwhile(signedIn), meanwhile.awaitDeviceSignIn(AiCli.Codex))

        val failed = Fake(mutableListOf({ error("The sign-in ended.") }))
        assertEquals(DeviceSignInEnd.Failed("The sign-in ended."), failed.awaitDeviceSignIn(AiCli.Codex))

        val stopped = Fake(mutableListOf(), ready = false)
        assertEquals(DeviceSignInEnd.Stopped, stopped.awaitDeviceSignIn(AiCli.Codex))
        assertEquals(0, stopped.asked)
    }
}
