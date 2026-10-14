package com.octaviantocan.pulls.ui.book

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octaviantocan.pulls.ui.design.BreathingDot
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.DarkPullsColors
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
        SectionTitle("Breathe, turn and shimmer")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PullsSpace.x4)) {
            BreathingDot()
            Orb()
            Skeleton(Modifier.weight(1f))
        }
    }
}

/** A pressable that scales to the press token while a finger is on it. */
@Composable
private fun PressDemo() {
    val c = LocalPullsColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(150), label = "press")
    val shape = RoundedCornerShape(PullsRadius.card)
    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(c.raised, shape)
            .border(1.dp, c.border, shape)
            .clickable(interactionSource = interaction, indication = null) {},
        contentAlignment = Alignment.Center,
    ) { Text("Press and hold", style = PullsType.row, color = c.text) }
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
    "ext" to Lu.ext,
    "redo" to Lu.redo,
    "merge" to Lu.merge,
    "req" to Lu.req,
    "draft" to Lu.draft,
    "shut" to Lu.shut,
    "search" to Lu.search,
    "ask" to Lu.ask,
    "branch" to Lu.branch,
    "copy" to Lu.copy,
    "stop" to Lu.stop,
    "refresh" to Lu.refresh,
    "edit" to Lu.edit,
    "trash" to Lu.trash,
    "commit" to Lu.commit,
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
