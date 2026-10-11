package com.octaviantocan.pulls.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The web app's design tokens, read from the compiled CSS (`packages/cli/dist/client/assets/index-*.css`)
 * and `@antfu/design/unocss/shortcuts.ts`. Names follow the web shortcuts so a port reads
 * like the template: `bg-base` -> [PullsColors.base], `border-base` -> [PullsColors.border],
 * `op-fade` -> [PullsColors.fade], `color-success-500` -> [PullsColors.success].
 */
@Immutable
data class PullsColors(
    val dark: Boolean,
    /** `bg-base`: #111 dark, white light. */
    val base: Color,
    /** `color-base`: neutral-200 dark, neutral-800 light. */
    val text: Color,
    /** `color-muted`: neutral-400 dark, neutral-600 light. */
    val muted: Color,
    /** `color-faint`: neutral-500. */
    val faint: Color,
    /** `border-base`: #9992. */
    val border: Color,
    /** `border-mute`: #9991. */
    val borderMute: Color,
    /** `bg-hover`: #99999915. */
    val hover: Color,
    /** `bg-active`: #99999920. */
    val active: Color,
    /** `bg-raised`: white/65 light, black/60 dark. */
    val raised: Color,
    /** `bg-sunken`: black/4 light, white/6 dark (segment tracks). */
    val sunken: Color,
    /** `bg-code`: gray-500/5. */
    val code: Color,
    /** `bg-secondary`. */
    val secondary: Color,
    /** antfu green `primary-500` (#5b9544): primary buttons, checked boxes. */
    val primary: Color,
    val primary600: Color,
    /** `color-active`: primary-300 dark, primary-600 light. */
    val activeText: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
    val blue: Color,
    val purple: Color,
    val gray: Color,
    val orange: Color,
    val amber: Color,
    /** `color-accent-teal`, `color-accent-orange`, `color-accent-magenta`. */
    val accentTeal: Color,
    val accentOrange: Color,
    val accentMagenta: Color,
) {
    /** `op-fade` (op65) applied to body text. */
    val fade: Color get() = text.copy(alpha = 0.65f)

    /** `op-mute` (op40). */
    val mute: Color get() = text.copy(alpha = 0.40f)
}

val DarkPullsColors = PullsColors(
    dark = true,
    base = Color(0xFF111111),
    text = Color(0xFFE5E5E5),
    muted = Color(0xFFA3A3A3),
    faint = Color(0xFF737373),
    border = Color(0x22999999),
    borderMute = Color(0x11999999),
    hover = Color(0x15999999),
    active = Color(0x20999999),
    raised = Color(0x99000000),
    sunken = Color(0x0FFFFFFF),
    code = Color(0x0D6B7280),
    secondary = Color(0xFF101010),
    primary = Color(0xFF5B9544),
    primary600 = Color(0xFF49833E),
    activeText = Color(0xFFA8CF96),
    success = Color(0xFF12B76A),
    error = Color(0xFFF63D68),
    warning = Color(0xFFF79009),
    blue = Color(0xFF3B82F6),
    purple = Color(0xFFA855F7),
    gray = Color(0xFF6B7280),
    orange = Color(0xFFF97316),
    amber = Color(0xFFFBBF24),
    accentTeal = Color(0xFF5FC2A4),
    accentOrange = Color(0xFFF57A4F),
    accentMagenta = Color(0xFFC65FBF),
)

val LightPullsColors = PullsColors(
    dark = false,
    base = Color(0xFFFFFFFF),
    text = Color(0xFF262626),
    muted = Color(0xFF525252),
    faint = Color(0xFF737373),
    border = Color(0x22999999),
    borderMute = Color(0x11999999),
    hover = Color(0x15999999),
    active = Color(0x20999999),
    raised = Color(0xA6FFFFFF),
    sunken = Color(0x0A000000),
    code = Color(0x0D6B7280),
    secondary = Color(0xFFF6F6F6),
    primary = Color(0xFF5B9544),
    primary600 = Color(0xFF49833E),
    activeText = Color(0xFF49833E),
    success = Color(0xFF12B76A),
    error = Color(0xFFF63D68),
    warning = Color(0xFFF79009),
    blue = Color(0xFF3B82F6),
    purple = Color(0xFFA855F7),
    gray = Color(0xFF6B7280),
    orange = Color(0xFFEA580C),
    amber = Color(0xFFB45309),
    accentTeal = Color(0xFF1F8F72),
    accentOrange = Color(0xFFD9552A),
    accentMagenta = Color(0xFF9C3A95),
)

/**
 * The phone type scale from `main.css` (max-width 767.9px): `text-sm` is 16px, `text-xs`
 * 13px; `text-base` 16, `text-lg` 18, `text-2xl` 24. Families are the system ones the web
 * stack resolves to on Android (`ui-sans-serif` -> Roboto, `ui-monospace` -> monospace).
 */
object PullsType {
    val xs = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val sm = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val base = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val lg = TextStyle(fontSize = 18.sp, lineHeight = 28.sp)
    val xl = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)
    val xxl = TextStyle(fontSize = 24.sp, lineHeight = 32.sp)

    /** `text-micro` 10px, `text-mini` 11px, `text-compact` 12px. */
    val micro = TextStyle(fontSize = 10.sp, lineHeight = 14.sp)
    val mini = TextStyle(fontSize = 11.sp, lineHeight = 16.sp)
    val compact = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
    val mono = FontFamily.Monospace
    val semibold = FontWeight.SemiBold
    val medium = FontWeight.Medium
}

val LocalPullsColors = staticCompositionLocalOf { DarkPullsColors }
