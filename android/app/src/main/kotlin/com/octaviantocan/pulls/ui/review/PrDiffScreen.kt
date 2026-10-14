package com.octaviantocan.pulls.ui.review

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.FileLinks
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.FloatButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsFonts
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.ReviewState
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.home.Spinner
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.home.compactCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The three code sizes of the Aa menu, as multiples of the 12.5sp default (the prototype's 11, 12.5 and 14.5). */
private val SIZES = listOf("Smaller" to 0.88f, "Default" to 1f, "Larger" to 1.16f)

/**
 * The prototype's diff: a header with the file picker and the Aa menu, the AI's note about the file,
 * its counts and Reviewed box, the rows, and a bottom bar with Previous, the comment field (once a
 * line is selected) and Next. [path] is the file shown; [onPath] moves to another.
 */
@Composable
internal fun PrDiffScreen(session: ReviewSession, ui: DiffUi, path: String?, onPath: (String) -> Unit, onBack: () -> Unit) {
    val c = LocalPullsColors.current
    val files = fileSections(session, ui).flatMap { it.files }
    val index = files.indexOfFirst { it.path == path }.let { if (it < 0) 0 else it }
    val file = files.getOrNull(index)
    var menu by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = menu != null) { menu = null }
    val width = LocalConfiguration.current.screenWidthDp
    SideEffect { if (ui.widthDp != width) ui.widthDp = width }

    Box(Modifier.fillMaxSize().background(c.base)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FloatIcon(Lu.back, "Back", onBack, iconSize = 22.dp)
                FileTitle(file, expanded = menu == "files", modifier = Modifier.weight(1f)) { menu = if (menu == "files") null else "files" }
                FloatButton({ menu = if (menu == "aa") null else "aa" }, "Text size") {
                    Text("Aa", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
                }
            }
            if (file == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No files in this change.", style = PullsType.sm, color = c.muted) }
            } else {
                FileBody(session, ui, file, files, index, onPath, Modifier.weight(1f).fillMaxWidth())
            }
        }
        if (menu == "files") FilePicker(session, files, file, onPick = { onPath(it); menu = null }, onDismiss = { menu = null })
        if (menu == "aa") ViewMenu(session, ui, file, onDismiss = { menu = null })
    }
}

/** `.dtitle`: the folder faded, the file name in the mono face, and a caret that flips while the list is open. */
@Composable
private fun FileTitle(file: ReviewFile?, expanded: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    val dir = file?.path?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" }.orEmpty()
    val base = file?.path?.substringAfterLast('/').orEmpty()
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Choose a file", onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.faint, fontWeight = FontWeight.Normal)) { append(dir.takeLast(18).let { if (it.length < dir.length) "…$it" else it }) }
                append(base)
            },
            Modifier.weight(1f, fill = false),
            style = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        LuIcon(Lu.chevd, Modifier.rotate(if (expanded) 180f else 0f), tint = c.muted, size = 20.dp)
    }
}

/** The note card, the counts line and the rows of one file, with the bottom bar over them. */
@Composable
private fun FileBody(session: ReviewSession, ui: DiffUi, file: ReviewFile, files: List<ReviewFile>, index: Int, onPath: (String) -> Unit, modifier: Modifier) {
    val c = LocalPullsColors.current
    val toaster = LocalToaster.current
    val listState = rememberLazyListState()
    val state = session.reviewState(file)
    val reviewed = state == ReviewState.Reviewed
    val note = session.saved?.analysis?.files?.firstOrNull { it.path == file.path }?.summary?.takeIf { it.isNotBlank() }
    val threads = session.reviewData?.threads.orEmpty().count { it.path == file.path && !it.outdated }
    val selected = ui.drafts[file.path]
    val dark = c.dark

    // A new file starts at its top with nothing selected.
    LaunchedEffect(file.path) {
        ui.drafts.clear()
        ui.selectionStart.clear()
        ui.mark = null
        if (session.focus?.path != file.path) listState.scrollToItem(0)
    }
    // Syntax colors for the rows on screen come from the server, a file at a time.
    val texts = remember(ui.entries(file)) { ui.entries(file).mapNotNull { (it as? DiffEntry.Line)?.line?.text } }
    LaunchedEffect(file.path, texts, dark) { if (!file.isBinary) ui.highlight(file, texts, dark) }
    DiffFocus(session, ui, file, listState)

    Box(modifier) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (note != null) NoteCard(note)
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val mono = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("+${compactCount(file.additions)}", style = mono, color = StatusColors.green(dark), maxLines = 1)
                    Text("−${compactCount(file.deletions)}", style = mono, color = StatusColors.red(dark), maxLines = 1)
                }
                Text(
                    when (threads) { 0 -> "No threads"; 1 -> "1 thread"; else -> "$threads threads" },
                    style = PullsType.sm, color = c.muted, maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Checkbox, onClickLabel = "Reviewed") {
                        session.setReviewed(listOf(file.sha), !reviewed)
                        if (!reviewed) {
                            val done = files.count { it.sha in session.reviewed || it.path == file.path }
                            toaster.show(if (done == files.size) "All ${files.size} files reviewed" else "$done of ${files.size} files reviewed")
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    PullsCheck(reviewed, size = 20.dp)
                    Text("Reviewed", style = PullsType.sm, color = c.text)
                }
            }
        }
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp
        Box(
            Modifier.weight(1f).fillMaxWidth().background(c.raised)
                .drawBehind { drawRect(c.borderMute, Offset.Zero, Size(size.width, 1.dp.toPx())) },
        ) {
            LazyColumn(Modifier.fillMaxSize().pinchToZoomCode(session.settings), state = listState, contentPadding = PaddingValues(bottom = bottom)) {
                fileDiffItems(session, ui, file)
            }
        }
    }
    CommentBar(
        session, ui, file, index, files.size,
        onPrev = { files.getOrNull(index - 1)?.let { onPath(it.path) } },
        onNext = { files.getOrNull(index + 1)?.let { onPath(it.path) } },
        selected = selected,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    }
}

/** `.note`: the AI's one-line note about this file, from "Pulls". */
@Composable
private fun NoteCard(note: String) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(c.accentWash), contentAlignment = Alignment.Center) { LuIcon(Lu.bot, tint = c.activeText, size = 16.dp) }
            Text("Pulls", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
            Spacer(Modifier.weight(1f))
            Text("File note", style = PullsType.sm, color = c.muted)
        }
        Text(note, style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = c.text)
    }
}

/**
 * `.dctl`: Previous, the comment field and Next. The field appears once a line is selected and posts
 * the comment into the viewer's pending review; with no write access it only names the selection.
 */
@Composable
private fun CommentBar(session: ReviewSession, ui: DiffUi, file: ReviewFile, index: Int, count: Int, onPrev: () -> Unit, onNext: () -> Unit, selected: DraftTarget?, modifier: Modifier) {
    val c = LocalPullsColors.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    // Tapping another line keeps what was typed (the comment follows the selection); deselecting or posting clears it.
    var text by remember(file.path) { mutableStateOf("") }
    LaunchedEffect(selected == null) { if (selected == null) text = "" }
    var busy by remember { mutableStateOf(false) }
    // With no review pending a comment can be posted at once instead of starting one; the choice sticks for the screen.
    val hasPending = session.reviewData?.pendingReviewId != null
    var single by rememberSaveable { mutableStateOf(false) }
    val post = single && !hasPending
    val canComment = session.canWrite && session.pull != null && !file.isBinary
    val label = selected?.let { s ->
        val first = s.startLine?.takeIf { it < s.line } ?: s.line
        if (first == s.line) "line ${s.line}" else "lines $first–${s.line}"
    }

    fun send() {
        val draft = selected ?: return
        val pull = session.pull ?: return
        val head = session.opened?.headSha ?: return
        val body = text.trim()
        if (body.isEmpty() || busy) return
        busy = true
        scope.launch {
            runCatching {
                session.repo.comment(pull, draft.path, draft.side, draft.line, head, body, if (post) "single" else "review", draft.startLine?.takeIf { it < draft.line })
            }.onSuccess {
                text = ""
                ui.drafts.remove(file.path)
                focus.clearFocus()
                session.reloadReviews()
                toaster.show(if (post) "Comment posted" else "Comment added to your pending review")
            }.onFailure { toaster.show(it.message ?: "Couldn't add the comment") }
            busy = false
        }
    }

    Row(
        modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FloatIcon(Lu.prev, "Previous file", onPrev, size = 48.dp, enabled = index > 0, iconSize = 22.dp)
        Box(Modifier.weight(1f)) {
            androidx.compose.animation.AnimatedVisibility(selected != null, enter = fadeIn() + scaleIn(initialScale = 0.96f), exit = fadeOut() + scaleOut(targetScale = 0.96f)) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).floatShadow(CircleShape, PullsShadow.Bar).clip(CircleShape).background(c.raised).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (canComment) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            enabled = !busy,
                            textStyle = PullsType.sm.copy(color = c.text),
                            cursorBrush = SolidColor(c.accentTeal),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (text.isEmpty()) Text("Comment on ${label ?: "a line"}…", style = PullsType.sm, color = c.muted, maxLines = 1)
                                    inner()
                                }
                            },
                        )
                        if (busy) Spinner(16.dp, c.muted)
                        if (!hasPending) {
                            Box(
                                Modifier.defaultMinSize(minHeight = 40.dp).clip(CircleShape)
                                    .clickable(role = Role.Switch, onClickLabel = if (single) "Add comments to a review instead" else "Post comments at once instead") { single = !single }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(if (single) "Post now" else "In review", style = PullsType.xs.copy(fontWeight = FontWeight.Medium), color = if (single) c.activeText else c.muted, maxLines = 1)
                            }
                        }
                    } else {
                        Text(label?.replaceFirstChar { it.uppercase() } ?: "", Modifier.weight(1f), style = PullsType.sm, color = c.muted, maxLines = 1)
                    }
                    FloatIcon(Lu.close, "Deselect line", { ui.drafts.remove(file.path); ui.selectionStart.remove(file.path) }, flat = true, size = 40.dp, iconSize = 20.dp, tint = c.muted)
                }
            }
        }
        FloatIcon(Lu.next, "Next file", onNext, size = 48.dp, enabled = index < count - 1, iconSize = 22.dp, container = c.primary, tint = Color.White)
    }
}

/** `.dd`: the pull request's files, each with its counts; the one on screen is shaded and reviewed ones are ticked. */
@Composable
private fun FilePicker(session: ReviewSession, files: List<ReviewFile>, current: ReviewFile?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(16.dp)
    val screen = LocalConfiguration.current.screenHeightDp
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss))
    // Lazy, and opened on the file you are reading, so a long list neither costs a row per file nor starts at the top.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (files.indexOfFirst { it.path == current?.path } - 2).coerceAtLeast(0))
    LazyColumn(
        Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 56.dp)
            .fillMaxWidth().heightIn(max = (screen * 0.6f).dp).floatShadow(shape, PullsShadow.Menu).clip(shape).background(c.raised),
        state = state,
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        items(files, key = { it.path }) { file ->
            val dir = file.path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            Row(
                Modifier.fillMaxWidth().height(48.dp).background(if (file.path == current?.path) c.paper else Color.Transparent)
                    .clickable(role = Role.Button) { onPick(file.path) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    if (file.sha in session.reviewed) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = c.faint)) { append(dir) }
                        append(file.path.substringAfterLast('/'))
                    },
                    Modifier.weight(1f), style = PullsType.sm.copy(fontFamily = PullsFonts.display), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("+${compactCount(file.additions)} −${compactCount(file.deletions)}", style = PullsType.sm.copy(fontFamily = PullsFonts.display, fontFeatureSettings = "tnum"), color = c.muted, maxLines = 1)
            }
        }
    }
}

/** `.pop`: the code size (Smaller, Default, Larger), line wrapping, and, when a [file] is open, its own actions. */
@Composable
internal fun ViewMenu(session: ReviewSession, ui: DiffUi, file: ReviewFile?, onDismiss: () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val settings = session.settings
    val shape = RoundedCornerShape(20.dp)
    val opened = session.opened
    val repo = FileLinks.repoOf(session.target)
    val sha = if (file?.status == "removed") opened?.baseSha else opened?.headSha
    val blob = if (file != null && repo != null && opened?.refKind.orEmpty().startsWith("github") && !sha.isNullOrBlank()) FileLinks.blob(repo.first, repo.second, sha, file.path) else null
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss))
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp, end = 16.dp, start = 16.dp), contentAlignment = Alignment.TopEnd) {
        Column(Modifier.width(300.dp).floatShadow(shape, PullsShadow.Menu).clip(shape).background(c.raised).padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SIZES.forEach { (name, zoom) ->
                    val on = kotlin.math.abs(settings.codeZoom - zoom) < 0.04f
                    Box(
                        Modifier.height(36.dp).clip(CircleShape).background(if (on) c.chipOn else Color.Transparent)
                            .clickable(role = Role.Tab) { settings.updateCodeZoom(zoom) }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(name, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1) }
                }
            }
            MenuRow("Wrap long lines", trailing = { PullsSwitch(settings.codeWrap, { settings.updateCodeWrap(it) }, "Wrap long lines") }) { settings.updateCodeWrap(!settings.codeWrap) }
            if (session.reviewData != null) {
                MenuRow("Show review comments", trailing = { PullsSwitch(settings.showThreads, { settings.updateShowThreads(it) }, "Show review comments") }) {
                    settings.updateShowThreads(!settings.showThreads)
                }
            }
            if (file != null) MenuRow("Copy path") { copyText(context, file.path); toaster.show("Copied ${file.path.substringAfterLast('/')}"); onDismiss() }
            if (blob != null) MenuRow("Open on GitHub") { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(blob))) }; onDismiss() }
            if (file != null && ui.canLoadFull(file)) MenuRow("Load the whole file") { ui.loadFullFile(file); onDismiss() }
        }
    }
}

@Composable
private fun MenuRow(label: String, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = PullsType.sm, color = c.text, maxLines = 1)
        trailing?.invoke()
    }
}

/**
 * Brings a focus request into view: opens the file's gap when the line sits in unchanged text,
 * scrolls to the line with some context above it, and marks it for a moment. The request is
 * consumed once it has been shown, so coming back to the screen does not jump again.
 */
@Composable
private fun DiffFocus(session: ReviewSession, ui: DiffUi, file: ReviewFile, listState: androidx.compose.foundation.lazy.LazyListState) {
    val focus = session.focus
    LaunchedEffect(focus, file.path) {
        val target = focus ?: return@LaunchedEffect
        if (target.path != file.path) return@LaunchedEffect
        if (target.line <= 0) {
            listState.scrollToItem(0)
            if (session.focus === target) session.focus = null
            return@LaunchedEffect
        }
        ui.mark = LineMark(file.path, target.side, target.line, target.findingId)
        val anchor = "${file.path}|${target.side}|${target.line}"
        delay(48)
        if (ui.lineKey[anchor] == null && !file.isBinary) {
            // The line sits in an unmodified gap: load the full file and open every gap.
            ui.loadFullFile(file)
            repeat(250) { if (ui.fullLoading[file.path] != true) return@repeat; delay(20) }
            if (ui.fullFiles[file.path] != null) ui.expandedGaps[file.path] = null
        }
        var key: String? = null
        repeat(60) { if (key == null) { key = ui.lineKey[anchor]; if (key == null) delay(16) } }
        val at = key?.let { ui.indexOf[it] }
        if (at != null) listState.animateScrollToItem(at, scrollOffset = -240)
        if (session.focus === target) session.focus = null
    }
    // Consuming the request changes the key above and cancels that effect, so the mark fades on its own timer.
    val mark = ui.mark
    LaunchedEffect(mark) {
        if (mark == null) return@LaunchedEffect
        delay(2500)
        if (ui.mark == mark) ui.mark = null
    }
}
