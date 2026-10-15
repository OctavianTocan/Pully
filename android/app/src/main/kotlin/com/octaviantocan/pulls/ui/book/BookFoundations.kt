package com.octaviantocan.pulls.ui.book

import androidx.compose.foundation.layout.fillMaxSize
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.ui.design.ColorThemes
import com.octaviantocan.pulls.ui.design.PullsColors
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import com.octaviantocan.pulls.ui.settings.applyColorTheme
import com.octaviantocan.pulls.ui.settings.wantsDark
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.ui.design.SidePages
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octaviantocan.pulls.ui.design.BreathingDot
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.DarkPullsColors
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.IconSwap
import com.octaviantocan.pulls.ui.design.LabelSwitch
import com.octaviantocan.pulls.ui.design.LightPullsColors
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuGlyph
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PullsFonts
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.pageSlide
import com.octaviantocan.pulls.ui.design.rememberPageSlide

// The foundations pages read the same numbers the checks compare with the Kotlin tokens, so what
// they draw is what the app renders.

private val medium: TextStyle get() = PullsType.sm.copy(fontWeight = FontWeight.Medium)

@Composable
internal fun ColorsPage(book: BookData) {
    PageColumn {
        PageTitle("Colors", "Every colour as a light and dark pair, light first. A colour with no dark pair fails the check (rule 47).")
        book.colors.forEach { token ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
                Swatch(hexColor(token.light), LightPullsColors.base)
                Swatch(hexColor(token.dark), DarkPullsColors.base)
                Column(Modifier.weight(1f)) {
                    Text(token.name, style = medium, color = LocalPullsColors.current.text)
                    Mono("${token.light} ${token.dark}", muted = true)
                }
            }
        }
    }
}

/** The roles a theme's strip shows, in the order a screen stacks them: surfaces, ink, accent, states, diff. */
private val STRIP: List<Pair<String, (PullsColors) -> Color>> = listOf(
    "base" to { it.base },
    "secondary" to { it.secondary },
    "raised" to { it.raised },
    "text" to { it.text },
    "muted" to { it.muted },
    "accent" to { it.accentTeal },
    "primary" to { it.primary },
    "success" to { it.success },
    "warning" to { it.warning },
    "error" to { it.error },
    "diff-add" to { it.diffAdd },
    "diff-del" to { it.diffDel },
)

/**
 * Every colour theme (K18) as two strips, its light and its dark palette, with the contrast of its text on its screen.
 * A tap on a theme switches the app to it through the theme wipe, as Settings' Theme page does; the tick is the one in use.
 */
@Composable
internal fun ThemesPage() {
    val c = LocalPullsColors.current
    val settings = AppSettings.get(LocalContext.current)
    val dark = wantsDark(settings)
    val motion = rememberMotionEnabled()
    val scope = rememberCoroutineScope()
    PageColumn {
        PageTitle("Themes", "Pulls and the themes converted from OpenCode's, each a light and a dark palette; Light, Dark and System pick which. A theme with one look shows it twice. Generated by android/scripts/gen-opencode-themes.mjs.")
        Text(STRIP.joinToString("  ") { it.first }, style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted)
        ColorThemes.all.forEach { theme ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.lg))
                    .clickable(role = Role.RadioButton, onClickLabel = theme.name) { scope.applyColorTheme(settings, theme, dark, motion) }
                    .padding(vertical = PullsSpace.x1),
                verticalArrangement = Arrangement.spacedBy(PullsSpace.x1),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
                    Text(theme.name, Modifier.weight(1f), style = medium, color = c.text)
                    if (theme.id == ColorThemes.of(settings.colorTheme).id) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
                }
                ThemeStrip(theme.light)
                ThemeStrip(theme.colors(true))
                val ratios = listOf(theme.light, theme.colors(true)).joinToString(" / ") { "%.1f".format(contrastRatio(it.text, it.base)) }
                Mono("${theme.id}, text on base $ratios${if (theme.dark == null) ", one look" else ""}", muted = true)
            }
        }
    }
}

/** One palette as a row of equal swatches, outlined so a swatch the page's own colour still shows. */
@Composable
private fun ThemeStrip(palette: PullsColors) {
    val shape = RoundedCornerShape(PullsRadius.md)
    Row(Modifier.fillMaxWidth().height(28.dp).clip(shape).border(1.dp, LocalPullsColors.current.border, shape)) {
        STRIP.forEach { (_, role) -> Box(Modifier.weight(1f).fillMaxSize().background(role(palette))) }
    }
}

/** WCAG 2 contrast of two opaque colours. */
private fun contrastRatio(x: Color, y: Color): Float {
    val (hi, lo) = listOf(x.luminance(), y.luminance()).sortedDescending()
    return (hi + 0.05f) / (lo + 0.05f)
}

/** A colour over the page it sits on, so a translucent one shows as it will render. */
@Composable
private fun Swatch(color: Color, behind: Color) {
    val shape = RoundedCornerShape(PullsRadius.lg)
    Box(Modifier.size(44.dp).clip(shape).background(behind).border(1.dp, LocalPullsColors.current.border, shape)) {
        Box(Modifier.size(44.dp).background(color))
    }
}

private fun TypeToken.style(): TextStyle = TextStyle(
    fontFamily = when (family) {
        "Geist Mono" -> PullsType.mono
        "Mona Sans" -> PullsType.display
        else -> PullsFonts.sans
    },
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = letterSpacing.sp,
)

@Composable
internal fun TypographyPage(book: BookData) {
    val c = LocalPullsColors.current
    PageColumn {
        PageTitle("Typography", "Every role, set as the app sets it. The -wide roles are the 768dp and up steps. Anything under 15 is a defect (rule 14).")
        book.typography.forEach { token ->
            Column(Modifier.padding(vertical = PullsSpace.x1), verticalArrangement = Arrangement.spacedBy(PullsSpace.x1)) {
                Text("Pull requests, reviewed", style = token.style(), color = c.text)
                val under = if (token.size < 15f) ", under 15" else ""
                Mono("${token.name} ${token.family} ${dp(token.size)}/${dp(token.lineHeight)} ${token.weight}$under", muted = true)
            }
        }
    }
}

@Composable
internal fun ShapesPage(book: BookData) {
    val c = LocalPullsColors.current
    PageColumn {
        PageTitle("Shapes", "Corner radii. Full is a stadium: it clamps to half the shorter side.")
        book.rounded.forEach { radius ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PullsSpace.x4)) {
                Box(Modifier.size(72.dp).background(c.raised, RoundedCornerShape(radius.value.dp)).border(1.dp, c.border, RoundedCornerShape(radius.value.dp)))
                Column {
                    Text(radius.name, style = medium, color = c.text)
                    Mono("${dp(radius.value)}dp", muted = true)
                }
            }
        }
    }
}

@Composable
internal fun SpacingPage(book: BookData) {
    val c = LocalPullsColors.current
    PageColumn {
        PageTitle("Spacing", "Steps of 4dp. Step 4 is the 16dp page margin. Code reads PullsSpace.")
        book.spacing.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
                Mono("x${step.name}", modifier = Modifier.width(36.dp))
                Box(Modifier.width(step.value.dp).height(PullsSpace.x3).background(c.accentTeal, RoundedCornerShape(PullsRadius.xs)))
                Mono("${dp(step.value)}dp", muted = true)
            }
        }
    }
}

@Composable
internal fun ElevationPage(book: BookData) {
    PageColumn {
        PageTitle("Elevation", "The canvas shadows from Row W, drawn by floatShadow: a soft ink drop two pixels down, never an even glow (rule 45). Each name is the blur.")
        book.elevation.forEach { level ->
            Themed(ThemeMode.Both, level.name) {
                val shape = RoundedCornerShape(PullsRadius.card)
                Row(horizontalArrangement = Arrangement.spacedBy(PullsSpace.x3), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(72.dp).floatShadow(shape, PullsShadow.entries.first { it.name.equals(level.name, ignoreCase = true) }).clip(shape).background(LocalPullsColors.current.raised))
                    Mono("${level.name} ${dp(level.value)}dp", muted = true)
                }
            }
        }
    }
}

@Composable
internal fun MotionPage(book: BookData) {
    PageColumn {
        PageTitle("Motion", "Durations and curves, and the live ones. Everything that moves says so here first.")
        book.motion.forEach { (name, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
                Mono(name, modifier = Modifier.width(112.dp))
                Mono(value, muted = true)
            }
        }
        SectionTitle("Press")
        PressDemo()
        SectionTitle("Slide")
        var selected by remember { mutableIntStateOf(0) }
        CapsuleTabs(listOf("Inbox", "Tours", "Done"), selected, { selected = it })
        SectionTitle("Side by side")
        SidePagesDemo()
        SectionTitle("Slide in a list")
        PageSlideDemo()
        SectionTitle("Label")
        LabelDemo()
        SectionTitle("Swap")
        SwapDemo()
        SectionTitle("Breathe, turn and shimmer")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PullsSpace.x4)) {
            BreathingDot()
            Orb()
            Skeleton(Modifier.weight(1f))
        }
    }
}

/** An icon that swaps for another on tap: the old one shrinks away through a soft blur as the new one grows in (rule 73). */
@Composable
private fun SwapDemo() {
    val c = LocalPullsColors.current
    var copied by remember { mutableStateOf(false) }
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { copied = !copied }, contentAlignment = Alignment.Center) {
        IconSwap(copied) { done -> LuIcon(if (done) Lu.check else Lu.copy, tint = c.text, size = 22.dp) }
    }
}

/** A card that takes the flat press tint while a finger is on it, and nothing else: it does not scale, shift or grow (rule 82). */
@Composable
private fun PressDemo() {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(PullsRadius.card)
    Box(
        Modifier.fillMaxWidth().height(72.dp).clip(shape).background(c.raised, shape).border(1.dp, c.border, shape).clickable(role = Role.Button) {},
        contentAlignment = Alignment.Center,
    ) { Text("Press and hold", style = PullsType.row, color = c.text) }
}

/**
 * Rows that change as a page when the tab above them does: the old rows leave the way the tabs go and the new ones
 * arrive from the other side (rules 73 and 105), the tabs stay put.
 */
@Composable
private fun PageSlideDemo() {
    val c = LocalPullsColors.current
    var tab by remember { mutableIntStateOf(0) }
    val slide = rememberPageSlide(tab, order = { it }, full = true)
    Column(verticalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
        CapsuleTabs(listOf("Summary", "Commits", "Conversation"), tab, { tab = it })
        Column(Modifier.pageSlide(slide), verticalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
            repeat(3) { row -> Text("${listOf("Summary", "Commits", "Conversation")[slide.shown]}, row ${row + 1}", style = PullsType.row, color = c.text) }
        }
    }
}

/** Screens side by side, as the Inbox and Tours are: a tab or a swipe moves both, with nothing fading (H20, rule 105). */
@Composable
private fun SidePagesDemo() {
    val c = LocalPullsColors.current
    var tab by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
        CapsuleTabs(listOf("Inbox", "Tours"), tab, { tab = it })
        SidePages(tab, listOf(0, 1), { tab = it }, Modifier.fillMaxWidth().height(96.dp)) { page ->
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(PullsRadius.card)).background(c.raised), contentAlignment = Alignment.Center) {
                Text(if (page == 0) "Inbox, swipe left" else "Tours, swipe right", style = PullsType.row, color = c.text)
            }
        }
    }
}

/** A label replaced by its neighbour, as the file name between ‹ and › is: the next comes from the right, the previous from the left (rule 105). */
@Composable
private fun LabelDemo() {
    val c = LocalPullsColors.current
    val names = listOf("Motion.kt", "Kit.kt", "Sheet.kt", "Tokens.kt")
    var at by remember { mutableIntStateOf(0) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
        FloatIcon(Lu.prev, "Previous", { at = (at - 1).coerceAtLeast(0) }, enabled = at > 0)
        LabelSwitch(at, Modifier.weight(1f), order = { it }) { i ->
            Text(names[i], Modifier.fillMaxWidth(), style = PullsType.row, color = c.text, textAlign = TextAlign.Center)
        }
        FloatIcon(Lu.next, "Next", { at = (at + 1).coerceAtMost(names.lastIndex) }, enabled = at < names.lastIndex)
    }
}

/** Every glyph in `Lu`, by name. `pnpm run android:design` fails when one is missing from this list. */
private val GLYPHS: List<Pair<String, LuGlyph>> = listOf(
    "cog" to Lu.cog,
    "back" to Lu.back,
    "more" to Lu.more,
    "list" to Lu.list,
    "diff" to Lu.diff,
    "tour" to Lu.tour,
    "chev" to Lu.chev,
    "chevd" to Lu.chevd,
    "arrow" to Lu.arrow,
    "arch" to Lu.arch,
    "xc" to Lu.xc,
    "okc" to Lu.okc,
    "to" to Lu.to,
    "exp" to Lu.exp,
    "prev" to Lu.prev,
    "next" to Lu.next,
    "reply" to Lu.reply,
    "close" to Lu.close,
    "check" to Lu.check,
    "tick" to Lu.tick,
    "fail" to Lu.fail,
    "redo" to Lu.redo,
    "merge" to Lu.merge,
    "draft" to Lu.draft,
    "shut" to Lu.shut,
    "search" to Lu.search,
    "sort" to Lu.sort,
    "branch" to Lu.branch,
    "layers" to Lu.layers,
    "copy" to Lu.copy,
    "stop" to Lu.stop,
    "refresh" to Lu.refresh,
    "edit" to Lu.edit,
    "trash" to Lu.trash,
    "commit" to Lu.commit,
    "file" to Lu.file,
    "terminal" to Lu.terminal,
    "think" to Lu.think,
    "write" to Lu.write,
    "tool" to Lu.tool,
    "newChat" to Lu.newChat,
    "followUp" to Lu.followUp,
)

@Composable
internal fun IconsPage() {
    val c = LocalPullsColors.current
    PageColumn {
        PageTitle("Icons", "The Lucide set the app draws itself. Ask of each icon which fact it carries; none means cut it (rules 1 and 48).")
        GLYPHS.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (name, glyph) ->
                    Column(Modifier.weight(1f).padding(vertical = PullsSpace.x2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PullsSpace.x1)) {
                        LuIcon(glyph, size = 28.dp)
                        Text(name, style = PullsType.sm, color = c.muted, maxLines = 1)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun RulesPage(book: BookData) {
    val c = LocalPullsColors.current
    var mode by remember { mutableIntStateOf(0) }
    val sources = listOf(null, "canvas", "walkthrough", "perplexity")
    val shown = book.rules.filter { sources[mode] == null || it.source == sources[mode] }
    PageColumn {
        PageTitle("Rules", "The numbered rules from DESIGN.md. Cite them by number. Each says who checks it.")
        CapsuleTabs(listOf("All", "Canvas", "Walkthrough", "Perplexity"), mode, { mode = it })
        shown.forEach { rule ->
            Column(Modifier.padding(top = PullsSpace.x3), verticalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
                Text("${rule.n}. ${rule.title}", style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text)
                Markdown(rule.text, style = PullsType.read)
                Mono("${rule.source}, checked by ${rule.checkedBy.joinToString(", ")}", muted = true)
            }
        }
    }
}
