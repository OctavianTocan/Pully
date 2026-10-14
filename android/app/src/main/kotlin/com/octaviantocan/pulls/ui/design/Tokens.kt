package com.octaviantocan.pulls.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.octaviantocan.pulls.R

/**
 * The app's design tokens. The light set is the Android prototype's palette (warm paper, deep
 * teal ink, one teal accent); the dark set is derived from it so the theme setting keeps working.
 * Names follow the web shortcuts the screens were first ported from (`bg-base` -> [PullsColors.base],
 * `border-base` -> [PullsColors.border], `op-fade` -> [PullsColors.fade]), with the prototype's own
 * names for what the web has no word for ([paper], [hair], [cta], the diff washes).
 */
@Immutable
data class PullsColors(
    val dark: Boolean,
    /** Screen background: `--bg` #FDFCF9. */
    val base: Color,
    /** Ink: `--ink` #0E333A. */
    val text: Color,
    /** Secondary text: `--muted` #6E8285. */
    val muted: Color,
    /** Tertiary text and icons: `--grey` #93A2A4. */
    val faint: Color,
    /** Borders: `--bd` #E3E5E3. */
    val border: Color,
    /** Hairlines between rows: `--hair` #ECEBE7. */
    val borderMute: Color,
    /** Pressed and hovered rows. */
    val hover: Color,
    /** Selected rows. */
    val active: Color,
    /** Cards, floating buttons and inputs: white. */
    val raised: Color,
    /** Segment tracks and the selected segment: `--paper2`. */
    val sunken: Color,
    /** Code and quote backgrounds: `--paper`. */
    val code: Color,
    /** Sheets and grouped backgrounds: `--paper`. */
    val secondary: Color,
    /** The accent used for fills and checked boxes: `--accent2` #1B7580. */
    val primary: Color,
    val primary600: Color,
    /** Accent text and links: `--link` #1E6F78. */
    val activeText: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
    val blue: Color,
    val purple: Color,
    val gray: Color,
    val orange: Color,
    val amber: Color,
    /** The brighter accent: `--accent` #24929C (progress, live dots, selection bars). */
    val accentTeal: Color,
    val accentOrange: Color,
    val accentMagenta: Color,
    /** `--paper` #F4F2EF: sheets, hunk bars, code wells. */
    val paper: Color,
    /** `--paper2` #F1F0EC: the selected segment. */
    val paper2: Color,
    /** The dark call-to-action fill: `--dark` #13343B. */
    val cta: Color,
    val onCta: Color,
    /** A pressed or selected filter chip: #E9E8E3. */
    val chipOn: Color,
    /** Tinted fill behind accent badges and bubbles: #E6F1F2. */
    val accentWash: Color,
    /** Text on [accentWash]: #165E66. */
    val accentWashInk: Color,
    /** Diff row washes and the ink of their gutters. */
    val diffAdd: Color,
    val diffDel: Color,
    val diffAddInk: Color,
    val diffDelInk: Color,
    val diffAddMark: Color,
    val diffDelMark: Color,
    /** The shadow tint of floating surfaces: the ink at low alpha. */
    val shadow: Color,
    /** Behind sheets: `rgba(14,51,58,.38)`. */
    val scrim: Color,
) {
    /** `op-fade`: secondary text. */
    val fade: Color get() = muted

    /** `op-mute`: tertiary text. */
    val mute: Color get() = faint
}

val LightPullsColors = PullsColors(
    dark = false,
    base = Color(0xFFFDFCF9),
    text = Color(0xFF0E333A),
    muted = Color(0xFF6E8285),
    faint = Color(0xFF93A2A4),
    border = Color(0xFFE3E5E3),
    borderMute = Color(0xFFECEBE7),
    hover = Color(0x120E333A),
    active = Color(0x1A0E333A),
    raised = Color(0xFFFFFFFF),
    sunken = Color(0xFFF1F0EC),
    code = Color(0xFFF4F2EF),
    secondary = Color(0xFFF4F2EF),
    primary = Color(0xFF1B7580),
    primary600 = Color(0xFF165E66),
    activeText = Color(0xFF1E6F78),
    success = Color(0xFF1C8A5A),
    error = Color(0xFFC94F55),
    warning = Color(0xFFD9822B),
    blue = Color(0xFF3B82F6),
    purple = Color(0xFFA855F7),
    gray = Color(0xFF93A2A4),
    orange = Color(0xFFEA580C),
    amber = Color(0xFFB45309),
    accentTeal = Color(0xFF24929C),
    accentOrange = Color(0xFFD9552A),
    accentMagenta = Color(0xFF9C3A95),
    paper = Color(0xFFF4F2EF),
    paper2 = Color(0xFFF1F0EC),
    cta = Color(0xFF13343B),
    onCta = Color(0xFFFFFFFF),
    chipOn = Color(0xFFE9E8E3),
    accentWash = Color(0xFFE6F1F2),
    accentWashInk = Color(0xFF165E66),
    diffAdd = Color(0xFFCFE6E3),
    diffDel = Color(0xFFF6D7D0),
    diffAddInk = Color(0xFF1E7E8A),
    diffDelInk = Color(0xFFB4533F),
    diffAddMark = Color(0xFFA8D6D0),
    diffDelMark = Color(0xFFEDB5AA),
    shadow = Color(0xFF0E333A),
    scrim = Color(0x610E333A),
)

val DarkPullsColors = PullsColors(
    dark = true,
    base = Color(0xFF161616),
    text = Color(0xFFE3E3E3),
    muted = Color(0xFF9D9D9D),
    faint = Color(0xFF707070),
    border = Color(0xFF2B2B2B),
    borderMute = Color(0xFF242424),
    hover = Color(0x14FFFFFF),
    active = Color(0x1FFFFFFF),
    raised = Color(0xFF1D1D1D),
    sunken = Color(0xFF212121),
    code = Color(0xFF1B1B1B),
    secondary = Color(0xFF1B1B1B),
    primary = Color(0xFF1B7580),
    primary600 = Color(0xFF165E66),
    activeText = Color(0xFF6CC3CB),
    success = Color(0xFF3CC98F),
    error = Color(0xFFE8777C),
    warning = Color(0xFFF2A14B),
    blue = Color(0xFF60A5FA),
    purple = Color(0xFFC084FC),
    gray = Color(0xFF707070),
    orange = Color(0xFFFB923C),
    amber = Color(0xFFFBBF24),
    accentTeal = Color(0xFF4FB7C1),
    accentOrange = Color(0xFFF57A4F),
    accentMagenta = Color(0xFFC65FBF),
    paper = Color(0xFF1B1B1B),
    paper2 = Color(0xFF212121),
    cta = Color(0xFFE3E3E3),
    onCta = Color(0xFF161616),
    chipOn = Color(0xFF2A2A2A),
    accentWash = Color(0xFF14363B),
    accentWashInk = Color(0xFF9AD3D6),
    diffAdd = Color(0xFF123B3A),
    diffDel = Color(0xFF3F2420),
    diffAddInk = Color(0xFF6CC3CB),
    diffDelInk = Color(0xFFE08A76),
    diffAddMark = Color(0xFF1C5A58),
    diffDelMark = Color(0xFF6A3A32),
    shadow = Color(0xFF000000),
    scrim = Color(0x99000000),
)

/** Geist for the interface, Geist Mono for code and branch names, Mona Sans for numbers and file names. */
object PullsFonts {
    val sans = FontFamily(
        Font(R.font.geist_regular, FontWeight.Normal),
        Font(R.font.geist_medium, FontWeight.Medium),
        Font(R.font.geist_semibold, FontWeight.SemiBold),
    )
    val mono = FontFamily(
        Font(R.font.geist_mono_regular, FontWeight.Normal),
        Font(R.font.geist_mono_medium, FontWeight.Medium),
    )
    val display = FontFamily(
        Font(R.font.mona_sans_regular, FontWeight.Normal),
        Font(R.font.mona_sans_medium, FontWeight.Medium),
        Font(R.font.mona_sans_semibold, FontWeight.SemiBold),
    )
}

/**
 * The prototype's type scale: 15/20 for lists and meta, 17 for row titles and body copy, 26 for page
 * titles, 20 for section headings. [sm] and [xs] step down a notch from 768dp up, as before.
 */
object PullsType {
    /** Set by `PullsTheme` from the window width (the unfolded Fold is ~840dp wide). */
    var wide by androidx.compose.runtime.mutableStateOf(false)
        internal set
    val xs: TextStyle get() = if (wide) TextStyle(fontFamily = PullsFonts.sans, fontSize = 12.sp, lineHeight = 16.sp) else TextStyle(fontFamily = PullsFonts.sans, fontSize = 13.sp, lineHeight = 18.sp)
    val sm: TextStyle get() = if (wide) TextStyle(fontFamily = PullsFonts.sans, fontSize = 14.sp, lineHeight = 20.sp) else TextStyle(fontFamily = PullsFonts.sans, fontSize = 15.sp, lineHeight = 21.sp)
    val base = TextStyle(fontFamily = PullsFonts.sans, fontSize = 16.sp, lineHeight = 22.sp)
    val lg = TextStyle(fontFamily = PullsFonts.sans, fontSize = 18.sp, lineHeight = 26.sp)
    val xl = TextStyle(fontFamily = PullsFonts.sans, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
    val xxl = TextStyle(fontFamily = PullsFonts.sans, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.26).sp)

    /** `.body`: 17/25. */
    val body = TextStyle(fontFamily = PullsFonts.sans, fontSize = 17.sp, lineHeight = 25.sp)

    /** `.p16` and `.tp`: 16/24, the reading size of summaries, descriptions and tour text. */
    val read = TextStyle(fontFamily = PullsFonts.sans, fontSize = 16.sp, lineHeight = 24.sp)

    /** `.pr .tt`: 17/22, a row's title. */
    val row = TextStyle(fontFamily = PullsFonts.sans, fontSize = 17.sp, lineHeight = 22.sp)

    /** `.pr .num` and the PR number in headings: Mona Sans 15/22 semibold. */
    val num = TextStyle(fontFamily = PullsFonts.display, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)

    /** `text-micro` 10px, `text-mini` 11px, `text-compact` 12px. */
    val micro = TextStyle(fontFamily = PullsFonts.sans, fontSize = 10.sp, lineHeight = 14.sp)
    val mini = TextStyle(fontFamily = PullsFonts.sans, fontSize = 11.sp, lineHeight = 16.sp)
    val compact = TextStyle(fontFamily = PullsFonts.sans, fontSize = 12.sp, lineHeight = 18.sp)
    /** The code face the reader chose (Geist Mono until they pick another); see [CodeFonts]. */
    val mono: FontFamily get() = CodeFonts.family
    val display: FontFamily = PullsFonts.display
    val semibold = FontWeight.SemiBold
    val medium = FontWeight.Medium
}

val LocalPullsColors = staticCompositionLocalOf { LightPullsColors }
