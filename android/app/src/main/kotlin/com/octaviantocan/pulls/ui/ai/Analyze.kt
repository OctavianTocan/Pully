package com.octaviantocan.pulls.ui.ai

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc
import com.octaviantocan.pulls.ui.review.ReviewSession
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/** The login GitHub Actions shares analyses as (`ACTIONS_BOT_LOGIN`). */
private const val ACTIONS_BOT_LOGIN = "github-actions[bot]"

/** The palette's "Analyze with AI": starts one unless an analysis is already running. */
fun runAnalyzeFromCommand(session: ReviewSession) {
    if (session.taskFor("analyze")?.running == true) return
    session.runAnalyze()
}

internal fun ReviewSession.runAnalyze() {
    val locale = settings.locale
    startAiTask("analyze", extra = { put("locale", locale) })
}

/** Port of `DiffAnalyzeButton.vue`. */
@Composable
internal fun AnalyzeButtonImpl(session: ReviewSession, modifier: Modifier) {
    val c = LocalPullsColors.current
    if (session.key == null) return
    val task = rememberLiveTask(session, "analyze")
    val hasAiResult = session.hasAiResult
    val analyzing = task?.running == true
    val failed = task?.status == "failed"
    // Running or failed: the button opens the status sheet instead of starting a run.
    val hasStatus = analyzing || failed
    var statusOpen by remember { mutableStateOf(false) }
    val now = rememberNow(analyzing)
    if (hasAiResult && !hasStatus && session.analyzeMode == "rules") {
        if (statusOpen) AnalyzeStatusSheet(session) { statusOpen = false }
        return
    }
    val label = when {
        analyzing -> if (task.startedAt > 0) t("analyze.analyzingFor", "duration" to formatClockDuration(now - task.startedAt)) else t("analyze.analyzing")
        failed -> t("analyze.failed")
        hasAiResult -> t("analyze.reanalyze")
        else -> t("analyze.withAi")
    }
    ActionButton(
        onClick = { if (hasStatus) statusOpen = true else session.runAnalyze() },
        modifier = modifier,
        variant = if (hasAiResult) ButtonVariant.Text else ButtonVariant.Primary,
        icon = if (analyzing || failed) null else "sparkle-duotone",
        small = true,
    ) {
        val fg = if (hasAiResult) c.text else androidx.compose.ui.graphics.Color.White
        if (analyzing) Spinner(fg)
        // `text-red-500` beside an AI result, `text-red-100` on the green primary button.
        else if (failed) PhIcon("warning-circle-duotone", tint = if (hasAiResult) c.error else androidx.compose.ui.graphics.Color(0xFFFEE2E2), size = 18.dp)
        Text(label, style = PullsType.xs, maxLines = 1)
    }
    if (statusOpen) AnalyzeStatusSheet(session) { statusOpen = false }
}

/** Port of `AnalyzeStatusModal.vue`: the live work log, outcome, usage, Stop / Rerun. */
@Composable
internal fun AnalyzeStatusSheet(session: ReviewSession, onDismiss: () -> Unit) {
    val c = LocalPullsColors.current
    val ui = aiUi(session)
    val task = session.taskFor("analyze")
    val analyzing = task?.running == true
    val outcome = outcomeOf(task)
    AiSheet(
        title = t("analyze.statusTitle"),
        onDismiss = onDismiss,
        footer = {
            if (analyzing) ActionButton(onClick = { session.cancelTask(task.id) }, icon = "stop-duotone", small = true) { Text(t("common.stop")) }
            else ActionButton(onClick = { session.runAnalyze() }, variant = ButtonVariant.Primary, icon = "arrow-clockwise-duotone", small = true) { Text(t("common.rerun")) }
        },
    ) {
        if (analyzing && task.id !in ui.startedHere) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhIcon("plugs-connected", tint = c.mute, size = 14.dp)
                Text(t("analyze.resumed"), style = PullsType.xs, color = c.mute)
            }
        }
        AiActivityLogFull(task?.activities.orEmpty(), analyzing, startedAt = task?.startedAt?.takeIf { it > 0 }, endedAt = task?.endedAt, outcome = outcome)
        if (!analyzing) {
            val groups = session.saved?.aiGroups?.size
            when {
                task?.status == "failed" -> ErrorBox(t("analyze.failed"), task.error)
                task?.status == "cancelled" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PhIcon("stop-circle", tint = c.fade, size = 16.dp)
                    Text(t("ai.stopped"), style = PullsType.sm, color = c.fade)
                }
                groups != null && groups > 0 -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PhIcon("check-circle", tint = c.success, size = 16.dp)
                    Text(tc("analyze.done", groups), style = PullsType.sm, color = c.text)
                }
            }
            val wall = if (task != null && task.startedAt > 0 && task.endedAt != null) task.endedAt - task.startedAt else null
            AiUsageLine(task?.usage, durationMs = wall)
        }
    }
}

/** A 16dp spinner in the button's color, for `i-ph:spinner-duotone animate-spin`. */
@Composable
internal fun Spinner(color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp = 16.dp) {
    com.octaviantocan.pulls.ui.design.SpinIcon(tint = color, size = size)
}

/** Share is for analyses the viewer generated, on a pull request (`canShareResult`). */
internal fun canShare(session: ReviewSession): Boolean {
    val analysis = session.saved?.analysis ?: return false
    return session.pull != null && session.hasAiResult && analysis.sharedBy == null
}

/** Port of `DiffShareButton.vue`. */
@Composable
internal fun ShareButtonImpl(session: ReviewSession, modifier: Modifier) {
    val c = LocalPullsColors.current
    val ui = aiUi(session)
    if (!canShare(session)) return
    var confirmOpen by remember { mutableStateOf(false) }
    val own = session.saved?.sharedCommentUrl ?: ui.sharedUrl
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(
            onClick = { confirmOpen = true },
            variant = ButtonVariant.Text,
            icon = if (ui.sharing) null else "share-network-duotone",
            enabled = !ui.sharing,
            small = true,
        ) {
            if (ui.sharing) Spinner(c.text)
            Text(t(if (ui.sharing) "share.sharing" else if (own != null) "share.updateButton" else "share.button"), style = PullsType.xs, maxLines = 1)
        }
        val error = ui.shareError
        if (error != null && !confirmOpen) Text(t("share.failed", "message" to error), Modifier.weight(1f, fill = false), style = PullsType.xs, color = c.error, maxLines = 1)
    }
    if (confirmOpen) ShareSheet(session) { confirmOpen = false }
}

/** Port of `ShareResultModal.vue`, plus the shared comment's link once posted. */
@Composable
private fun ShareSheet(session: ReviewSession, onDismiss: () -> Unit) {
    val c = LocalPullsColors.current
    val ui = aiUi(session)
    val context = LocalContext.current
    val messages = LocalMessages.current
    val own = session.saved?.sharedCommentUrl ?: ui.sharedUrl
    var done by remember { mutableStateOf<String?>(null) }
    // The analysis carries no locale in `review-state`; the phone runs analyses in its UI language.
    val notEnglish = session.settings.locale != "en"
    val viewer = session.reviewData?.viewer
    val confirmLabel = if (own != null) t(if (notEnglish) "share.updateAnyway" else "share.update") else t(if (notEnglish) "share.shareAnyway" else "share.share")

    fun confirm() {
        val key = session.key ?: return
        ui.sharing = true
        ui.shareError = null
        session.scope.launch {
            runCatching { session.repo.share(key) }
                .onSuccess { url -> ui.sharedUrl = url; done = url; session.reloadSaved() }
                .onFailure { ui.shareError = it.message ?: it.toString() }
            ui.sharing = false
        }
    }

    AiSheet(title = t(if (own != null) "share.titleUpdate" else "share.titleShare"), onDismiss = onDismiss) {
        val link = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
        val sentence = messages.t(if (own != null) "share.willUpdate" else "share.willPost", "comment" to "\u0001", "user" to "\u0002")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                buildAnnotatedString {
                    sentence.forEach { ch ->
                        when (ch) {
                            '\u0001' -> if (own != null) withLink(LinkAnnotation.Url(own, link)) { append(messages.t("share.commentLink")) } else append(messages.t("share.commentLink"))
                            '\u0002' -> if (viewer != null) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(viewer) } else append(messages.t("common.you"))
                            else -> append(ch)
                        }
                    }
                },
                style = PullsType.sm, color = c.text,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t("share.contains"), style = PullsType.sm, color = c.fade)
            Bullet {
                val parts = messages.t("share.containsLink", "site" to "\u0001").split('\u0001')
                Text(
                    buildAnnotatedString {
                        append(parts.first())
                        withStyle(SpanStyle(color = c.primary, fontWeight = FontWeight.Bold)) { append("pulls.review") }
                        append(parts.getOrElse(1) { "" })
                    },
                    style = PullsType.sm, color = c.text,
                )
            }
            Bullet { Text(t("share.containsJson"), style = PullsType.sm, color = c.text) }
            Text(t("share.excludes"), style = PullsType.sm, color = c.fade)
        }
        Text(t("share.anyoneCanLoad"), style = PullsType.sm, color = c.fade)
        if (notEnglish) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.amber.copy(alpha = 0.10f)).border(1.dp, c.amber.copy(alpha = 0.20f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhIcon("globe-duotone", Modifier.padding(top = 2.dp), tint = c.amber, size = 16.dp)
                Text(t("share.englishRecommended"), style = PullsType.sm, color = c.amber)
            }
        }
        ui.shareError?.let { Text(it, style = PullsType.sm, color = c.error) }
        val url = done
        if (url != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhIcon("check-circle", tint = c.success, size = 16.dp)
                Text(
                    buildAnnotatedString { withLink(LinkAnnotation.Url(url, link)) { append(url) } },
                    Modifier.weight(1f), style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.text,
                )
                IconButton("arrow-square-out", "Open on GitHub", { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, compact = true)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            ActionButton(onClick = onDismiss, variant = ButtonVariant.Text, enabled = !ui.sharing) { Text(t(if (url != null) "common.close" else "common.cancel")) }
            if (url == null) ActionButton(onClick = ::confirm, variant = ButtonVariant.Primary, icon = "share-network-duotone", loading = ui.sharing) { Text(confirmLabel) }
        }
    }
}

@Composable
private fun Bullet(content: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = PullsType.sm, color = c.text)
        content()
    }
}

/** A shared analysis on the PR another person (or you, elsewhere) posted (`SharedAnalysisCandidate`). */
internal data class SharedCandidate(val login: String, val own: Boolean, val stale: Boolean, val locale: String?)

/**
 * Port of `SharedAnalysisBanner.vue`: after the diff loads, the server lists the analyses shared
 * as comments on the PR (`shared-list`, the web `shared.discover`); they are offered as chips
 * that install one as this diff's AI result (`shared-load`). Dismiss hides it for the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SharedAnalysisBannerImpl(session: ReviewSession, modifier: Modifier) {
    val c = LocalPullsColors.current
    val ui = aiUi(session)
    val key = session.key ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var candidates by androidx.compose.runtime.remember(key) { androidx.compose.runtime.mutableStateOf<List<SharedCandidate>>(emptyList()) }
    var notice by androidx.compose.runtime.remember(key) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(key) {
        runCatching { session.repo.call("shared-list") { put("key", key) } }.onSuccess { result ->
            val o = result as? kotlinx.serialization.json.JsonObject ?: return@onSuccess
            if (o["loaded"] != null) { session.reloadSaved(); session.analyzeMode = "ai" }
            candidates = (o["candidates"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                val item = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun str(k: String) = (item[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                SharedCandidate(str("login").orEmpty(), str("own") == "true", str("stale") == "true", str("locale"))
            }
        }
    }
    if (ui.bannerDismissed || (candidates.isEmpty() && notice == null)) return
    val replacesLocal = session.hasAiResult && session.saved?.analysis?.sharedBy == null
    val locale = session.settings.locale
    FlowRow(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.raised).border(1.dp, c.border, RoundedCornerShape(8.dp)).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon("sparkle-duotone", tint = c.fade, size = 16.dp)
        if (candidates.isNotEmpty()) {
            Text(t(if (replacesLocal) "share.loadReplaces" else "share.available"), style = PullsType.sm, color = c.text)
            candidates.forEach { candidate ->
                com.octaviantocan.pulls.ui.design.ActionButton({
                    scope.launch {
                        runCatching { session.repo.call("shared-load") { put("key", key); put("login", candidate.login) } }
                            .onSuccess { candidates = emptyList(); notice = null; session.reloadSaved(); session.analyzeMode = "ai" }
                            .onFailure { notice = it.message }
                    }
                }, Modifier.clip(androidx.compose.foundation.shape.CircleShape), small = true) {
                    GithubAvatar(candidate.login, size = 16.dp)
                    Text(if (candidate.own) t("common.you") else candidate.login)
                    if (candidate.stale) Text(t("share.outdated"), style = PullsType.xs, color = c.fade)
                    else candidate.locale?.takeIf { it != locale }?.let { other ->
                        val name = com.octaviantocan.pulls.ui.design.Messages.LOCALES.firstOrNull { it.first == other }?.second ?: other
                        Text("($name)", style = PullsType.xs, color = c.fade)
                    }
                }
            }
        } else notice?.let { Text(it, style = PullsType.sm, color = c.fade) }
        Spacer(Modifier.weight(1f))
        IconButton("x", t("common.dismiss"), { ui.bannerDismissed = true }, compact = true)
    }
}
