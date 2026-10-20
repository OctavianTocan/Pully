package com.octaviantocan.pulls.ui.ai

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.review.ModelCatalogs
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.settings.AiSettingsLogic
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/**
 * Starts the analysis (groups, file notes, summary). [choice] says what follows it: the server starts the tour and
 * the brief itself when the analysis succeeds, so they come even if the phone is off by then, and an AI review
 * starts next to it. The Analyze button runs the last choice; the sheet under its chevron picks another.
 */
internal fun ReviewSession.runAnalyze(context: Context, choice: AnalyzeChoice = settings.analyzeChoice) {
    if (taskFor("analyze")?.running == true) return
    val locale = settings.locale
    val config = ServerStore.load(context)
    // The efforts come from the model list the app has already fetched; without it the Settings effort stands.
    val efforts = AiSettingsLogic.current(ModelCatalogs.get(config.engine), config.model)?.efforts.orEmpty()
    val run = choice.run(efforts)
    startAiTask("analyze", extra = { put("locale", locale) }, effort = run.effort, then = run.then)
    if (run.review && taskFor("critique")?.running != true) runCritique(context, null, run.effort)
}

/** AI work in progress keeps its Orb (rule 18); the colour argument stays for the call sites that still pass one. */
@Composable
internal fun Spinner(@Suppress("UNUSED_PARAMETER") color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp = 16.dp) {
    Orb(size = size)
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
    val shape = RoundedCornerShape(16.dp)
    FlowRow(
        modifier.fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (candidates.isNotEmpty()) {
            Text(t(if (replacesLocal) "share.loadReplaces" else "share.available"), style = PullsType.sm, color = c.text)
            candidates.forEach { candidate ->
                val note = if (candidate.stale) t("share.outdated")
                else candidate.locale?.takeIf { it != locale }?.let { other -> "(${com.octaviantocan.pulls.ui.design.Messages.LOCALES.firstOrNull { it.first == other }?.second ?: other})" }
                PillButton(
                    listOfNotNull(if (candidate.own) t("common.you") else candidate.login, note).joinToString(" "),
                    {
                        scope.launch {
                            runCatching { session.repo.call("shared-load") { put("key", key); put("login", candidate.login) } }
                                .onSuccess { candidates = emptyList(); notice = null; session.reloadSaved(); session.analyzeMode = "ai" }
                                .onFailure { notice = it.message }
                        }
                    },
                    leading = { GithubAvatar(candidate.login, size = 20.dp) },
                )
            }
        } else notice?.let { Text(it, style = PullsType.sm, color = c.fade) }
        Spacer(Modifier.weight(1f))
        FloatIcon(Lu.close, t("common.dismiss"), { ui.bannerDismissed = true }, flat = true, size = 40.dp, tint = c.muted)
    }
}
