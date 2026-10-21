package com.octaviantocan.pulls.data

import kotlinx.serialization.json.JsonObject

/** Native device metadata adapters supply the existing feedback payload. */
fun interface FeedbackPayload {
    fun toJson(): JsonObject
}

/** A note the account kept, as the Feedback page lists it: its first words, when, and whether an agent has done it. */
data class FeedbackItem(val id: String, val text: String, val createdAt: String, val done: Boolean)

fun JsonObject.asFeedbackItem() = FeedbackItem(s("id"), s("text"), s("createdAt"), s("status") == "done")
