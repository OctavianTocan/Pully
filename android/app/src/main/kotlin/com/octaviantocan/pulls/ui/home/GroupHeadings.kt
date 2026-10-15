package com.octaviantocan.pulls.ui.home

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

/** The heading pinned over a grouped list: its group, and how far up (px, zero or less) the next heading pushes it. */
internal data class Pinned(val group: String, val push: Int)

/**
 * Which heading to pin, from the items on screen in order: each one's key and its offset from the top of the list in px.
 * None while the first item is a heading whose top is in view, or belongs to no group (the update banner). [groupOf]
 * names the group of a row's key. The next heading pushes the pinned one up as it arrives, as a section header does.
 */
internal fun pinnedGroup(visible: List<Pair<Any, Int>>, headingHeight: Int, groupOf: (Any) -> String?): Pinned? {
    val (firstKey, firstOffset) = visible.firstOrNull() ?: return null
    val heading = groupOfKey(firstKey)
    if (heading != null && firstOffset >= 0) return null
    val group = heading ?: groupOf(firstKey) ?: return null
    val next = visible.drop(1).firstOrNull { groupOfKey(it.first) != null }
    return Pinned(group, next?.let { (it.second - headingHeight).coerceAtMost(0) } ?: 0)
}

/**
 * A list grouped by repository whose heading stays pinned at the top while its rows scroll under it (Tavi, 14 Oct: "I
 * need the project row to stay sticky as you scroll down"), the next heading pushing it away as it arrives. The rows
 * fade out just below the pinned heading, as under any pinned edge (rule 74). [list] is the LazyColumn, given the
 * modifier that fades it; headings in it are keyed with [groupKey]. The pinned heading is cut at the top of the list, so
 * one pushed away by the next goes under the edge and never over the chips above the list (H13).
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
        // The name keeps its own target, padded so it is easy to hit; its text still starts on the rows' 16dp line and
        // sits 18dp under the rows above and 6dp over its own, as the heading did before it folded.
        Text(
            label,
            Modifier.clip(RoundedCornerShape(PullsRadius.md))
                .then(if (onOpen != null) Modifier.cueClickable(role = Role.Button, onClickLabel = "Open $label", onClick = onOpen) else Modifier)
                .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            style = PullsType.sm.copy(fontWeight = FontWeight.Medium),
            color = c.muted,
        )
        Text("$count", Modifier.weight(1f), style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.faint)
        if (onFold != null) LuIcon(Lu.chevd, Modifier.rotate(turn), tint = c.faint, size = 18.dp)
    }
}
