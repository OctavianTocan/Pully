package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SendButton
import com.octaviantocan.pulls.ui.design.floatShadow

/**
 * `.tourask`: an Ask bar floating at the bottom of a page, Home's 54dp search pill with the Ask sheet's button in place
 * of the clear button. Empty, the button is the Ask sheet's own earlier-chats button ([onOpenHistory]); once something is
 * typed it is Send, and sending hands the question to [onSend] ("the history button while empty, send once typed",
 * recording of 16 Oct 12:10, 13:25 to 14:15, N28). With no history to offer, an empty bar shows Send, grey and inert
 * (rule 84). With [onTapEmpty], a tap on the empty bar goes there instead of to the keyboard: the page opens the
 * conversation it already has. [trailing] sits beside the pill, outside it, such as the Overview's review button.
 *
 * It is one component for every page that asks, so the Tour and the Overview never drift apart.
 */
@Composable
internal fun AskBar(
    text: String,
    onText: (String) -> Unit,
    placeholder: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit = {},
    onOpenHistory: (() -> Unit)? = null,
    onTapEmpty: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalPullsColors.current
    val focus = LocalFocusManager.current
    fun send() {
        val question = text.trim()
        if (question.isEmpty()) return
        onText("")
        focus.clearFocus()
        onSend(question)
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.weight(1f).height(54.dp).floatShadow(CircleShape, PullsShadow.Search).clip(CircleShape).background(c.raised).padding(start = 20.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = text,
                    onValueChange = onText,
                    singleLine = true,
                    textStyle = PullsType.lg.copy(color = c.text),
                    cursorBrush = SolidColor(c.accentTeal),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) Text(placeholder, style = PullsType.lg, color = c.muted, maxLines = 1)
                            inner()
                        }
                    },
                )
                // The conversation is there already: the empty bar opens it, rather than a second place to type.
                if (onTapEmpty != null && text.isEmpty()) Box(
                    Modifier.matchParentSize().cueClickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        role = Role.Button, onClickLabel = placeholder, onClick = onTapEmpty,
                    ),
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
        trailing?.invoke(this)
    }
}
