package com.octaviantocan.pulls.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/*
 * The one text field of the app. Every place that takes typing (Home's search pill, the Ask bars, the searches on
 * sheets, Commands, the fields inside a sheet's card) is built from [FieldText], and the framed ones from [FieldFrame], so
 * they share one height, one corner for where they sit, one inner padding, one text and placeholder style and the same
 * soft edge where words scroll out of sight ("go through the entire project and standardize this stuff": recording
 * 16 Oct 12:10, 03:23 to 04:03 and 15:10 to 15:36, N6; 05:47 to 06:00, N9). The bans check keeps `BasicTextField` in
 * this file.
 */

/** The height of every framed field: a pill at the bottom of a page and a search on a sheet alike. */
val FieldHeight = 52.dp

/** Where a framed field's words (or its magnifier) start, and where they end when nothing sits at its end. */
val FieldInset = 16.dp

/** The room at a field's end when a 40dp round button (✕, send, Open) sits inside it. */
val FieldButtonInset = 6.dp

/** Between the magnifier, the words and the buttons inside a field. */
val FieldGap = 10.dp

/** The words typed into a field, and its placeholder in the muted ink. */
val FieldStyle: TextStyle get() = PullsType.row

/** How far words fade at an edge of a field they have scrolled past. */
private val FIELD_FADE = 20.dp

/**
 * Where a framed field sits, which gives it its corner and its shadow: at the bottom of a page it floats as Home's
 * search pill does (rule 132); on a sheet it has the corners and the shadow of the cards under it (K28), so it reads as
 * part of the list. Everything else about the two is the same.
 */
enum class FieldPlace { Page, Sheet }

/**
 * The white frame a field sits in: [FieldHeight] tall, the pill or the card's corner by [place], [FieldInset] in from
 * its start, and from its end too unless [button] says a round button sits there. With [search] the magnifier leads.
 */
@Composable
fun FieldFrame(
    place: FieldPlace,
    modifier: Modifier = Modifier,
    search: Boolean = false,
    button: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val c = LocalPullsColors.current
    val shape = if (place == FieldPlace.Page) CircleShape else RoundedCornerShape(PullsRadius.card)
    Row(
        modifier.fillMaxWidth().height(FieldHeight)
            .floatShadow(shape, if (place == FieldPlace.Page) PullsShadow.Search else PullsShadow.Note)
            .clip(shape).background(c.raised)
            .padding(start = FieldInset, end = if (button) FieldButtonInset else FieldInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FieldGap),
    ) {
        if (search) LuIcon(Lu.search, tint = c.muted, size = 20.dp)
        content()
    }
}

/** The ✕ inside a field that empties it. */
@Composable
fun FieldClear(onClick: () -> Unit, label: String = "Clear") {
    FloatIcon(Lu.close, label, onClick, flat = true, size = 40.dp, iconSize = 18.dp, tint = LocalPullsColors.current.muted)
}

/**
 * Where the words of a field are scrolled to, worked out the way the text field scrolls: it moves only as far as it must
 * to keep the cursor in view. [offset] is how far the start is out of sight, [past] how far the words run beyond the
 * window in all, both in px along the way the field scrolls.
 */
@Stable
private class FieldScroll {
    var offset by mutableFloatStateOf(0f)
    var past by mutableFloatStateOf(0f)

    fun follow(cursorStart: Float, cursorEnd: Float, content: Float, window: Float) {
        val most = (content - window).coerceAtLeast(0f)
        var at = offset
        if (cursorStart < at) at = cursorStart
        if (cursorEnd > at + window) at = cursorEnd - window
        offset = at.coerceIn(0f, most)
        past = most
    }
}

/** Verify branch only: reports every keystroke the field sees, to find the dropped letters on iPhone. */
object TypingTrace { var log: ((String) -> Unit)? = null }

/**
 * The one text field: [FieldStyle] words in the text ink, the placeholder in the same style in the muted ink, the teal
 * cursor. Words that run past the field's edge fade into [fade], its background, on the side where they are hidden, as a
 * list fades under a pinned edge (N9): sideways in a one-line field, up and down in one whose [maxLines] it scrolls
 * within. [placeholderContent] draws the empty field's words in place of [placeholder] where they need more than one
 * string, such as the diff's comment bar choosing the wording that fits. [modifier] is the field's own, so a
 * `focusRequester` or `onFocusChanged` on it reaches the field. [live] styles the words from the text and the cursor
 * without changing a character (the description's live Markdown, Q4), and [cursorAt] picks where the cursor starts once
 * the words are first laid out, in place of the end (the place a tap was over, Q3).
 */
@Composable
fun FieldText(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    secret: Boolean = false,
    style: TextStyle = FieldStyle,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    fade: Color = LocalPullsColors.current.raised,
    placeholderContent: (@Composable () -> Unit)? = null,
    live: ((text: String, cursor: Int) -> AnnotatedString)? = null,
    cursorAt: ((TextLayoutResult) -> Int?)? = null,
) {
    val c = LocalPullsColors.current
    // In the foot bar's pill, a tap anywhere on the pill focuses this field (Q6).
    val foot = LocalFootFocus.current
    // The field keeps its own cursor and composition; the text is the caller's, so a text set from outside (a clear,
    // a pasted link opened) puts the cursor at its end.
    var kept by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val field = if (kept.text == value) kept else TextFieldValue(value, TextRange(value.length))
    SideEffect { TypingTrace.log?.invoke("compose value=[$value] kept=[${kept.text}] sel=${kept.selection} comp=${kept.composition} reset=${field !== kept}") }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var placed by remember { mutableStateOf(false) }
    var window by remember { mutableStateOf(IntSize.Zero) }
    val scroll = remember { FieldScroll() }
    val sideways = singleLine || maxLines == 1
    // Read here, so a new layout, a resize or a moved cursor runs the follow below again.
    val shown = layout
    val room = window
    val cursor = field.selection.end
    SideEffect {
        if (shown == null || room == IntSize.Zero) return@SideEffect
        val caret = shown.getCursorRect(cursor.coerceIn(0, shown.layoutInput.text.length))
        if (sideways) {
            val content = maxOf(shown.size.width.toFloat(), if (shown.lineCount > 0) shown.getLineRight(0) else 0f)
            scroll.follow(caret.left, caret.right, content, room.width.toFloat())
        } else {
            val content = maxOf(shown.size.height.toFloat(), if (shown.lineCount > 0) shown.getLineBottom(shown.lineCount - 1) else 0f)
            scroll.follow(caret.top, caret.bottom, content, room.height.toFloat())
        }
    }
    val band = with(LocalDensity.current) { FIELD_FADE.toPx() }
    val start = { (scroll.offset / band).coerceIn(0f, 1f) }
    val end = { ((scroll.past - scroll.offset) / band).coerceIn(0f, 1f) }
    BasicTextField(
        value = field,
        onValueChange = { next ->
            TypingTrace.log?.invoke("change next=[${next.text}] sel=${next.selection} comp=${next.composition} value=[$value] kept=[${kept.text}]")
            kept = next
            if (next.text != value) onChange(next.text)
        },
        modifier = if (foot != null) modifier.focusRequester(foot) else modifier,
        enabled = enabled,
        textStyle = style.copy(color = c.text),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = when {
            secret -> PasswordVisualTransformation()
            // Styled from the words and where the cursor is, character for character (live Markdown, Q4).
            live != null -> VisualTransformation { words -> TransformedText(live(words.text, cursor), OffsetMapping.Identity) }
            else -> VisualTransformation.None
        },
        onTextLayout = { result ->
            layout = result
            // Where the cursor starts, once the words are laid out: the place a tap on the shown text was over (Q3).
            if (cursorAt != null && !placed) {
                placed = true
                cursorAt(result)?.let { at -> kept = TextFieldValue(value, TextRange(at.coerceIn(0, value.length))) }
            }
        },
        cursorBrush = SolidColor(c.accentTeal),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().onSizeChanged { window = it }.then(
                    if (sideways) Modifier.sideFade(start = FIELD_FADE, end = FIELD_FADE, color = fade, startStrength = start, endStrength = end)
                    else Modifier.edgeFade(top = FIELD_FADE, bottom = FIELD_FADE, color = fade, topStrength = start, bottomStrength = end),
                ),
                contentAlignment = if (sideways) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) {
                    if (placeholderContent != null) placeholderContent()
                    else Text(placeholder, style = style, color = c.muted, maxLines = if (sideways) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                }
                inner()
            }
        },
    )
}
