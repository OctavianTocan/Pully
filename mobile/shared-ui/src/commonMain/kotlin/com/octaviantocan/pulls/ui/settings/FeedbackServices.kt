package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.FeedbackItem
import com.octaviantocan.pulls.data.FeedbackReport
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Unsaved words/history outlive a sheet, never the source that owns them. */
open class FeedbackState {
    var text by mutableStateOf("")
    var sent by mutableStateOf<List<FeedbackItem>?>(null)
    fun clear() { text = ""; sent = null }
}

/** Generic device and OS labels only: no installation identifiers or credentials. */
data class FeedbackMetadata(
    val version: String,
    val versionCode: Int,
    val manufacturer: String,
    val model: String,
    val osLabel: String,
    val android: String? = null,
    val sdk: Int? = null,
    val ios: String? = null,
) {
    fun report(text: String, screen: String, place: String?, dark: Boolean, locale: String) = FeedbackReport(
        text, version, versionCode, screen, place, manufacturer, model, android, sdk,
        if (dark) "dark" else "light", locale, kotlin.time.Clock.System.now().toString(), ios,
    )
}

interface FeedbackServices {
    val settings: AppSettings
    val metadata: FeedbackMetadata
    val ui: FeedbackState
    fun current(): Boolean
    fun screen(): String
    fun place(): String?
    suspend fun refresh()
    suspend fun send(text: String, screen: String, place: String?, dark: Boolean)
    /** Source retirement only; ordinary dismiss deliberately retains the draft. */
    fun close()
}

class SourceFeedbackServices(
    private val repo: Repo,
    override val settings: AppSettings,
    override val metadata: FeedbackMetadata,
    private val readScreen: () -> String,
    private val readPlace: () -> String?,
    private val owner: () -> Boolean,
    override val ui: FeedbackState = FeedbackState(),
) : FeedbackServices {
    private val opening = runCatching { repo.source() }
    private var closed = false
    private var historyRevision = 0L
    override fun current() = !closed && owner() && opening.fold(
        onSuccess = repo::isCurrent,
        onFailure = { runCatching { repo.source() }.isFailure },
    )
    private suspend fun check() {
        currentCoroutineContext().ensureActive()
        if (!current()) throw CancellationException("The feedback source changed.")
        opening.getOrThrow()
    }
    override fun screen() = readScreen().takeUnless { it == "settings-feedback" } ?: "settings"
    override fun place() = readPlace()
    private suspend fun <T> request(block: suspend () -> T): T = try {
        block().also { check() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        check()
        throw failure
    }
    override suspend fun refresh() {
        check()
        val revision = ++historyRevision
        val items = request { repo.feedback() }
        if (revision == historyRevision) ui.sent = items
    }
    override suspend fun send(text: String, screen: String, place: String?, dark: Boolean) {
        check()
        if (text.isBlank()) return
        request { repo.sendFeedback(metadata.report(text, screen, place, dark, settings.locale)) }
        if (ui.text == text) ui.text = ""
        // Only a confirmed send retires the older list; a failed send retains a useful initial refresh.
        val revision = ++historyRevision
        try {
            val items = repo.feedback()
            check()
            if (revision == historyRevision) ui.sent = items
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            check() // A successful send still succeeds if only refreshing its history failed.
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        historyRevision++
        ui.clear()
    }
}
