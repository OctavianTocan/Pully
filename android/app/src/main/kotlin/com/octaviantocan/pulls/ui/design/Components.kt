package com.octaviantocan.pulls.ui.design

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** 44dp: the web's `coarse:` touch target (`main.css`, `@media (pointer: coarse)`). */
val TOUCH = 44.dp

/**
 * A Phosphor icon by its web name (`i-ph:git-pull-request-duotone` -> `git-pull-request-duotone`),
 * drawn from the vectors `scripts/gen-phosphor.mjs` generated from `@iconify-json/ph`.
 * Unknown names render nothing, the way an unknown UnoCSS icon class does.
 */
@SuppressLint("DiscouragedApi")
@Composable
fun PhIcon(name: String, modifier: Modifier = Modifier, tint: Color = LocalPullsColors.current.text, size: Dp = 18.dp, contentDescription: String? = null) {
    val id = phIconId(LocalContext.current, name)
    if (id == 0) return
    Icon(painterResource(id), contentDescription, modifier.size(size), tint = tint)
}

private val iconIds = mutableMapOf<String, Int>()

@SuppressLint("DiscouragedApi")
fun phIconId(context: Context, name: String): Int = iconIds.getOrPut(name) {
    val clean = name.removePrefix("i-ph:").removePrefix("i-ph-").replace('-', '_')
    context.resources.getIdentifier("ic_ph_$clean", "drawable", context.packageName)
}

/** Press feedback the web adds on touch (`:active` scale in `main.css`). */
@Composable
private fun Modifier.press(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    return this.scale(if (pressed) 0.97f else 1f)
}

enum class ButtonVariant { Action, Primary, Text }

/**
 * `ActionButton` from `@antfu/design`: `btn-action` (bordered), `btn-primary` (green fill)
 * and `btn-text` (ghost) share one box (`px2 py1 rounded`, 1px border), `size="sm"` adds `text-sm`.
 */
@Composable
fun ActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Action,
    icon: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    active: Boolean = false,
    small: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(4.dp)
    val interaction = remember { MutableInteractionSource() }
    val (fill, border, fg) = when (variant) {
        ButtonVariant.Primary -> Triple(c.primary, Color.Transparent, Color.White)
        ButtonVariant.Text -> Triple(Color.Transparent, Color.Transparent, c.text)
        ButtonVariant.Action -> if (active) Triple(c.active, c.primary.copy(alpha = 0.25f), c.activeText) else Triple(Color.Transparent, c.border, c.text)
    }
    val restAlpha = when {
        !enabled -> if (variant == ButtonVariant.Primary) 0.5f else 0.3f
        variant == ButtonVariant.Primary || active -> 1f
        else -> 0.75f
    }
    Row(
        modifier
            .defaultMinSize(minHeight = TOUCH)
            .press(interaction)
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, border, shape)
            .clickable(interaction, null, enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .alpha(restAlpha)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = fg, strokeWidth = 2.dp)
        else if (icon != null) PhIcon(icon, tint = fg, size = 18.dp)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides fg,
            androidx.compose.material3.LocalTextStyle provides (if (small) PullsType.sm else PullsType.base).copy(color = fg),
        ) { content() }
    }
}

/** `ActionIconButton`: `btn-icon` (36px round) or `btn-icon-compact` (24px), with a 44dp hit area. */
@Composable
fun IconButton(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, active: Boolean = false, enabled: Boolean = true, tint: Color? = null) {
    val c = LocalPullsColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(TOUCH)
            .press(interaction)
            .clip(if (compact) RoundedCornerShape(4.dp) else CircleShape)
            .background(if (active) c.active else Color.Transparent)
            .clickable(interaction, null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .alpha(if (!enabled) 0.3f else if (active) 1f else 0.65f),
        contentAlignment = Alignment.Center,
    ) {
        PhIcon(icon, tint = tint ?: if (active) c.activeText else c.text, size = if (compact) 16.dp else 20.dp, contentDescription = label)
    }
}

/** `ActionToggleGroup`: `p-1 rounded-lg bg-sunken`, items `px-3 py-1 rounded-md`, the active one `bg-raised shadow-sm`. */
@Composable
fun <T> ToggleGroup(options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit, modifier: Modifier = Modifier, icons: Map<T, String> = emptyMap()) {
    val c = LocalPullsColors.current
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(c.sunken).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (option, label) ->
            val on = option == value
            Row(
                Modifier
                    .defaultMinSize(minHeight = 36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) c.raised else Color.Transparent)
                    .clickable(role = Role.Tab) { onChange(option) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                icons[option]?.let { PhIcon(it, tint = if (on) c.text else c.muted, size = 16.dp) }
                Text(label, style = PullsType.sm, color = if (on) c.text else c.muted, maxLines = 1)
            }
        }
    }
}

/** Review state of a file: the web `ReviewStatus` plus the folder-only `partial`. */
enum class ReviewState { Reviewed, Unreviewed, Changed, Partial }

/** `ReviewCheckbox`: 16px box, primary fill when checked, orange indeterminate for `changed`. */
@Composable
fun ReviewCheckbox(state: ReviewState, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val c = LocalPullsColors.current
    val checked = state == ReviewState.Reviewed
    val indeterminate = state == ReviewState.Changed || state == ReviewState.Partial
    val fill = when {
        state == ReviewState.Changed -> c.orange
        checked || indeterminate -> c.primary
        else -> c.raised
    }
    Box(
        modifier
            .size(TOUCH)
            .clickable(role = Role.Checkbox, onClickLabel = label) { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(fill).border(1.dp, if (checked || indeterminate) fill else c.border, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) PhIcon("check-bold", tint = Color.White, size = 11.dp)
            else if (indeterminate) PhIcon("minus-bold", tint = Color.White, size = 11.dp)
        }
    }
}

/** `FileStatus`: one letter in a 16px tinted chip. */
@Composable
fun FileStatus(status: String, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val (letter, color) = when (status) {
        "added" -> "A" to c.success
        "removed" -> "D" to c.error
        "renamed" -> "R" to c.blue
        "copied" -> "C" to c.blue
        else -> "M" to c.warning
    }
    Box(modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
        Text(letter, style = PullsType.xs.copy(fontFamily = PullsType.mono), color = color)
    }
}

/** `DiffStats`: green `+n`, red `-n`, `text-xs tabular-nums`. */
@Composable
fun DiffStats(additions: Int?, deletions: Int?, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if ((additions ?: 0) > 0) Text("+${"%,d".format(additions)}", style = PullsType.xs, color = c.success)
        if ((deletions ?: 0) > 0) Text("-${"%,d".format(deletions)}", style = PullsType.xs, color = c.error)
    }
}

/** `PrStatusIcon`: the pull-request glyph (merge glyph once merged), colored by state. */
@Composable
fun PrStatusIcon(state: String, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val c = LocalPullsColors.current
    val color = when (state) {
        "open" -> c.success
        "draft" -> c.gray
        "closed" -> c.error
        "merged" -> c.purple
        else -> c.muted
    }
    PhIcon(if (state == "merged") "git-merge-duotone" else "git-pull-request-duotone", modifier, tint = color, size = size)
}

/** `GithubAvatar`: round, `bg-raised`, `github.com/<login>.png` unless a URL is given, initial on failure. */
@Composable
fun GithubAvatar(login: String, modifier: Modifier = Modifier, avatarUrl: String? = null, size: Dp = 20.dp) {
    val c = LocalPullsColors.current
    val src = avatarUrl?.takeIf { it.isNotBlank() } ?: "https://github.com/${login.removeSuffix("[bot]")}.png?size=${(size.value * 2).toInt()}"
    Box(modifier.size(size).clip(CircleShape).background(c.raised), contentAlignment = Alignment.Center) {
        Text(login.firstOrNull()?.uppercase() ?: "?", style = PullsType.xs, color = c.fade)
        AsyncImage(src, contentDescription = login, modifier = Modifier.size(size), contentScale = ContentScale.Crop)
    }
}

/** `badge` / `badge-muted`: `px-1.5 py-0.5 rounded-md text-xs font-medium`. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = LocalPullsColors.current.muted, fill: Color = Color(0x11888888)) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(6.dp)).background(fill).padding(horizontal = 6.dp, vertical = 2.dp),
        style = PullsType.xs.copy(fontWeight = FontWeight.Medium),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A bordered card: `border border-base rounded-lg`. */
@Composable
fun Card(modifier: Modifier = Modifier, radius: Dp = 8.dp, content: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    Box(modifier.clip(RoundedCornerShape(radius)).border(1.dp, c.border, RoundedCornerShape(radius))) { content() }
}

/**
 * `FormTextInput` / `FormTextarea`: `border border-base rounded bg-raised`, 16px text,
 * an optional leading Phosphor icon, placeholder at `op-fade`.
 */
@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(4.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = PullsType.base.copy(color = c.text),
        cursorBrush = SolidColor(c.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier.defaultMinSize(minHeight = TOUCH),
        decorationBox = { inner ->
            Row(
                Modifier.clip(shape).background(c.raised).border(BorderStroke(1.dp, c.border), shape).padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) PhIcon(icon, tint = c.fade, size = 18.dp)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = PullsType.base, color = c.mute, maxLines = if (singleLine) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        },
    )
}

/** `FeedbackLoading`: a spinner and a muted line. */
@Composable
fun Loading(text: String, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Row(modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), color = c.fade, strokeWidth = 2.dp)
        Text(text, style = PullsType.sm, color = c.fade)
    }
}

/** `FeedbackEmptyState`: icon, title, optional hint and actions, centered. */
@Composable
fun EmptyState(icon: String, title: String, modifier: Modifier = Modifier, hint: String? = null, actions: @Composable () -> Unit = {}) {
    val c = LocalPullsColors.current
    androidx.compose.foundation.layout.Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhIcon(icon, tint = c.fade, size = 32.dp)
        Text(title, style = PullsType.base.copy(fontWeight = FontWeight.Medium), color = c.text)
        if (hint != null) Text(hint, style = PullsType.sm, color = c.fade)
        actions()
    }
}

/** A Catppuccin file or folder icon (the web `DisplayFileIcon`). */
@Composable
fun FileIcon(@DrawableRes id: Int, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Image(painterResource(id), contentDescription = null, modifier = modifier.size(size))
}

/** Default inner padding for list rows: `px-3 py-2`. */
val RowPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/** Same text style with web `op-fade`. */
@Composable
fun TextStyle.faded(): TextStyle = copy(color = LocalPullsColors.current.fade)
