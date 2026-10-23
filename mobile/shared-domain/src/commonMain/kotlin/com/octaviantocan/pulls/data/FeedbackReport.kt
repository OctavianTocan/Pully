package com.octaviantocan.pulls.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One note of feedback as the phone sends it (`feedback-send`): the words, and what they were written from, so an agent
 * reading it later knows the version, the screen and the phone without asking (Tavi, 20 Oct 06:10). The server stamps
 * the time itself; [sentAt] is only the phone's clock beside it.
 */
data class FeedbackReport(
    val text: String,
    val version: String,
    val versionCode: Int,
    /** The app's fixed name for the screen it was sent from, as usage stats name it (`home`, `review`, `settings`). */
    val screen: String,
    /** The pull request or repository on that screen (`owner/repo/12`, `owner/repo`), when there is one. */
    val place: String?,
    val manufacturer: String,
    val model: String,
    val android: String?,
    val sdk: Int?,
    val theme: String,
    val locale: String,
    val sentAt: String,
    val ios: String? = null,
) : FeedbackPayload {
    override fun toJson(): JsonObject = buildJsonObject {
        put("text", text.trim())
        put("app", buildJsonObject { put("version", version); put("versionCode", versionCode) })
        put("screen", screen)
        place?.let { put("place", it) }
        put("device", buildJsonObject {
            put("manufacturer", manufacturer); put("model", model)
            android?.let { put("android", it) }; sdk?.let { put("sdk", it) }
            ios?.let { put("ios", it) }
        })
        put("theme", theme)
        put("locale", locale)
        put("sentAt", sentAt)
    }

    companion object {}
}
