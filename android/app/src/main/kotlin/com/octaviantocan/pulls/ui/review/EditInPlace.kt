package com.octaviantocan.pulls.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
        BasicTextField(
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
