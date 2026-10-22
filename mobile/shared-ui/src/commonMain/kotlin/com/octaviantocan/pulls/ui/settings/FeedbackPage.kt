package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.SheetTopGap
import com.octaviantocan.pulls.ui.home.parseInstant
import com.octaviantocan.pulls.ui.home.timeAgo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The longest note the server keeps (`MAX_TEXT` in `backend/src/feedback.rs`). */
private const val MAX_TEXT = 10_000

/** Feedback from Commands: the same page as Settings, Send feedback, in a sheet of its own (nav-feedback-from-anywhere). */
@Composable
fun FeedbackSheet(services: FeedbackServices, onClose: () -> Unit) {
    FeedbackPageOwner(services) { FeedbackSheetBody(services, onClose) }
}

@Composable
private fun FeedbackSheetBody(services: FeedbackServices, onClose: () -> Unit) {
    PullsSheet(onClose, "Send feedback") {
        FeedbackPage(services, onSent = onClose)
    }
}

/**
 * Send feedback: a note, what goes with it (the version, the phone and the screen it came from) as one quiet line, and
 * Send. The account keeps it with the server's time, and agents read it with one of the account's keys (Tavi, 20 Oct
 * 06:10: "a button somewhere for me to leave feedback from the app itself ... stored with timestamps, app version").
 * The account's last notes follow, with Done on those an agent has acted on. [onSent] runs once the server has it.
 */
@Composable
fun ColumnScope.FeedbackPage(services: FeedbackServices, onSent: () -> Unit) {
    FeedbackPageOwner(services) { FeedbackPageBody(services, onSent) }
}

@Composable
internal fun FeedbackPageOwner(services: FeedbackServices, content: @Composable () -> Unit) {
    androidx.compose.runtime.key(services) { content() }
}

@Composable
private fun ColumnScope.FeedbackPageBody(services: FeedbackServices, onSent: () -> Unit) {
    val c = LocalPullsColors.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    // The screen it was opened over: Settings' own name for this page is set only after this first frame.
    val form = rememberFeedbackFormState(services)
    val screen = form.screen
    val place = form.place
    val dark = wantsDark(services.settings)
    val version = services.metadata.version
    LaunchedEffect(Unit) {
        try { services.refresh() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }
    fun send() {
        val text = services.ui.text
        if (text.isBlank() || form.busy || !services.current()) return
        form.busy = true
        form.problem = null
        scope.launch {
            try {
                services.send(text, screen, place, dark)
                if (!services.current()) return@launch
                toaster.show("Feedback sent", cue = Cue.Success)
                onSent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.problem = e.message ?: e.toString()
            }
            form.busy = false
        }
    }

    SheetCard(gap = SheetTopGap) {
        SheetField(null, services.ui.text, { if (services.current()) services.ui.text = it.take(MAX_TEXT) }, placeholder = "What should change?", first = true, enabled = !form.busy, minLines = 5)
    }
    Text(
        listOf("Pully $version", services.metadata.model, services.metadata.osLabel, screenLabel(screen)).filter { it.isNotBlank() }.joinToString(" · "),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm, color = c.muted,
    )
    form.problem?.let { Text(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm, color = c.error) }
    CtaButton(if (form.busy) "Sending…" else "Send feedback", ::send, Modifier.fillMaxWidth().padding(top = 20.dp), enabled = services.ui.text.isNotBlank() && !form.busy)
    val sent = services.ui.sent.orEmpty().take(10)
    if (sent.isNotEmpty()) Group("Sent") {
        SheetCard {
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            sent.forEachIndexed { index, item ->
                SheetRow(item.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(), null, first = index == 0, iconless = true) {
                    Text(if (item.done) "Done" else parseInstant(item.createdAt)?.let { timeAgo(it, now, services.settings.locale) }.orEmpty(), style = PullsType.sm, color = c.muted, maxLines = 1)
                }
            }
        }
    }
}

/** The screen a note came from, in the words the app uses for it. */
private fun screenLabel(screen: String): String = when {
    screen == "home" -> "Inbox"
    screen == "repo" -> "Repository"
    screen == "review" -> "Pull request"
    screen == "uibook" -> "UI Book"
    screen.startsWith("settings") -> "Settings"
    else -> ""
}

/** Actual remembered form/effect state, owned by the same key as its widgets and focus. */
internal class FeedbackFormState(val screen: String, val place: String?) {
    var busy by mutableStateOf(false)
    var problem by mutableStateOf<String?>(null)
}

@Composable
internal fun rememberFeedbackFormState(services: FeedbackServices): FeedbackFormState =
    remember { FeedbackFormState(services.screen(), services.place()) }
