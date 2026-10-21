@file:OptIn(ExperimentalLayoutApi::class)

package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.sound.Cue
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.DETAILS_INFO
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.design.PullsColors
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull


fun Modifier.bottomBorder(color: Color) = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
}

fun Modifier.topBorder(color: Color) = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
}

/** `PrHtml`: GitHub's rendered `bodyHTML`, restyled like the app's Markdown (`chat-markdown`). */
@Composable
fun PrHtml(html: String, modifier: Modifier = Modifier) {
    val markdown = remember(html) { htmlToMarkdown(html) }
    Markdown(markdown, modifier, style = PullsType.sm)
}

/**
 * The header row of a timeline card. A tap folds the card up to this row and opens it again (F72), and the chevron at
 * the end says which way it will go.
 */
@Composable
private fun CardHeader(collapsed: Boolean, onToggle: () -> Unit, trailing: @Composable () -> Unit, content: @Composable RowScope.() -> Unit) {
    val c = LocalPullsColors.current
    // Who and what on the left, wrapping when long; when and the chevron always in the same place on the right.
    Row(
        Modifier.fillMaxWidth()
            .cueClickable(Cue.Toggle, role = Role.Button, onClickLabel = if (collapsed) "Show the comment" else "Hide the comment", onClick = onToggle)
            .defaultMinSize(minHeight = TOUCH).padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
        trailing()
        LuIcon(if (collapsed) Lu.chev else Lu.chevd, tint = c.faint, size = 18.dp)
    }
}

/** A folded comment's first line, dimmed: enough to know what it is without opening it. It is a preview, so a long one ends in an ellipsis. */
@Composable
private fun FoldedLine(text: String) {
    if (text.isEmpty()) return
    Text(text, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), style = PullsType.sm, color = LocalPullsColors.current.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * `PrConversation`: the timeline (last 30, "Show n earlier"), without the description: Summary already shows it, so it
 * is not repeated as the first comment ("the first comment is just the PR's description", recording of 16 Oct 12:10,
 * 15:44 to 16:01; N30). A long one is hard to get around, most of it bots (J19), so chips over it narrow it to what
 * people wrote or what bots wrote, each with how many.
 */
@Composable
fun Conversation(overview: PrOverview, spot: LinkSpot? = null, onSpot: (y: Int) -> Unit = {}) {
    val c = LocalPullsColors.current
    val github = LocalOverviewServices.current.native::openOnGithub
    var showAll by rememberSaveable(overview.id) { mutableStateOf(false) }
    var talk by rememberSaveable(overview.id, "talk") { mutableStateOf(Talk.All) }
    // Pushed commits live in the Commits tab (rule 91); the conversation is what people said and did.
    val everything = remember(overview.timeline) { overview.timeline.filter { it !is PrTimelineItem.Commits } }
    val counts = remember(everything) { Talk.entries.associateWith { which -> everything.count { inTalk(it, which) } } }
    val timeline = remember(everything, talk) { everything.filter { inTalk(it, talk) } }
    val earlier = if (showAll || talk != Talk.All) 0 else maxOf(0, timeline.size - 30)
    // A link to a comment or a review shows it whatever the chips and "Show earlier" hid, then the page scrolls to it.
    var column by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val cards = remember { HashMap<String, LayoutCoordinates>() }
    LaunchedEffect(spot) {
        val wanted = spot ?: return@LaunchedEffect
        if (everything.none { it.id == wanted.item }) return@LaunchedEffect
        if (timeline.none { it.id == wanted.item }) talk = Talk.All
        // Folded under "Show earlier": the whole conversation opens, as GitHub's page has it all.
        if (everything.indexOfFirst { it.id == wanted.item } < everything.size - 30) showAll = true
        var y: Int? = null
        repeat(120) {
            if (y != null) return@repeat
            val card = cards[wanted.item]
            val root = column
            if (card != null && root != null && card.isAttached && root.isAttached) y = root.localPositionOf(card, Offset.Zero).y.roundToInt()
            else kotlinx.coroutines.delay(16)
        }
        y?.let(onSpot)
    }
    Column(Modifier.onGloballyPositioned { column = it }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Only worth having when there is something to narrow: people and bots both spoke.
        if ((counts[Talk.People] ?: 0) > 0 && (counts[Talk.Bots] ?: 0) > 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Talk.entries.forEach { which ->
                    SoftChip(which.label, talk == which, { talk = which }, count = if (which == Talk.All) null else "${counts[which]}")
                }
            }
        }
        // With the description gone (N30), a pull request nobody has spoken on says so instead of showing an empty tab.
        if (everything.isEmpty() && overview.timelineHidden == 0) Text("Nobody has commented yet.", Modifier.padding(horizontal = 4.dp), style = PullsType.sm, color = c.muted)
        if (overview.timelineHidden > 0) LinkText(t("overview.moreOnGithub", "n" to overview.timelineHidden)) { github(overview.url) }
        if (earlier > 0) LinkText(t("overview.earlier", "n" to earlier)) { showAll = true }
        timeline.drop(earlier).forEach { item ->
            TimelineEntry(item, spot?.takeIf { it.item == item.id }, Modifier.onGloballyPositioned { cards[item.id] = it })
        }
    }
}

/** A timeline card in the kit's look: the white 16dp card with the canvas shadow, no border (rules 45 and 49). */
@Composable
private fun Modifier.timelineCard(c: PullsColors): Modifier {
    val shape = RoundedCornerShape(PullsRadius.card)
    return fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised).animateContentSize(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut))
}

/** A quiet text button on a 44dp row: the accent text of rule 96, at body size (rule 14). */
@Composable
fun LinkText(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    Box(modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(PullsRadius.sm)).cueClickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText)
    }
}

/** `PrTimelineEntry`: an event line, or a comment / review card. */
@Composable
fun TimelineEntry(item: PrTimelineItem, spot: LinkSpot? = null, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    when (item) {
        // An event is a sentence: who, what and when, with no icon in front (rule 48), at body size (rule 14).
        is PrTimelineItem.Event -> FlowRow(
            modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            item.actor?.let { GithubAvatar(it.login, avatarUrl = it.avatarUrl, size = 18.dp) }
            Text(item.actor?.login ?: "ghost", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
            Text(eventText(item, t("overview.event.${item.event}")), style = PullsType.sm, color = c.muted)
            Text(prTimeAgo(item.createdAt), style = PullsType.sm, color = c.faint)
            item.message?.let { Text(it, Modifier.fillMaxWidth().padding(start = 24.dp), style = PullsType.sm, color = c.muted) }
        }
        // Pushed commits are in the Commits tab, not the conversation (rule 91).
        is PrTimelineItem.Commits -> Unit
        is PrTimelineItem.Comment, is PrTimelineItem.Review -> CommentCard(item, spot, modifier)
    }
}

/**
 * A comment or a review card. The one a link pointed at ([spot]) opens, with its comments on the code when the link was to
 * one of them, and is washed in the accent for a moment, as the diff marks a line it lands on (LinkPlaces).
 */
@Composable
private fun CommentCard(item: PrTimelineItem, spot: LinkSpot?, modifier: Modifier) {
    val c = LocalPullsColors.current
    val github = LocalOverviewServices.current.native::openOnGithub
    var showInline by rememberSaveable(item.id + ":inline") { mutableStateOf(false) }
    val comment = item as? PrTimelineItem.Comment
    val review = item as? PrTimelineItem.Review
    val url = comment?.url ?: review?.url.orEmpty()
    val body = comment?.bodyHTML ?: review?.bodyHTML.orEmpty()
    val minimized = comment?.minimizedReason
    // A comment GitHub hid, or one a bot wrote, starts folded up; any other starts open (rule 91). Either folds and opens with a tap on its header.
    var open by rememberSaveable(item.id, "open") { mutableStateOf(minimized == null && !isBot(item.actor)) }
    val collapsed = !open
    val wash = remember { Animatable(0f) }
    LaunchedEffect(spot) {
        if (spot == null) return@LaunchedEffect
        open = true
        if (spot.inline) showInline = true
        wash.snapTo(1f)
        kotlinx.coroutines.delay(1_600)
        wash.animateTo(0f, tween(900, easing = PullsMotion.SmoothOut))
    }
    Column(modifier.timelineCard(c).drawBehind { if (wash.value > 0f) drawRect(c.accentTeal.copy(alpha = 0.12f * wash.value)) }) {
        CardHeader(
            collapsed = collapsed, onToggle = { open = !open },
            trailing = {
                Text(prTimeAgo(item.createdAt), Modifier.cueClickable(enabled = url.isNotBlank()) { github(url) }, style = PullsType.sm, color = c.muted, maxLines = 1)
            },
        ) {
            item.actor?.let { GithubAvatar(it.login, avatarUrl = it.avatarUrl, size = 18.dp) }
            // A comment can only be a comment, so it carries no verb; a review says what it decided, in the colour of
            // the decision instead of an icon before the name (rule 48).
            val verb = when (review?.state) {
                null -> null
                "approved" -> t("overview.timeline.approved") to StatusColors.green(c.dark)
                "changes_requested" -> t("overview.timeline.changesRequested") to StatusColors.red(c.dark)
                else -> t("overview.timeline.reviewed") to c.muted
            }
            val hidden = minimized?.let { t("overview.timeline.hidden", "reason" to it) }
            // The name, the decision and a hidden note read as one line. When they run long the decision moves under the
            // name; the name keeps to one line and gives way only at its end, and the age stays at the right edge
            // (rules 91 and 111).
            FlowRow(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.actor?.login ?: "ghost", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                verb?.let { (words, color) -> Text(words, style = PullsType.sm, color = color) }
                hidden?.let { Text(it, style = PullsType.sm, color = c.muted) }
            }
        }
        if (body.isNotBlank() && !collapsed) Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) { PrHtml(body) }
        if (collapsed && body.isNotBlank()) FoldedLine(remember(body) { commentPreview(body) })
        if (review != null && review.commentCount > 0 && !collapsed) {
            Column(Modifier.fillMaxWidth().topBorder(c.borderMute).padding(start = 14.dp, end = 12.dp, top = 2.dp, bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = TOUCH).cueClickable { showInline = !showInline },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(tc("overview.timeline.inlineComments", review.commentCount), Modifier.weight(1f), style = PullsType.sm, color = c.muted)
                    LuIcon(if (showInline) Lu.chevd else Lu.chev, tint = c.faint, size = 18.dp)
                }
                if (showInline) Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    review.comments.forEach { inline ->
                        Row {
                            Box(Modifier.width(2.dp).height(1.dp))
                            Column(
                                Modifier.fillMaxWidth().drawBehind { drawLine(c.border, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx()) }.padding(start = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        inline.path + (inline.line?.let { ":$it" } ?: ""),
                                        Modifier.cueClickable(enabled = inline.url.isNotBlank()) { github(inline.url) },
                                        style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted,
                                    )
                                    // A word, not a bordered tag (rule 49).
                                    if (inline.outdated) Text(t("overview.timeline.outdated"), style = PullsType.sm, color = c.amber)
                                }
                                PrHtml(inline.bodyHTML)
                            }
                        }
                    }
                    if (review.commentCount > review.comments.size) LinkText(t("overview.moreOnGithub", "n" to review.commentCount - review.comments.size)) { github(review.url) }
                }
            }
        }
    }
}

/**
 * The `<i18n-t>` slots of an event: `{label}` as a label-colored chip, `{subject}` medium
 * (mono for a merge commit), `{from}`/`{to}` mono (medium for a renamed title), all in
 * `color-base` against the faded sentence.
 */
@Composable
private fun eventText(item: PrTimelineItem.Event, template: String): AnnotatedString {
    val c = LocalPullsColors.current
    val label = item.labelColor?.let { labelColors(it, c.dark) }
    return remember(item, template, c.dark) {
        buildAnnotatedString {
            var at = 0
            for (m in Regex("""\{(\w+)\}""").findAll(template)) {
                append(template.substring(at, m.range.first))
                at = m.range.last + 1
                when (m.groupValues[1]) {
                    "label" -> if (item.labelName != null) withStyle(
                        SpanStyle(
                            color = label?.let { Color(it.text) } ?: c.text, background = label?.let { Color(it.background) } ?: c.code,
                            fontWeight = FontWeight.Medium,
                        ),
                    ) { append("\u2009${item.labelName}\u2009") }
                    "subject" -> withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Medium, fontFamily = if (item.event == "merged") PullsType.mono else null)) { append(item.subject.orEmpty()) }
                    "from" -> withStyle(slotStyle(item, c.text)) { append(item.from.orEmpty()) }
                    "to" -> withStyle(slotStyle(item, c.text)) { append(item.to.orEmpty()) }
                    else -> append(m.value)
                }
            }
            append(template.substring(at))
        }
    }
}

private fun slotStyle(item: PrTimelineItem.Event, text: Color) =
    if (item.event == "renamed") SpanStyle(color = text, fontWeight = FontWeight.Medium) else SpanStyle(color = text, fontFamily = PullsType.mono)
