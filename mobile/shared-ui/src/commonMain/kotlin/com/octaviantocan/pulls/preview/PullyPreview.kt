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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.DarkPullsColors
import com.octaviantocan.pulls.ui.design.LightPullsColors
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.home.PullRowFrame
import com.octaviantocan.pulls.ui.home.PullState
import com.octaviantocan.pulls.ui.home.RowFacts
import com.octaviantocan.pulls.ui.home.RowNumber
import com.octaviantocan.pulls.ui.home.StateTile

/** Internal host fixture: the very same row frame, state tile, type and diff counts Android ships. */
@Composable
fun PullyPreview() {
    val colors = if (isSystemInDarkTheme()) DarkPullsColors else LightPullsColors
    CompositionLocalProvider(LocalPullsColors provides colors) {
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.base)) {
            val wide = maxWidth >= 768.dp
            if (PullsType.wide != wide) PullsType.wide = wide
            LazyColumn(Modifier.fillMaxSize().safeDrawingPadding()) {
                item {
                    Column(Modifier.padding(PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
                        Text("Pully internal preview", Modifier.semantics { heading() }, style = PullsType.xxl, color = colors.text)
                        Text("Fixture data", style = PullsType.sm, color = colors.muted)
                    }
                }
                items(fixtures, key = { it.number }) { fixture ->
                    PullRowFrame(AnnotatedString(fixture.title), tile = { StateTile(fixture.state, fixture.state.name) }) {
                        RowNumber(fixture.number)
                        Text(
                            fixture.author, Modifier.weight(1f), style = PullsType.sm, color = colors.muted,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        )
                        RowFacts({ LuIcon(Lu.okc, tint = colors.success, size = 18.dp) }, fixture.additions, fixture.deletions, "2h")
                    }
                }
            }
        }
    }
}

private data class PreviewPull(
    val number: Int,
    val title: String,
    val author: String,
    val state: PullState,
    val additions: Int,
    val deletions: Int,
)

private val fixtures = listOf(
    PreviewPull(76, "Share the existing Pully review interface", "octavian", PullState.Open, 180, 32),
    PreviewPull(75, "Keep the same type, colors and state tiles on both phones", "reviewer", PullState.Draft, 24, 8),
    PreviewPull(74, "Preserve the file review workflow", "octavian", PullState.Merged, 1200, 640),
    PreviewPull(73, "An intentionally long pull request title that keeps the same two line limit as the Android inbox", "reviewer", PullState.Closed, 17, 12),
)
