package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.FieldText
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.FootPill
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.SendButton

/** The word the short Ask bar reads (Q5). */
const val ASK_SHORT = "Ask"

/**
 * `.tourask`: an Ask bar at the foot of a page, in the app's one foot pill, so going from the Inbox into a pull request
 * changes only the words in the pill (Q2). Until it is tapped it is short and reads "Ask", with no button ("let's not
 * have this send button and let's just have like a shorter bar that says Ask. And when you tap on it ... it kind of
 * expands ... and then obviously compacts back whenever you get out of that": recording 17 Oct 14:45, 02:26 to 03:19;
 * Q5). A tap opens it to the full width with the cursor in it, and it goes short again once it is left empty.
 *
 * Open and empty, the button is the Ask sheet's own earlier-chats button ([onOpenHistory]); once something is typed it
 * is Send, and sending hands the question to [onSend] ("the history button while empty, send once typed", recording
 * of 16 Oct 12:10, 13:25 to 14:15, N28). With no history to offer, an empty bar shows Send, grey and inert (rule 84).
 * Nothing sits beside it: the Overview's bar is this one alone, the same as the Tour's (recording of 17 Oct 13:26,
 * 00:25 to 00:48, P2). [key] names the bar: pages that share a key (the opening page and the Overview) are one bar.
 *
 * It is one component for every page that asks, so the Tour and the Overview never drift apart.
 */
@Composable
fun AskBar(
    key: String,
    text: String,
    onText: (String) -> Unit,
    placeholder: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit = {},
    onOpenHistory: (() -> Unit)? = null,
    veil: Modifier = Modifier,
) {
    val c = LocalPullsColors.current
    val focus = LocalFocusManager.current
    var open by rememberSaveable { mutableStateOf(false) }
    // Only a field that had the cursor and lost it closes the bar, not the first focus report of a new field.
    var held by remember { mutableStateOf(false) }
    fun send() {
        val question = text.trim()
        if (question.isEmpty()) return
        onText("")
        open = false
        focus.clearFocus()
        onSend(question)
    }
    FootPill(
        key = key,
        modifier = modifier,
        button = true,
        compact = if (open || text.isNotEmpty()) null else ASK_SHORT,
        onExpand = { open = true },
        veil = veil,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            FieldText(
                value = text,
                onChange = onText,
                placeholder = placeholder,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged {
                    onFocus(it.isFocused)
                    if (it.isFocused) held = true
                    else if (held) {
                        held = false
                        // Left with nothing in it, the bar goes short again (Q5); a question being written keeps it open.
                        if (text.isEmpty()) open = false
                    }
                },
            )
        }
        when {
            text.isNotBlank() -> SendButton({ send() }, "Send")
            // The Ask sheet's own button, the same glyph and size, so the bar and the sheet read as one (N28).
            onOpenHistory != null -> FloatIcon(Lu.history, "Earlier chats", onOpenHistory, size = 40.dp, iconSize = 20.dp, flat = true, tint = c.muted)
            // Grey and inert until there is a question to send (rule 84).
            else -> SendButton({ send() }, "Send", enabled = false)
        }
    }
}
