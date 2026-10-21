package com.octaviantocan.pulls.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.PullWork
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.ui.design.DarkPullsColors
import com.octaviantocan.pulls.ui.design.LightPullsColors
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.rememberMessages
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.PullRowContent

/** Actual production Inbox rows; deterministic public-safe data until the shared client is connected. */
@Composable
fun PullyPreview() {
    val colors = if (isSystemInDarkTheme()) DarkPullsColors else LightPullsColors
    val messages = rememberMessages()
    CompositionLocalProvider(LocalPullsColors provides colors, LocalMessages provides messages) {
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.base)) {
            val wide = maxWidth >= 768.dp
            if (PullsType.wide != wide) PullsType.wide = wide
            LazyColumn(Modifier.fillMaxSize().safeDrawingPadding()) {
                item {
                    Column(Modifier.padding(PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
                        Text(t("mobilePreview.title"), Modifier.semantics { heading() }, style = PullsType.xxl, color = colors.text)
                        Text(t("mobilePreview.fixtureData"), style = PullsType.sm, color = colors.muted)
                    }
                }
                items(fixtures, key = { it.number }) { fixture ->
                    PullRowContent(fixture, now = PREVIEW_NOW, dimmed = false, work = if (fixture.number == 74) PullWork.Analyzed else null)
                }
            }
        }
    }
}

private const val PREVIEW_NOW = 1_792_579_200_000L

private fun previewPull(number: Int, title: String, author: String, state: String, additions: Int, deletions: Int) = PullRow(
    owner = "pully", repo = "mobile-fixtures", number = number, title = title, author = author,
    updatedAt = "2026-10-21T10:00:00Z", updatedAtMs = PREVIEW_NOW - 7_200_000,
    isDraft = state == "draft", state = if (state == "closed" || state == "merged") "closed" else "open",
    merged = state == "merged", checks = "success", additions = additions, deletions = deletions,
)

private val fixtures = listOf(
    previewPull(76, "Share the existing Pully review interface", "octavian", "open", 180, 32),
    previewPull(75, "Keep the same type, colors and state tiles on both phones", "reviewer", "draft", 24, 8),
    previewPull(74, "Preserve the file review workflow", "octavian", "merged", 1200, 640),
    previewPull(73, "An intentionally long pull request title that keeps the same two line limit as the Android inbox", "reviewer", "closed", 17, 12),
)
