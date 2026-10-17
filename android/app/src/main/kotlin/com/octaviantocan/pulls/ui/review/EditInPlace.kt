package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.design.markdownLive
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.FieldText
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.PullsRadius
import kotlinx.coroutines.launch

/**
 * Which text on the page is being edited, so the rest of the page can step back while it is (H35): `title` or
 * `description`, null when nothing is. A page that wants that provides one through [LocalEditFocus].
 */
@Stable
internal class EditFocus {
    var editing: String? by mutableStateOf(null)
}

internal val LocalEditFocus = staticCompositionLocalOf<EditFocus?> { null }

/**
 * Takes every touch here before what is under it, and leaves the field being edited when the finger lifts: while a field
 * is open the rest of the page only puts it away, it does not open, switch or swipe anything (J17).
 */
internal suspend fun PointerInputScope.leaveOnTap(leave: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        leave()
    }
}

/** How much of the page stays while something else on it is edited (H35). */
internal const val EDIT_DIM = 0.28f

/**
 * Text the viewer owns, edited where it is (rule 83): a tap turns it into a field, and leaving the field saves it. There
 * is no Edit button. The caret goes where the finger landed (H34), and while the field is open the rest of the page
 * fades (through [LocalEditFocus]) and a title is underlined, so it plainly reads as editing (H35). [display] draws it at rest, [value] is what the server holds, and [onSave] stores a changed one
 * (it throws to say it failed, and the field reverts with a toast). [multiline] is a description, whose Enter makes a new
 * line and which a tap on the empty page leaves; a title is one line and the keyboard's Done leaves it. While it saves,
 * [display] already shows the new text, so nothing jumps back.
 */
@Composable
internal fun EditableText(
    value: String,
    canEdit: Boolean,
    textStyle: TextStyle,
    saved: String,
    onSave: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
    multiline: Boolean = false,
    allowBlank: Boolean = false,
    placeholder: String = "",
    display: @Composable (String) -> Unit,
) {
    val c = LocalPullsColors.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // What the page shows: the server's text, until the person has saved a newer one.
    var shown by remember(value) { mutableStateOf(value) }
    var draft by remember { mutableStateOf(TextFieldValue(value)) }
    var editing by remember { mutableStateOf(false) }
    var gotFocus by remember { mutableStateOf(false) }
    val page = LocalEditFocus.current
    val which = if (multiline) "description" else "title"
    DisposableEffect(page, editing) {
        if (editing) page?.editing = which
        onDispose { if (page?.editing == which) page.editing = null }
    }
    // Where the finger went down on the text at rest, and how wide it is, so the caret can open there.
    var pressed by remember { mutableStateOf(Offset.Zero) }
    var restWidth by remember { mutableIntStateOf(0) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    fun open() {
        val caret = if (multiline) {
            // A description is drawn as Markdown at rest, so a point on it does not map to a letter of the source: the end.
            shown.length
        } else {
            val layout = measurer.measure(shown, textStyle, constraints = Constraints(maxWidth = restWidth.coerceAtLeast(1)), density = density)
            layout.getOffsetForPosition(pressed).coerceIn(0, shown.length)
        }
        draft = TextFieldValue(shown, TextRange(caret))
        editing = true
    }

    fun leave() {
        if (!editing) return
        editing = false
        gotFocus = false
        val next = if (multiline) draft.text else draft.text.trim()
        if (next == shown || (!allowBlank && next.isBlank())) return
        val before = shown
        shown = next
        scope.launch {
            runCatching { onSave(next) }
                .onSuccess { toaster.show(saved, cue = Cue.Success) }
                .onFailure {
                    shown = before
                    toaster.show("Couldn't save: ${it.message ?: it}", cue = Cue.Error)
                }
        }
    }

    if (editing) {
        // motion-ok: Back leaves the field with what it had before, the way Escape would; no page moves, so none follows the finger.
        BackHandler { editing = false; gotFocus = false; draft = TextFieldValue(shown) }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        BasicTextField( // ban-ok: the page's own text edited where it stands, its caret where the finger went down, which the kit's field does not place
            value = draft,
            onValueChange = { draft = if (multiline) it else it.copy(text = it.text.replace("\n", "")) },
            textStyle = textStyle.copy(color = c.text),
            cursorBrush = SolidColor(c.accentTeal),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = if (multiline) ImeAction.Default else ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { state ->
                if (state.isFocused) gotFocus = true else if (gotFocus) leave()
            },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().then(
                        if (multiline) Modifier.defaultMinSize(minHeight = 160.dp).clip(RoundedCornerShape(PullsRadius.card)).background(c.raised).padding(16.dp)
                        // A title being edited is underlined in the accent, the line a field has (H35).
                        else Modifier.drawBehind { drawRect(c.accentTeal, Offset(0f, size.height + 2.dp.toPx()), Size(size.width, 2.dp.toPx())) },
                    ),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (draft.text.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = textStyle, color = c.muted)
                    inner()
                }
            },
        )
    } else {
        Box(
            modifier.fillMaxWidth().onSizeChanged { restWidth = it.width }.then(
                // A title or a paragraph that opens a field does not change colour while held: the field is the answer.
                if (canEdit) {
                    Modifier
                        .pointerInput(Unit) { awaitEachGesture { pressed = awaitFirstDown(requireUnconsumed = false).position } }
                        .cueClickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClickLabel = "Edit") { open() }
                } else Modifier,
            ),
        ) { display(shown) }
    }
}

/**
 * Long text the viewer owns, edited on a sheet with a proper text field instead of in place: a tap on it at rest opens
 * the sheet with the caret at the place in the text the tap was over (Q3), its Markdown read as rendered while typed (Q4),
 * and the page does not scroll or move under it. Leaving the sheet, by Save, ✕,
 * Back or a swipe down, saves a changed text, as leaving an in-place field does (rule 83). A link or a picture in the
 * text at rest opens itself and never the sheet. Tavi chose this for the description; the title stays in place ("if I
 * tap to edit it, you just open a bottom sheet with it in a nice like text editor ... the title, you can leave for
 * editing here", recording 15 Oct 15:37, 08:29 to 08:56; L13).
 */
@Composable
internal fun EditableInSheet(
    value: String,
    canEdit: Boolean,
    title: String,
    saved: String,
    onSave: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    display: @Composable (String) -> Unit,
) {
    val c = LocalPullsColors.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var shown by remember(value) { mutableStateOf(value) }
    var draft by remember { mutableStateOf(value) }
    var open by remember { mutableStateOf(false) }
    // Where on the text at rest the tap was, as a share of its width and height, so the cursor starts there (Q3).
    var tapped by remember { mutableStateOf<Offset?>(null) }
    var restSize by remember { mutableStateOf(IntSize.Zero) }
    fun finish() {
        if (!open) return
        open = false
        val next = draft
        if (next == shown) return
        val before = shown
        shown = next
        scope.launch {
            runCatching { onSave(next) }
                .onSuccess { toaster.show(saved, cue = Cue.Success) }
                .onFailure {
                    shown = before
                    toaster.show("Couldn't save: ${it.message ?: it}", cue = Cue.Error)
                }
        }
    }
    Box(
        modifier.fillMaxWidth().onSizeChanged { restSize = it }.then(
            if (canEdit) {
                Modifier
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val at = awaitFirstDown(requireUnconsumed = false).position
                            tapped = if (restSize.width > 0 && restSize.height > 0) Offset(at.x / restSize.width, at.y / restSize.height) else null
                        }
                    }
                    .cueClickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClickLabel = "Edit $title") {
                        draft = shown
                        open = true
                    }
            } else Modifier,
        ),
    ) { display(shown) }
    if (open) {
        val focus = remember { FocusRequester() }
        PullsSheet(
            onClose = ::finish,
            title = title,
            tall = true,
            footer = { CtaButton("Save", ::finish, Modifier.fillMaxWidth()) },
        ) {
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            // The kit's one field (N6) in a white card as tall as a page of text; its words fade where they scroll.
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp).defaultMinSize(minHeight = 240.dp).clip(RoundedCornerShape(PullsRadius.card)).background(c.raised).padding(16.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                // The Markdown reads rendered as it is typed, its marks folded away off the cursor's line (Q4), and the
                // cursor starts at the same place in the text as the tap was on the page, not at its end ("it scrolls me
                // all the way down instead of stopping where I was gonna edit": recording 17 Oct 14:45, 01:51 to 01:59; Q3).
                val share = tapped
                FieldText(
                    draft, { draft = it }, placeholder, Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = false,
                    style = PullsType.read,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    live = { words, cursor -> markdownLive(words, cursor, c) },
                    cursorAt = share?.let { { layout -> cursorFor(layout, it) } },
                )
            }
        }
    }
}

/**
 * The place in [layout]'s words at the same share of its height as a tap was on the text at rest ([share], across and
 * down), at the start of that line's word, so the cursor lands by what was tapped rather than at the end (Q3).
 */
internal fun cursorFor(layout: TextLayoutResult, share: Offset): Int {
    val at = layout.getOffsetForPosition(Offset(share.x * layout.size.width, share.y * layout.size.height))
    val text = layout.layoutInput.text.text
    var start = at.coerceIn(0, text.length)
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    return start
}
