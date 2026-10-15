package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.octaviantocan.pulls.core.PastVersion
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Haptics
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.ProgressRing
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.update.AppVersion
import com.octaviantocan.pulls.update.PastStatus
import com.octaviantocan.pulls.update.PastVersions
import com.octaviantocan.pulls.update.WhatsNew

/**
 * Settings, Advanced, Past versions: a slider over every version of the app, oldest on the left and the one running
 * on the right, where it starts. Picking an older one and tapping Open runs that version as it was, a separate app
 * rebuilt from git, and Back comes home to this one (Tavi, 15 Oct: "a dev-only slider... by default the app just has
 * latest changes").
 */
@Composable
internal fun ColumnScope.PastVersionsPage() {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val currentName = remember { AppVersion.name(context) }
    val current = remember {
        PastVersion(
            versionName = currentName,
            versionCode = AppVersion.code(context),
            packageName = context.packageName,
            summary = WhatsNew.all(context).firstOrNull { it.version == currentName }?.summary.orEmpty(),
            sha256 = "",
        )
    }
    var past by remember { mutableStateOf(PastVersions.cached(context)) }
    var asked by remember { mutableStateOf(false) }
    var answered by remember { mutableStateOf(past.isNotEmpty()) }
    LaunchedEffect(Unit) {
        PastVersions.refresh(context)?.let { past = it; answered = true }
        asked = true
    }
    val stops = past + current
    var chosenName by rememberSaveable { mutableStateOf(currentName) }
    val index = stops.indexOfFirst { it.versionName == chosenName }.takeIf { it >= 0 } ?: stops.lastIndex
    val chosen = stops[index]
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumed++
        PastVersions.resume(context)
    }
    val status = PastVersions.status
    val installed = remember(chosen, resumed, status) { chosen !== current && PastVersions.installed(context, chosen) }
    LaunchedEffect(status) {
        val ready = (status as? PastStatus.Ready)?.version ?: return@LaunchedEffect
        PastVersions.open(context, ready)
        PastVersions.settle()
    }

    SheetCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chosen.versionName, Modifier.weight(1f), style = PullsType.xl, color = c.text)
                Text(if (chosen === current) "Running" else chosen.date.take(10), style = PullsType.sm, color = c.muted)
            }
            if (chosen.summary.isNotBlank()) Text(chosen.summary, style = PullsType.read, color = c.muted)
        }
    }
    if (stops.size > 1) {
        VersionSlider(stops, index, { chosenName = stops[it].versionName }, Modifier.padding(top = 20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(stops.first().versionName, Modifier.weight(1f), style = PullsType.sm, color = c.muted)
            Text(stops.last().versionName, style = PullsType.sm, color = c.muted)
        }
    }
    val mine = status.versionName() == chosen.versionName
    when {
        chosen === current -> Note(
            when {
                stops.size > 1 -> "This is the version you're running."
                !asked -> ""
                answered -> "The server has no past versions yet."
                else -> "The server did not answer."
            },
        )
        mine && PastVersions.busy -> Row(
            Modifier.fillMaxWidth().padding(top = 20.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            // As the update screen shows its download: the ring while the size is known, the orb otherwise (rule 133).
            if (status is PastStatus.Downloading && status.fraction >= 0f) ProgressRing(status.fraction, size = 30.dp) else Orb(size = 30.dp)
            Text(if (status is PastStatus.Downloading) "Downloading ${chosen.versionName}…" else "Installing ${chosen.versionName}…", style = PullsType.sm, color = c.text)
        }
        else -> {
            CtaButton(
                if (installed) "Open ${chosen.versionName}" else "Get ${chosen.versionName}",
                { if (installed) PastVersions.open(context, chosen) else PastVersions.get(context, chosen) },
                Modifier.fillMaxWidth().padding(top = 20.dp),
                enabled = !PastVersions.busy,
            )
            if (mine && status is PastStatus.Failed) Note(status.message, error = true)
            else if (installed) {
                Text(
                    "Remove from this phone",
                    Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally)
                        .cueClickable(role = Role.Button) { PastVersions.remove(context, chosen) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText,
                )
            } else if (chosen.size > 0) Note("${(chosen.size + 524_288) / 1_048_576} MB, installed beside this app.")
        }
    }
}

private fun PastStatus.versionName(): String? = when (this) {
    is PastStatus.NeedsPermission -> version.versionName
    is PastStatus.Downloading -> version.versionName
    is PastStatus.Confirming -> version.versionName
    is PastStatus.Installing -> version.versionName
    is PastStatus.Ready -> version.versionName
    is PastStatus.Failed -> version.versionName
    PastStatus.Idle -> null
}

@Composable
private fun ColumnScope.Note(text: String, error: Boolean = false) {
    if (text.isEmpty()) return
    val c = LocalPullsColors.current
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp).align(Alignment.CenterHorizontally), style = PullsType.sm, color = if (error) c.error else c.muted)
}

/**
 * The slider: one stop for each version on a track, the chosen one marked by the thumb. A tap or a drag lands on the
 * nearest stop, and each stop passed is felt as a tick.
 */
@Composable
private fun VersionSlider(stops: List<PastVersion>, index: Int, onIndex: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val last = stops.lastIndex
    val current by rememberUpdatedState(index)
    val pick by rememberUpdatedState(onIndex)
    val edge = 14.dp
    fun land(x: Float, width: Float, edgePx: Float) {
        val span = (width - 2 * edgePx).coerceAtLeast(1f)
        val to = (((x - edgePx) / span) * last).let { Math.round(it) }.coerceIn(0, last)
        if (to != current) {
            Haptics.cue(Cue.Select)
            pick(to)
        }
    }
    Canvas(
        modifier.fillMaxWidth().height(44.dp)
            .pointerInput(last) { detectTapGestures { land(it.x, size.width.toFloat(), edge.toPx()) } }
            .pointerInput(last) { detectHorizontalDragGestures { change, _ -> change.consume(); land(change.position.x, size.width.toFloat(), edge.toPx()) } }
            .semantics {
                stateDescription = stops[index].versionName
                progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..last.toFloat(), steps = (last - 1).coerceAtLeast(0))
                setProgress { value ->
                    val to = Math.round(value).coerceIn(0, last)
                    if (to != current) pick(to)
                    true
                }
            },
    ) {
        val edgePx = edge.toPx()
        val y = size.height / 2
        val span = size.width - 2 * edgePx
        val at = edgePx + span * (if (last == 0) 1f else index.toFloat() / last)
        drawLine(c.borderMute, Offset(edgePx, y), Offset(size.width - edgePx, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(c.accentTeal, Offset(edgePx, y), Offset(at, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        for (stop in 0..last) {
            val x = edgePx + span * (if (last == 0) 1f else stop.toFloat() / last)
            drawCircle(if (stop <= index) c.onCta else c.faint, radius = 1.5.dp.toPx(), center = Offset(x, y))
        }
        drawCircle(c.border, radius = 13.dp.toPx(), center = Offset(at, y))
        drawCircle(c.raised, radius = 12.dp.toPx(), center = Offset(at, y))
        drawCircle(c.accentTeal, radius = 5.dp.toPx(), center = Offset(at, y))
    }
}
