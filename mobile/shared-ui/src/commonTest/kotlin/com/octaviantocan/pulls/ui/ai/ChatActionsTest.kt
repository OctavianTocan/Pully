package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.data.ChatMessage
import com.octaviantocan.pulls.data.RpcConnectionException
import com.octaviantocan.pulls.ui.review.DiffHarness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatActionsTest {
    @Test
    fun theActualRetainedInputSetterCannotEditAfterPolicyRevocationHeadAbaOrDisposal() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        var policy = AiCapabilities.Full
        val old = ChatActions(h.page, h.page.captureView()) { policy }
        val edit = old.changeInput
        edit("Original draft")
        assertEquals("Original draft", chatUi(h.page).chatInput)
        policy = AiCapabilities.ReadOnly
        edit("Revoked old edit")
        assertEquals("Original draft", chatUi(h.page).chatInput)
        policy = AiCapabilities.Full
        h.head = "head-b"; h.page.refresh(); runCurrent()
        h.head = "head-a"; h.page.refresh(); runCurrent()
        val current = ChatActions(h.page, h.page.captureView()) { policy }
        current.changeInput("New head draft")
        edit("Old head edit")
        assertEquals("New head draft", chatUi(h.page).chatInput)
        val retained = current.changeInput
        current.close(); retained("Disposed edit")
        assertEquals("New head draft", chatUi(h.page).chatInput)
        h.page.detach()
    }

    @Test
    fun historyErrorsKeepARealRetryAndARepeatedTapDoesNotCancelItsRequest() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        var fail = true
        var reads = 0
        val reply = CompletableDeferred<Unit>()
        h.handler = { name, args -> when (name) {
            "chat-history" -> {
                reads++
                if (fail) error("Offline")
                reply.await()
                Json.parseToJsonElement("""{"chats":[{"id":"history-1","title":"Why?","at":"2026-10-21T10:00:00Z","turns":2}]}""")
            }
            else -> h.default(name, args)
        } }
        val buttons = ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }
        assertTrue(buttons.loadHistory(force = true)); runCurrent()
        assertEquals("Pully could not load earlier chats. Try again.", h.page.chatHistoryError)
        assertFalse(h.page.chatHistoryLoading)
        fail = false
        assertTrue(buttons.loadHistory(force = true)); runCurrent()
        assertFalse(buttons.loadHistory(force = true))
        assertEquals(2, reads)
        reply.complete(Unit); runCurrent()
        assertNull(h.page.chatHistoryError)
        assertFalse(h.page.chatHistoryLoading)
        assertEquals("history-1", h.page.chatHistory!!.single().id)
        h.page.detach()
    }

    @Test
    fun aQuestionKeepsItsReferenceLocaleAndOriginalRpcAndStartsOnlyOnce() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val starts = mutableListOf<JsonObject>()
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> { starts += args; JsonPrimitive("chat-1") }
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        val buttons = ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }
        assertTrue(buttons.send("  Why this?  ", AskReference("src/file.kt", "The file note.")))
        assertEquals("About src/file.kt: \"The file note.\"\n\nWhy this?", chatUi(h.page).chatPending)
        assertTrue(h.page.taskFor("chat")?.running == true)
        assertFalse(buttons.send("A duplicate"))
        runCurrent()
        val request = starts.single()
        assertEquals("chat", request.getValue("kind").jsonPrimitive.content)
        assertEquals("pull-1", request.getValue("key").jsonPrimitive.content)
        assertEquals("en", request.getValue("locale").jsonPrimitive.content)
        assertEquals("About src/file.kt: \"The file note.\"\n\nWhy this?", request.getValue("text").jsonPrimitive.content)
        assertFalse("retry" in request)
        h.page.detach()
    }

    @Test
    fun unavailableEmptyDisposedAndRetiredButtonsCannotStartOrChangeAConversation() = runTest {
        val h = DiffHarness(backgroundScope)
        val unloaded = ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }
        assertFalse(unloaded.send("Before load")); assertFalse(unloaded.newChat())
        h.page.load(); runCurrent()
        val calls = mutableListOf<String>()
        h.handler = { name, args -> calls += name; h.default(name, args) }
        var policy = AiCapabilities.ReadOnly
        val buttons = ChatActions(h.page, h.page.captureView()) { policy }
        assertFalse(unloaded.send("Old opening button"))
        assertFalse(buttons.send("Read-only")); assertFalse(buttons.newChat()); assertFalse(buttons.restore("old") {})
        policy = AiCapabilities.Full
        assertFalse(buttons.send("  "))
        buttons.close()
        assertFalse(buttons.send("Disposed")); assertFalse(buttons.retry(null))
        val oldHead = ChatActions(h.page, h.page.captureView()) { policy }
        h.head = "head-b"; h.page.refresh(); runCurrent()
        h.head = "head-a"; h.page.refresh(); runCurrent()
        calls.clear()
        assertFalse(oldHead.send("Head ABA")); assertFalse(oldHead.newChat())
        val oldSource = ChatActions(h.page, h.page.captureView()) { policy }
        h.generation++
        assertFalse(oldSource.send("Retired source")); assertFalse(oldSource.restore("old") {})
        runCurrent(); assertTrue(calls.isEmpty())
        h.page.detach()
    }

    @Test
    fun newChatEmptiesImmediatelyAndAQuestionWaitsForThatExactClear() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        h.page.loadChatHistory(); runCurrent()
        h.page.saved = h.page.saved!!.copy(chat = listOf(ChatMessage("user", "Previous question")))
        val clear = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        h.handler = { name, args ->
            calls += name
            when (name) {
                "chat-clear" -> { clear.await(); JsonObject(emptyMap()) }
                "ai-task-start" -> JsonPrimitive("new-chat-task")
                "ai-task-wait" -> awaitCancellation()
                else -> h.default(name, args)
            }
        }
        val buttons = ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }
        assertTrue(buttons.newChat())
        assertTrue(h.page.saved!!.chat.isEmpty())
        assertFalse(buttons.newChat())
        assertTrue(buttons.send("Next conversation"))
        assertTrue(buttons.waiting)
        runCurrent(); assertEquals(listOf("chat-clear"), calls)
        clear.complete(Unit); runCurrent()
        assertFalse(buttons.waiting)
        assertTrue(calls.indexOf("review-state") in 1 until calls.indexOf("ai-task-start"))
        assertEquals(1, calls.count { it == "ai-task-start" })
        assertEquals(1, calls.count { it == "chat-history" })
        h.page.detach()
    }

    @Test
    fun aQueuedQuestionCannotStartAfterPolicyRevocationOrSheetDisposal() = runTest {
        for (dispose in listOf(false, true)) {
            val h = DiffHarness(backgroundScope)
            h.page.load(); runCurrent()
            val clear = CompletableDeferred<Unit>()
            var starts = 0
            h.handler = { name, args -> when (name) {
                "chat-clear" -> { clear.await(); JsonObject(emptyMap()) }
                "ai-task-start" -> { starts++; JsonPrimitive("wrong-task") }
                else -> h.default(name, args)
            } }
            var policy = AiCapabilities.Full
            val buttons = ChatActions(h.page, h.page.captureView()) { policy }
            assertTrue(buttons.newChat())
            assertTrue(buttons.send("Keep this unsent question"))
            runCurrent()
            if (dispose) buttons.close() else policy = AiCapabilities.ReadOnly
            clear.complete(Unit); runCurrent()
            assertEquals(0, starts)
            assertNull(chatUi(h.page).chatPending)
            assertEquals("Keep this unsent question", chatUi(h.page).chatInput)
            assertEquals("The question was not sent. Try again.", chatUi(h.page).chatError)
            h.page.detach()
        }
    }

    @Test
    fun aQuestionAfterAFailedNewChatKeepsItsDraftInsteadOfJoiningTheOldConversation() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val clear = CompletableDeferred<Unit>()
        var starts = 0
        h.handler = { name, args -> when (name) {
            "chat-clear" -> { clear.await(); error("The server is offline") }
            "ai-task-start" -> { starts++; JsonPrimitive("wrong-conversation") }
            else -> h.default(name, args)
        } }
        val buttons = ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }
        assertTrue(buttons.newChat())
        assertTrue(buttons.send("Question for the new chat"))
        runCurrent(); clear.complete(Unit); runCurrent()
        assertEquals(0, starts)
        assertEquals("Question for the new chat", chatUi(h.page).chatInput)
        assertEquals(RpcConnectionException().message, chatUi(h.page).chatError)
        assertNull(chatUi(h.page).chatPending)
        h.page.detach()
    }

    @Test
    fun aRejectedStartCanRetryTheSameQuestionThroughTheRealRequest() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val starts = mutableListOf<JsonObject>()
        var fail = true
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> { starts += args; if (fail) h.serverError("Provider unavailable") else JsonPrimitive("retried-chat") }
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        val buttons = ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }
        assertTrue(buttons.send("Original question")); runCurrent()
        assertEquals("Provider unavailable", chatUi(h.page).chatError)
        assertNull(chatUi(h.page).chatPending)
        fail = false
        assertTrue(buttons.retry("Original question")); runCurrent()
        assertEquals(2, starts.size)
        assertEquals("Original question", starts.last().getValue("text").jsonPrimitive.content)
        assertFalse("retry" in starts.last())
        assertNull(chatUi(h.page).chatError)
        h.page.detach()
    }

    @Test
    fun restoreUsesTheRealHistoryIdAndOpensAskWithARecoverableReloadFailure() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        var restored = 0
        var request: JsonObject? = null
        h.handler = { name, args -> when (name) {
            "chat-restore" -> { request = args; JsonObject(emptyMap()) }
            "review-state" -> error("Offline")
            else -> h.default(name, args)
        } }
        val buttons = ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }
        assertTrue(buttons.restore("chat-history-2") { restored++ })
        runCurrent()
        assertEquals("pull-1", request!!.getValue("key").jsonPrimitive.content)
        assertEquals("chat-history-2", request!!.getValue("id").jsonPrimitive.content)
        assertEquals(1, restored)
        assertEquals("Couldn't load that chat", chatUi(h.page).chatError)
        h.page.detach()
    }

    @Test
    fun anUncooperativeOldRestoreCannotReloadNavigateOrClearTheNewHeadDraft() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val reply = CompletableDeferred<Unit>()
        var restored = 0
        var savedReads = 0
        h.handler = { name, args -> when (name) {
            "chat-restore" -> { withContext(NonCancellable) { reply.await() }; error("Old restore failed") }
            "review-state" -> { savedReads++; h.default(name, args) }
            else -> h.default(name, args)
        } }
        val old = ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }
        assertTrue(old.restore("old-chat") { restored++ }); runCurrent()
        h.head = "head-b"; h.page.refresh(); runCurrent()
        h.head = "head-a"; h.page.refresh(); runCurrent()
        val current = chatUi(h.page)
        current.chatInput = "New head draft"
        current.chatError = "New head problem"
        savedReads = 0
        reply.complete(Unit); runCurrent()
        assertEquals(0, savedReads)
        assertEquals(0, restored)
        assertEquals("New head draft", current.chatInput)
        assertEquals("New head problem", current.chatError)
        h.page.detach()
    }

    @Test
    fun draftsSurviveSameOwnerReopeningButNotAnUnobservedHeadAba() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        chatUi(h.page).chatInput = "Private unsent words"
        ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }.close()
        assertEquals("Private unsent words", chatUi(h.page).chatInput)
        h.head = "head-b"; h.page.refresh(); runCurrent()
        h.head = "head-a"; h.page.refresh(); runCurrent()
        assertEquals("", chatUi(h.page).chatInput)
        h.page.detach()
    }
}
