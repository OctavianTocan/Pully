package com.octaviantocan.pulls.sound

import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/*
 * Cuelume's `data-cuelume-tap | select | toggle` attributes, for Compose. A control that a person
 * taps is a `cueClickable` instead of a `clickable`. Its role says which cue it is, as octa-ui's table
 * does: a tab or an option is a `Select`, a switch or a checkbox a `Toggle`, anything else a `Tap`.
 * Say the cue only when the role is not enough, and say `Normal` emphasis for the one main action on a
 * screen: every other control is quiet (`Subtle`), so the main one stands out.
 *
 * The cue plays when the tap lands, not when the finger goes down, so a scroll that starts on a
 * button stays silent, exactly as Cuelume's bindings treat touch.
 */

/** The cue a control with this [role] plays when nothing says otherwise. */
fun cueFor(role: Role?): Cue = when (role) {
    Role.Checkbox, Role.Switch -> Cue.Toggle
    Role.Tab, Role.RadioButton -> Cue.Select
    else -> Cue.Tap
}

/**
 * `clickable` that plays a cue as the tap lands. [direction] says which way a tab or a switch moved
 * (a later tab or an on switch is forward), so the cue can lean the same way.
 */
fun Modifier.cueClickable(
    cue: Cue? = null,
    emphasis: Emphasis = Emphasis.Subtle,
    direction: Direction? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = clickable(enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = cued(cue ?: cueFor(role), emphasis, direction, onClick))

/** The same, for a control that brings its own interaction source and indication. */
fun Modifier.cueClickable(
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    cue: Cue? = null,
    emphasis: Emphasis = Emphasis.Subtle,
    direction: Direction? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = clickable(interactionSource, indication, enabled, onClickLabel, role, cued(cue ?: cueFor(role), emphasis, direction, onClick))

/** [onClick] that plays [cue] first, for a callback that is not a `clickable`: a gesture, or one passed down. */
fun cued(cue: Cue = Cue.Tap, emphasis: Emphasis = Emphasis.Subtle, direction: Direction? = null, onClick: () -> Unit): () -> Unit = {
    Sounds.play(cue, emphasis, direction, input = if (cue == Cue.Tap) Input.Touch else null)
    onClick()
}

/** Which way a tab moved: a later one is forward, and the same one is no direction at all. */
fun directionOf(from: Int, to: Int): Direction? = when {
    to > from -> Direction.Forward
    to < from -> Direction.Back
    else -> null
}

/** A switch or checkbox turning on is forward, off is back. */
fun directionOf(on: Boolean): Direction = if (on) Direction.Forward else Direction.Back
