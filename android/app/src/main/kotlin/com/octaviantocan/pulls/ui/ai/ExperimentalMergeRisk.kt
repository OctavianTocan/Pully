package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.MergeRisk
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetHeading
import com.octaviantocan.pulls.ui.design.hairlineTop
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.review.ReviewSession

/** The caller's opt-in is the only entry point; no ordinary review UI depends on these rows. */
@Composable
internal fun ExperimentalMergeRisk(session: ReviewSession, risk: MergeRisk) {
    val c = LocalPullsColors.current
    LaunchedEffect(risk) {
        if (session.settings.experiments.mergeRisk)
            Analytics.track("experiment.use", "experiment" to "merge-risk", "action" to "view")
    }
    SheetHeading(t("critique.mergeRisk.title"), extra = {
        Text(t("settings.experiments.title"), style = PullsType.sm, color = c.muted)
    })
    SheetCard {
        Text(
            t("critique.mergeRisk.levelLabel", "level" to t("critique.mergeRisk.level.${risk.level}")),
            Modifier.fillMaxWidth().padding(PullsSpace.x4),
            style = PullsType.xl, color = if (risk.level == "high") c.amber else c.text,
        )
        risk.categories.forEach { category ->
            Column(
                Modifier.fillMaxWidth().hairlineTop(c.borderMute).padding(PullsSpace.x4),
                verticalArrangement = Arrangement.spacedBy(PullsSpace.x2),
            ) {
                Text(
                    buildAnnotatedString {
                        append("${t("critique.mergeRisk.category.${category.key}")}: ")
                        withStyle(SpanStyle(fontWeight = PullsType.medium)) {
                            append(t("critique.mergeRisk.verdict.${category.verdict}"))
                        }
                    },
                    style = PullsType.row, color = c.text,
                )
                AiMarkdown(session, category.reasons.joinToString("\n\n"), Modifier.fillMaxWidth(), style = PullsType.read)
            }
        }
    }
}

@Composable
internal fun experimentalMergeRiskMarkdown(risk: MergeRisk): String = buildString {
    append("**${t("critique.mergeRisk.title")} (${t("settings.experiments.title")}): ${t("critique.mergeRisk.level.${risk.level}")}**")
    risk.categories.forEach { category ->
        append("\n\n**${t("critique.mergeRisk.category.${category.key}")}: ${t("critique.mergeRisk.verdict.${category.verdict}")}**\n")
        append(category.reasons.joinToString("\n") { "- $it" })
    }
}
