package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.data.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatRowsTest {
    @Test
    fun chatRowsShowUserAssistantAndGroupingUpdatesOnly() {
        val rows = chatRows(
            listOf(
                ChatMessage("user", "Why?"),
                ChatMessage("assistant", ""),
                ChatMessage("toolResult", "file contents"),
                ChatMessage("toolResult", "Grouping updated: 4 groups."),
                ChatMessage("assistant", "Because."),
            ),
        )
        assertEquals(listOf(ChatRow.User("Why?"), ChatRow.Grouping(4), ChatRow.Assistant("Because.")), rows)
    }
}
