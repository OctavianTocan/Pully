package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.edgeFade
import com.octaviantocan.pulls.ui.design.topEdgeStrength

/** The list key of a heading in the Inbox and Tours lists: a repository's, or a kind's (`kind:fix`). */
internal fun groupKey(name: String): String = "group:$name"

/** The repository a heading's list key names; null for any other key. */
internal fun groupOfKey(key: Any?): String? = (key as? String)?.takeIf { it.startsWith("group:") }?.removePrefix("group:")

/** The heading pinned over a grouped list: its group, and how far up (px, zero or less) it is drawn. */
internal data class Pinned(val group: String, val push: Int = 0)

/**
 * Which heading to pin, from the items on screen in order: each one's key and its offset from the top of the list in px.
 * None while the first item is a heading whose top is in view, or belongs to no group (the update banner). [groupOf]
 * names the group of a row's key. Every heading sticks whole at the top, a folded one as well as an open one, and the
 * next heading slides up under it and takes its place when it gets there, so nothing is ever pushed half under the chips
 * ("collapsed project categories not becoming sticky with the top bar": recording 15 Oct 15:37, 01:56 to 02:15; L2).
 * [headingHeight] is kept for callers that measure the pinned heading.
 */
@Suppress("UNUSED_PARAMETER")
internal fun pinnedGroup(visible: List<Pair<Any, Int>>, headingHeight: Int, groupOf: (Any) -> String?): Pinned? {
    val (firstKey, firstOffset) = visible.firstOrNull() ?: return null
    val heading = groupOfKey(firstKey)
    if (heading != null && firstOffset >= 0) return null
    val group = heading ?: groupOf(firstKey) ?: return null
    return Pinned(group)
}

/**
 * How far to scroll (px, either way) when the list comes to rest with a heading half under the pinned one, from the
 * items on screen in order as key, offset and size: it settles fully under it, where it is pinned in its turn, or just
 * below it, never half a pill (K19, L2). Null when nothing is pinned ([pinnedHeight] 0) or no heading is cut by it.
 */
internal fun cutUnderPinned(visible: List<Triple<Any, Int, Int>>, pinnedHeight: Int): Int? {
    if (pinnedHeight <= 0) return null
    val (firstKey, firstOffset) = visible.firstOrNull() ?: return null
    if (groupOfKey(firstKey) != null && firstOffset >= 0) return null
    val (_, offset, size) = visible.drop(1).firstOrNull { groupOfKey(it.first) != null && it.second in 1 until pinnedHeight } ?: return null
    val under = pinnedHeight - offset
    return if (under * 2 > size) offset else offset - pinnedHeight
}

/**
 * A list grouped by repository whose heading stays pinned at the top while its rows scroll under it (Tavi, 14 Oct: "I
 * need the project row to stay sticky as you scroll down"). The rows
 * fade out just below the pinned heading, as under any pinned edge (rule 74). [list] is the LazyColumn, given the
 * modifier that fades it; headings in it are keyed with [groupKey]. A folded heading sticks as an open one does, and the
 * next heading slides under the pinned one instead of pushing it over the chips (H13, L2).
 */
@Composable
internal fun PinnedHeadings(
    state: LazyListState,
    groupOf: (Any) -> String?,
    heading: @Composable (group: String, modifier: Modifier) -> Unit,
    list: @Composable (fade: Modifier) -> Unit,
) {
    var height by remember { mutableIntStateOf(0) }
    val groups by rememberUpdatedState(groupOf)
    val pinned by remember(state) {
        derivedStateOf { pinnedGroup(state.layoutInfo.visibleItemsInfo.map { it.key to it.offset }, height, groups) }
    }
    val band = with(LocalDensity.current) { PullsMotion.EdgeTop.toPx() }
    // At rest no heading is left half under the pinned one: it settles under it or just below it (K19, L2).
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.filter { !it }.collect {
            val cut = cutUnderPinned(state.layoutInfo.visibleItemsInfo.map { item -> Triple(item.key, item.offset, item.size) }, if (pinned != null) height else 0) ?: return@collect
            state.animateScrollBy(cut.toFloat(), tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut))
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        list(
            Modifier.edgeFade(
                top = PullsMotion.EdgeTop,
                bottom = PullsMotion.EdgeBottom,
                topStrength = { state.topEdgeStrength(band) },
                topInset = { pinned?.let { (height + it.push).coerceAtLeast(0).toFloat() } ?: 0f },
            ),
        )
        pinned?.let { at ->
            heading(at.group, Modifier.onSizeChanged { height = it.height }.offset { IntOffset(0, at.push) })
        }
    }
}

/**
 * `.grp`: the one heading that folds a run of rows, wherever there is one: the Inbox and Tours repositories, the
 * Overview's file sections and the Files sheet's (J18). It is the repository name over its rows, and how many there are. A tap on the heading folds the rows away or opens
 * them again; a tap on the name itself opens the repository (Tavi, 14 Oct). It looks like the Files headings that fold
 * (rule 70). The one line under a heading is its first row's own (rule 75), so a folded heading and the pinned one,
 * which have no row of their own under them, draw it. While a search runs nothing folds (rule 101): [onFold] is null.
 * A kind's heading has nothing of its own to open: with [onOpen] null the name is part of the heading's target. On a
 * sheet, whose rows sit in cards, it has no line and no [background] of its own.
 */
@Composable
internal fun GroupHeading(
    label: String,
    count: Int,
    first: Boolean,
    folded: Boolean,
    onFold: (() -> Unit)?,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    line: Boolean = folded,
    background: Color = LocalPullsColors.current.base,
    extra: (@Composable () -> Unit)? = null,
) {
    val c = LocalPullsColors.current
    val turn by animateFloatAsState(if (folded) -90f else 0f, tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut), label = "fold")
    val inset = with(LocalDensity.current) { 16.dp.toPx() }
    Row(
        modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind { if (line) drawRect(c.borderMute, Offset(inset, size.height - 1.dp.toPx()), Size(size.width - 2 * inset, 1.dp.toPx())) }
            .then(if (onFold != null) Modifier.cueClickable(Cue.Toggle, role = Role.Button, onClickLabel = if (folded) "Show $label" else "Hide $label", onClick = onFold) else Modifier)
            .padding(start = 8.dp, end = 16.dp, top = if (first) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // The name and its count take the row up to the chevron, so every heading's chevron sits on one line at the
        // right edge whatever the name's length (recording 15 Oct 15:37, 02:16 to 02:37; L3).
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // The name keeps its own target, padded so it is easy to hit; its text still starts on the rows' 16dp line and
            // sits 18dp under the rows above and 6dp over its own, as the heading did before it folded.
            Text(
                label,
                Modifier.weight(1f, fill = false).clip(RoundedCornerShape(PullsRadius.md))
                    .then(if (onOpen != null) Modifier.cueClickable(role = Role.Button, onClickLabel = "Open $label", onClick = onOpen) else Modifier)
                    .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                style = PullsType.sm.copy(fontWeight = FontWeight.Medium),
                color = c.muted,
                // A long name ends in … on its one line; the count and the chevron never wrap or get cut (K24).
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("$count", style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.faint, maxLines = 1, softWrap = false)
        }
        if (extra != null) { extra(); Spacer(Modifier.width(8.dp)) }
        if (onFold != null) LuIcon(Lu.chevd, Modifier.rotate(turn), tint = c.faint, size = 18.dp)
    }
}
