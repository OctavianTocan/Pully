package com.octaviantocan.pulls.ui.settings

import android.os.Build
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.FeedbackDraft
import com.octaviantocan.pulls.data.FeedbackItem
import com.octaviantocan.pulls.data.FeedbackReport
import com.octaviantocan.pulls.data.Repo
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

/**
 * Where feedback is written from. [place] is the pull request or repository of the page under any sheet, kept by
 * `PullsApp` as pages change; the screen is the one usage stats has on top when Feedback opens.
 */
internal object FeedbackFrom {
    var place: String? = null
}

/**
 * What the Feedback page lists under the note: the account's last notes, kept so a second open shows them at once.
 * They are kept with the account they came from, so after Sign out another account never sees them.
 */
private object FeedbackUi {
    private var kept by mutableStateOf<Pair<String?, List<FeedbackItem>>?>(null)
    var sent: List<FeedbackItem>?
        get() = kept?.takeIf { it.first == com.octaviantocan.pulls.data.Account.email }?.second
        set(value) { kept = value?.let { com.octaviantocan.pulls.data.Account.email to it } }
}

/** The longest note the server keeps (`MAX_TEXT` in `backend/src/feedback.rs`). */
private const val MAX_TEXT = 10_000

/** Feedback from Commands: the same page as Settings, Send feedback, in a sheet of its own (nav-feedback-from-anywhere). */
@Composable
internal fun FeedbackSheet(onClose: () -> Unit) {
    PullsSheet(onClose, "Send feedback") {
        FeedbackPage(onSent = onClose)
    }
}

/**
 * Send feedback: a note, what goes with it (the version, the phone and the screen it came from) as one quiet line, and
 * Send. The account keeps it with the server's time, and agents read it with one of the account's keys (Tavi, 20 Oct
 * 06:10: "a button somewhere for me to leave feedback from the app itself ... stored with timestamps, app version").
 * The account's last notes follow, with Done on those an agent has acted on. [onSent] runs once the server has it.
 */
@Composable
internal fun ColumnScope.FeedbackPage(onSent: () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val repo = remember { Repo(context) }
    // The screen it was opened over: Settings' own name for this page is set only after this first frame.
    val screen = remember { Analytics.screen.takeUnless { it == "settings-feedback" } ?: "settings" }
    val place = remember { FeedbackFrom.place }
    val dark = wantsDark(com.octaviantocan.pulls.data.AppSettings.get(context))
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    LaunchedEffect(Unit) {
        try { FeedbackUi.sent = repo.feedback() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }
    fun send() {
        val text = FeedbackDraft.text
        if (text.isBlank() || busy) return
        busy = true
        problem = null
        scope.launch {
            try {
                repo.sendFeedback(FeedbackReport.of(context, text, screen, place, dark))
                FeedbackDraft.text = ""
                FeedbackUi.sent = runCatching { repo.feedback() }.getOrNull() ?: FeedbackUi.sent
                toaster.show("Feedback sent", cue = Cue.Success)
                onSent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message ?: e.toString()
            }
            busy = false
        }
    }

    SheetCard(gap = SheetTopGap) {
        SheetField(null, FeedbackDraft.text, { FeedbackDraft.text = it.take(MAX_TEXT) }, placeholder = "What should change?", first = true, enabled = !busy, minLines = 5)
    }
    Text(
        listOf("Pully $version", Build.MODEL.orEmpty(), "Android ${Build.VERSION.RELEASE}", screenLabel(screen)).filter { it.isNotBlank() }.joinToString(" · "),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm, color = c.muted,
    )
    problem?.let { Text(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm, color = c.error) }
    CtaButton(if (busy) "Sending…" else "Send feedback", ::send, Modifier.fillMaxWidth().padding(top = 20.dp), enabled = FeedbackDraft.text.isNotBlank() && !busy)
    val sent = FeedbackUi.sent.orEmpty().take(10)
    if (sent.isNotEmpty()) Group("Sent") {
        SheetCard {
            val now = System.currentTimeMillis()
            sent.forEachIndexed { index, item ->
                SheetRow(item.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(), null, first = index == 0, iconless = true) {
                    Text(if (item.done) "Done" else timeAgo(parseInstant(item.createdAt), now), style = PullsType.sm, color = c.muted, maxLines = 1)
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
