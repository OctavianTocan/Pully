package com.octaviantocan.pulls.data

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    val android: String,
    val sdk: Int,
    val theme: String,
    val locale: String,
    val sentAt: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("text", text.trim())
        put("app", buildJsonObject { put("version", version); put("versionCode", versionCode) })
        put("screen", screen)
        place?.let { put("place", it) }
        put("device", buildJsonObject { put("manufacturer", manufacturer); put("model", model); put("android", android); put("sdk", sdk) })
        put("theme", theme)
        put("locale", locale)
        put("sentAt", sentAt)
    }

    companion object {
        /** A report of [text] from this phone, as it is now. */
        fun of(context: Context, text: String, screen: String, place: String?, dark: Boolean): FeedbackReport {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
            return FeedbackReport(
                text = text,
                version = info.versionName.orEmpty(),
                versionCode = code,
                screen = screen,
                place = place,
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                android = Build.VERSION.RELEASE.orEmpty(),
                sdk = Build.VERSION.SDK_INT,
                theme = if (dark) "dark" else "light",
                locale = AppSettings.get(context).locale,
                sentAt = java.time.Instant.now().toString(),
            )
        }
    }
}

/** A note the account kept, as the Feedback page lists it: its first words, when, and whether an agent has done it. */
data class FeedbackItem(val id: String, val text: String, val createdAt: String, val done: Boolean)

internal fun JsonObject.asFeedbackItem() = FeedbackItem(s("id"), s("text"), s("createdAt"), s("status") == "done")

/**
 * What is typed in Feedback and not sent yet. It outlives the sheet, so closing it by a stray swipe, or a send that
 * fails, never loses the words; a send that works clears it.
 */
object FeedbackDraft {
    var text by mutableStateOf("")
}
