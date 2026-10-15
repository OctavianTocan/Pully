package com.octaviantocan.pulls.ui.update

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.ChangelogEntry
import com.octaviantocan.pulls.core.plainChangelogText
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.ProgressRing
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import com.octaviantocan.pulls.ui.design.softBlur
import com.octaviantocan.pulls.update.UpdateStatus
import com.octaviantocan.pulls.update.Updates

/** How much room the floating update card takes above the search pill at most, for the lists to scroll clear of it. */
val UpdateFloatRoom = 100.dp

/** True while the floating update card is on Home: a newer build is offered and the reader has not hidden it. */
val updateFloatShown: Boolean get() = Updates.available != null && (!Updates.hidden || Updates.busy)

/**
 * The update, floating just above Home's search pill: "Pulls 0.17.0 is ready", the new version's summary under
 * it, and Update. A tap on the words opens what's new; Update downloads and installs in the app, and the card
 * says how far it got (a ring while it downloads, the orb while the system installs). ✕ hides it until Pulls
 * next starts. It rises in like the toast, 16dp through a 2px blur in 250ms, and leaves in 150ms (rule 73).
 * [visible] false puts it away, such as while the search has the keyboard.
 */
@Composable
internal fun UpdateFloat(visible: Boolean, onWhatsNew: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val motion = rememberMotionEnabled()
    val manifest = Updates.available
    val status = Updates.status
    val show = visible && manifest != null && updateFloatShown
    val shown by animateFloatAsState(
        if (show) 1f else 0f,
        if (motion) tween(if (show) PullsMotion.FAST else PullsMotion.QUICK, easing = PullsMotion.SmoothOut) else tween(0),
        label = "update-float",
    )
    // Keeps the last offer on screen while it leaves.
    var last by remember { mutableStateOf(manifest) }
    if (manifest != null) last = manifest
    val offer = last ?: return
    if (shown < 0.01f) return
    val shape = RoundedCornerShape(PullsRadius.card)
    val summary = remember(offer) { plainChangelogText(Updates.changes(context).firstOrNull()?.summary.orEmpty()) }
    var summaryLines by remember(offer) { mutableIntStateOf(1) }
    Row(
        modifier
            .fillMaxWidth()
            .softBlur(PullsMotion.BlurSmall * (1f - shown))
            .graphicsLayer {
                alpha = shown
                translationY = (1f - shown) * 16.dp.toPx()
            }
            .floatShadow(shape, PullsShadow.Search)
            .clip(shape)
            .background(c.raised)
            .heightIn(min = 64.dp)
            .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier.weight(1f).cueClickable(role = Role.Button, onClickLabel = "See what's new", onClick = onWhatsNew),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Pulls ${offer.versionName} is ready", style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The summary may take two lines; the shorter words that replace it keep its lines, so the card keeps its height (rule 86).
            val idle = status == UpdateStatus.Idle
            Text(
                statusLine(status, summary),
                style = PullsType.sm, color = if (status is UpdateStatus.Failed) c.error else c.muted,
                maxLines = 2, minLines = if (idle) 1 else summaryLines, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (idle) summaryLines = it.lineCount },
            )
        }
        UpdateAction(status, Modifier) { Updates.start(context) }
        // While it works there is nothing to hide, but the slot stays so the ring sits where Update was (rule 69).
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (!Updates.busy) FloatIcon(Lu.close, "Hide the update", { Updates.hidden = true }, flat = true, size = 40.dp, iconSize = 18.dp, tint = c.muted)
        }
    }
}

/** The card's second line: the summary until something happens, then what is happening, in words short enough for one line. */
private fun statusLine(status: UpdateStatus, summary: String): String = when (status) {
    UpdateStatus.Idle -> summary.ifBlank { "Tap to see what's new." }
    UpdateStatus.NeedsPermission -> "Allow installs first."
    is UpdateStatus.Downloading -> "Downloading…"
    UpdateStatus.Confirming -> "Confirm the update."
    UpdateStatus.Installing -> "Installing…"
    is UpdateStatus.Failed -> status.message
}

/**
 * Update as a 40dp dark pill (the search pill's Open), the download ring while it downloads, the orb while it installs.
 * The slot is as wide as the pill, so the ring takes its place without the row moving.
 */
@Composable
private fun UpdateAction(status: UpdateStatus, modifier: Modifier, onUpdate: () -> Unit) {
    val c = LocalPullsColors.current
    Box(modifier.height(40.dp).widthIn(min = 92.dp), contentAlignment = Alignment.Center) {
        when {
            status is UpdateStatus.Downloading && status.fraction >= 0f -> ProgressRing(status.fraction, size = 30.dp)
            Updates.busy -> Orb(size = 30.dp)
            else -> Box(
                Modifier.height(40.dp).clip(CircleShape).background(c.cta)
                    .cueClickable(emphasis = Emphasis.Normal, role = Role.Button, onClickLabel = "Update Pulls", onClick = onUpdate)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (status is UpdateStatus.Failed) "Try again" else "Update", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.onCta, maxLines = 1)
            }
        }
    }
}

/**
 * What's new, as a sheet: each version's summary and what it changed, newest first. It opens by itself once
 * after an update, from the floating card before one (then Update sits at its foot), and from Settings.
 */
@Composable
internal fun WhatsNewSheet(entries: List<ChangelogEntry>, onClose: () -> Unit, offer: Boolean = false) {
    val context = LocalContext.current
    val manifest = Updates.available
    PullsSheet(
        onClose = onClose,
        title = "What's new",
        footer = if (offer && manifest != null) {
            { UpdateFooter(manifest.versionName) { Updates.start(context) } }
        } else null,
    ) {
        ChangelogEntries(entries)
    }
}

/** The sheet's Update: the dark main action, and once it runs, the card's own words for how far it got. */
@Composable
private fun ColumnScope.UpdateFooter(version: String, onUpdate: () -> Unit) {
    val c = LocalPullsColors.current
    val status = Updates.status
    // Back from the install settings without allowing it, Update is offered again with the reason above it.
    if (!Updates.busy) {
        if (status != UpdateStatus.Idle) {
            Text(
                statusLine(status, ""), Modifier.padding(bottom = 10.dp).align(Alignment.CenterHorizontally),
                style = PullsType.sm, color = if (status is UpdateStatus.Failed) c.error else c.muted,
            )
        }
        CtaButton(if (status is UpdateStatus.Failed) "Try again" else "Update to $version", onUpdate, Modifier.fillMaxWidth())
    } else {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            if (status is UpdateStatus.Downloading && status.fraction >= 0f) ProgressRing(status.fraction, size = 30.dp) else Orb(size = 30.dp)
            Text(statusLine(status, ""), style = PullsType.sm, color = c.text, maxLines = 2)
        }
    }
}

/**
 * Versions as the changelog writes them: the number in Mona Sans, the summary as the headline, and the bullets
 * at reading size. No card around them: a sheet's body is its list (rule 79).
 */
@Composable
internal fun ChangelogEntries(entries: List<ChangelogEntry>, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        if (entries.isEmpty()) Text("Nothing is written down for this version.", style = PullsType.read, color = c.muted)
        entries.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.version, style = PullsType.num, color = c.muted)
                if (entry.summary.isNotBlank()) Text(plainChangelogText(entry.summary), style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text)
                if (entry.items.isNotEmpty()) {
                    Markdown(entry.items.joinToString("\n") { "- $it" }, Modifier.padding(top = 4.dp), style = PullsType.read.copy(color = c.text))
                }
            }
        }
    }
}

/** What a list on Home adds to its bottom padding so its last row can scroll clear of the [UpdateFloat]. */
internal fun updateFloatPadding() = if (updateFloatShown) UpdateFloatRoom else 0.dp
