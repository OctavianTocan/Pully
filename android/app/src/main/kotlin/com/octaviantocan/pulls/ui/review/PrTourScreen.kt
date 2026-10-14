package com.octaviantocan.pulls.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.Tour
import com.octaviantocan.pulls.data.TourSource
import com.octaviantocan.pulls.data.TourStop
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.ai.AiUsageLine
import com.octaviantocan.pulls.ui.ai.Glossary
import com.octaviantocan.pulls.ui.ai.Sources
import com.octaviantocan.pulls.ui.ai.TourBlockView
import com.octaviantocan.pulls.ui.ai.TourEmpty
import com.octaviantocan.pulls.ui.ai.TourRunning
import com.octaviantocan.pulls.ui.ai.startTour
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FloatButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.MenuReveal
import com.octaviantocan.pulls.ui.design.ProgressRing
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SendButton
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.edgeFade
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.topEdgeStrength
import kotlinx.coroutines.launch

/**
 * The prototype's tour: a stop picker in the header, a sticky strip with the stop you are on and a
 * ring, one white card per stop, and an Ask pill at the bottom. [onReached] reports the furthest stop
 * the reader has scrolled to, which the Tours tab shows as progress.
 */
@Composable
internal fun PrTourScreen(
    session: ReviewSession,
    head: PrHead,
    resumeAt: Int,
    onReached: (stop: Int, total: Int, title: String) -> Unit,
    onBack: () -> Unit,
    onOverview: () -> Unit,
    onChanges: () -> Unit,
    onMore: () -> Unit,
    onOpenSource: (TourSource) -> Unit,
    onAsk: (String?) -> Unit,
) {
    val c = LocalPullsColors.current
    val scope = rememberCoroutineScope()
    val tour = session.saved?.tour
    val running = session.taskFor("tour")?.running == true
    val stops = tour?.stops.orEmpty()
    val n = stops.size
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var menu by remember { mutableStateOf<String?>(null) }
    var stripBottom by remember { mutableFloatStateOf(0f) }
    BackHandler(enabled = menu != null) { menu = null }

    // The stop you are on: the last whose top has passed 64dp plus a fifth of the screen (at most 120dp), or the last at the end.
    val current by remember(n) {
        derivedStateOf {
            if (n == 0) 0 else {
                val info = listState.layoutInfo
                val threshold = with(density) { 64.dp.toPx() } + minOf(with(density) { 120.dp.toPx() }, info.viewportSize.height * 0.2f)
                val passed = info.visibleItemsInfo.filter { it.index in 1..n && it.offset <= threshold }
                when {
                    !listState.canScrollForward && listState.canScrollBackward -> n
                    passed.isNotEmpty() -> passed.last().index
                    else -> 1
                }
            }
        }
    }
    LaunchedEffect(current, n) {
        if (n > 0 && current in 1..n) onReached(current, n, stops[current - 1].title)
    }
    // Pick up where the reader left off.
    LaunchedEffect(tour?.generatedAt) {
        if (n > 1 && resumeAt in 2..n) listState.scrollToItem(resumeAt)
    }

    Box(Modifier.fillMaxSize().background(c.base)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = headerTopPadding()), verticalAlignment = Alignment.CenterVertically) {
                FloatIcon(Lu.back, "Back", onBack, iconSize = 22.dp)
                Spacer(Modifier.weight(1f))
                // The same capsule as the Overview, with the Tour lit. The Aa menu is on the diff only: it sets the code size, wrapping and review comments, none of which a tour has.
                SectionCapsule(selected = 2, live = running, onOverview = onOverview, onChanges = onChanges, onTour = {}, onMore = onMore)
            }
            if (n > 0 && current in 1..n) {
                // The stop you are on, whole; tapping it opens the list of stops.
                Row(
                    Modifier.padding(top = 12.dp).fillMaxWidth()
                        .onGloballyPositioned { stripBottom = it.positionInRoot().y + it.size.height }
                        .drawBehind { drawRect(c.borderMute, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
                        .cueClickable(role = Role.Button, onClickLabel = "Choose a stop") { menu = if (menu == "stops") null else "stops" }
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = c.muted)) { append("$current of $n · ") }
                            append(stops[current - 1].title)
                        },
                        Modifier.weight(1f), style = PullsType.sm, color = c.text,
                    )
                    ProgressRing(current, n)
                    LuIcon(Lu.chevd, Modifier.rotate(if (menu == "stops") 180f else 0f), tint = c.muted, size = 20.dp)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 120.dp
                LazyColumn(
                    Modifier.fillMaxSize().edgeFade(top = PullsMotion.EdgeTop, topStrength = { listState.topEdgeStrength(with(density) { PullsMotion.EdgeTop.toPx() }) }),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = bottom),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "intro") { Intro(session, head, tour, running) }
                    itemsIndexed(stops, key = { index, _ -> "stop:$index" }) { index, stop -> StopCard(session, stop, onOpenSource) }
                    if (tour != null && tour.glossary.isNotEmpty()) item(key = "glossary") { Card { Glossary(tour.glossary, Modifier) } }
                    if (tour != null) item(key = "end") {
                        Column(Modifier.fillMaxWidth().padding(start = 2.dp, top = 8.dp)) {
                            Text("That is the whole tour.", style = PullsType.read, color = c.text)
                            CtaButton("Back to the pull request", onOverview, Modifier.padding(top = 16.dp), leading = { LuIcon(Lu.back, tint = c.onCta, size = 22.dp) })
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp).background(Brush.verticalGradient(0f to Color.Transparent, 0.7f to c.base, 1f to c.base)))
        AskPill(Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), onAsk)

        if (menu == "stops") Box(Modifier.fillMaxSize().cueClickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menu = null })
        MenuReveal(menu == "stops", Modifier.offset { IntOffset(0, stripBottom.toInt()) }) {
            StopPicker(stops, current, onPick = { index ->
                menu = null
                scope.launch { listState.animateScrollToItem(index + 1) }
            })
        }
    }
}

/** `.sec`: a white 18dp card with a soft shadow. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised).padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 20.dp)) { content() }
}

/** The tour's own card: its title and overview, a note when it was written for an older commit, and the way to make a new one. */
@Composable
private fun Intro(session: ReviewSession, head: PrHead, tour: Tour?, running: Boolean) {
    val c = LocalPullsColors.current
    Card {
        when {
            tour == null && running -> TourRunning(session, Modifier)
            tour == null -> TourEmpty(session, session.taskFor("tour")?.takeIf { it.status == "failed" }?.error, Modifier)
            else -> {
                val outdated = tour.headSha != null && session.opened?.headSha?.let { it != tour.headSha } == true
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tour.title, style = PullsType.xxl, color = c.text)
                    if (outdated) Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.warning.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Written for an older commit.", Modifier.weight(1f), style = PullsType.sm, color = c.text)
                        if (!running) PillButton("Update", { startTour(session) })
                    }
                    Markdown(tour.overview, style = PullsType.read.copy(color = c.text))
                    if (running) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.octaviantocan.pulls.ui.design.BreathingDot()
                        Text("Writing a new tour…", style = PullsType.sm, color = c.muted)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            AiUsageLine(tour.usage?.copy(model = tour.usage.model ?: tour.model) ?: com.octaviantocan.pulls.data.Usage(model = tour.model))
                        }
                        if (!running) PillButton("Rewrite", { startTour(session) }, leading = { LuIcon(Lu.redo, size = 20.dp) })
                    }
                }
            }
        }
    }
}

/** One stop: its title, why it matters, its blocks, the takeaway and where to look. */
@Composable
private fun StopCard(session: ReviewSession, stop: TourStop, onOpenSource: (TourSource) -> Unit) {
    val c = LocalPullsColors.current
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stop.title, style = PullsType.xxl, color = c.text)
            if (stop.purpose.isNotBlank()) Text(stop.purpose, Modifier.padding(top = 0.dp), style = PullsType.read, color = c.text)
            if (stop.error != null) com.octaviantocan.pulls.ui.ai.ErrorBox("This stop could not be written", stop.error)
            stop.blocks.forEach { block -> TourBlockView(session, block) }
            if (stop.takeaway.isNotBlank()) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.accentWash).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stop.takeaway, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.accentWashInk)
            }
            if (stop.sources.isNotEmpty()) Sources(stop.sources, onOpenSource)
        }
    }
}

/** `.dd` for the tour: the stops, those already passed ticked and the current one shaded. */
@Composable
private fun StopPicker(stops: List<TourStop>, current: Int, onPick: (Int) -> Unit) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(16.dp)
    val screen = LocalConfiguration.current.screenHeightDp
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)
            .fillMaxWidth().heightIn(max = (screen * 0.6f).dp).floatShadow(shape, PullsShadow.Menu).clip(shape).background(c.raised)
            .verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
    ) {
        stops.forEachIndexed { index, stop ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (index + 1 == current) c.paper else Color.Transparent)
                    .cueClickable(role = Role.Button) { onPick(index) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { if (index + 1 < current) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp) }
                Text(stop.title, Modifier.weight(1f), style = PullsType.sm, color = c.text)
                Text("${index + 1}", style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted)
            }
        }
    }
}

/** `.tourask`: a 54dp pill; sending opens the Ask sheet on the question. */
@Composable
private fun AskPill(modifier: Modifier, onAsk: (String?) -> Unit) {
    val c = LocalPullsColors.current
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf("") }
    fun send() {
        val question = text.trim()
        text = ""
        focus.clearFocus()
        onAsk(question.ifEmpty { null })
    }
    Row(
        modifier.fillMaxWidth().height(54.dp).floatShadow(CircleShape, PullsShadow.Search).clip(CircleShape).background(c.raised).padding(start = 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = PullsType.base.copy(color = c.text),
            cursorBrush = SolidColor(c.accentTeal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("Ask about this tour…", style = PullsType.base, color = c.muted, maxLines = 1)
                    inner()
                }
            },
        )
        SendButton({ send() }, "Send")
    }
}
