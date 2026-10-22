package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.review.ReviewSession
import kotlinx.coroutines.launch

/**
 * Port of `SharedAnalysisBanner.vue`: after the diff loads, the server lists the analyses shared
 * as comments on the PR (`shared-list`, the web `shared.discover`); they are offered as chips
 * that install one as this diff's AI result (`shared-load`). Dismiss hides it for the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharedAnalysisBannerImpl(session: ReviewSession, modifier: Modifier) {
    val c = LocalPullsColors.current
    val ui = aiUi(session)
    val state = sharedAnalysisState(session)
    val owner = state.viewOwner() ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val candidates = state.candidates
    val notice = state.notice
    androidx.compose.runtime.LaunchedEffect(state, owner) { state.discover(owner) }
    if (ui.bannerDismissed || (candidates.isEmpty() && notice == null)) return
    val replacesLocal = session.hasAiResult && session.saved?.analysis?.sharedBy == null
    val locale = session.settings.locale
    val shape = RoundedCornerShape(PullsRadius.card)
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
                        scope.launch { state.load(owner, candidate) }
                    },
                    enabled = !state.loading,
                    leading = { GithubAvatar(candidate.login, size = 20.dp) },
                )
            }
        }
        notice?.let { Text(it, style = PullsType.sm, color = c.fade) }
        Spacer(Modifier.weight(1f))
        FloatIcon(Lu.close, t("common.dismiss"), { if (session.isCurrentView(owner)) ui.bannerDismissed = true }, flat = true, size = 40.dp, tint = c.muted)
    }
}
