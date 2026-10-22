package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.octaviantocan.pulls.data.TourSource
import com.octaviantocan.pulls.ui.design.CodePlace
import com.octaviantocan.pulls.ui.design.LocalCodeBlock
import com.octaviantocan.pulls.ui.design.LocalMentions
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.MarkdownPreview
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.openSource

/** What the AI wrote, with its mentions as links, its code as the diff's code view and its sketches drawn (rules 95 and 96). */
@Composable
fun ProvideAiText(session: ReviewSession, open: (CodePlace) -> Unit, content: @Composable () -> Unit) {
    val latest by rememberUpdatedState(open)
    val mentions = remember(session) { mentionsFor(session) { place -> latest(place) } }
    val code = remember(session) { @Composable { body: String, lang: String -> AiFence(session, body, lang) } }
    CompositionLocalProvider(LocalMentions provides mentions, LocalCodeBlock provides code, content = content)
}

/**
 * Markdown the AI wrote, on a page that also shows people's words (the Diff's notes, findings and answers, the
 * Overview's file notes, the findings sheet): its mentions are links that open their place (rule 96) and its code is
 * the diff's code view (rule 95), while a reviewer's comment beside it stays as they wrote it. Inside [ProvideAiText]
 * it is plain [Markdown]. A [maxLines] makes it a preview of that many lines.
 */
@Composable
fun AiMarkdown(session: ReviewSession, source: String, modifier: Modifier = Modifier, style: TextStyle = PullsType.sm, maxLines: Int = Int.MAX_VALUE) {
    val text = @Composable { if (maxLines == Int.MAX_VALUE) Markdown(source, modifier, style) else MarkdownPreview(source, modifier, style, maxLines) }
    if (LocalMentions.current != null) text()
    else ProvideAiText(session, { place -> openSource(session, TourSource(place.path, place.line, "")) }, text)
}
