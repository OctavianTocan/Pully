package com.octaviantocan.pulls.ui.book

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.LocalColorTheme
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import kotlin.math.abs
import kotlin.math.roundToInt

// The pieces every UI Book page is made of. They are built from the kit and the tokens, like the
// screens: the book has no look of its own.

/** A page's column with the 16dp margin. The host scrolls it. */
@Composable
internal fun PageColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = PullsSpace.x4).padding(bottom = PullsSpace.x4),
        verticalArrangement = Arrangement.spacedBy(PullsSpace.x3),
        content = content,
    )
}

@Composable
internal fun PageTitle(title: String, summary: String?) {
    val c = LocalPullsColors.current
    Column(Modifier.padding(top = PullsSpace.x2), verticalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
        Text(title, style = PullsType.xxl, color = c.text)
        if (summary != null) Text(summary, style = PullsType.read, color = c.muted)
    }
}

/** One word where it can be (rule 14): a section label in sentence case. */
@Composable
internal fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = PullsSpace.x4), style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = LocalPullsColors.current.text)
}

@Composable
internal fun BodyText(text: String, muted: Boolean = false) {
    val c = LocalPullsColors.current
    Text(text, style = PullsType.read, color = if (muted) c.muted else c.text)
}

/** Code and values: the mono family at the body size, never smaller (rule 14). */
@Composable
internal fun Mono(text: String, muted: Boolean = false, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Text(text, modifier, style = PullsType.sm.copy(fontFamily = PullsType.mono), color = if (muted) c.muted else c.text)
}

/** A block of code on the paper well, scrolling sideways instead of wrapping. */
@Composable
internal fun CodeBlock(text: String) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(PullsRadius.lg)
    Box(Modifier.fillMaxWidth().clip(shape).background(c.code).border(1.dp, c.borderMute, shape).horizontalScroll(rememberScrollState()).padding(PullsSpace.x3)) {
        Text(text, style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.text, softWrap = false)
    }
}

/** The props of a composable: name and type, and the default under them. */
@Composable
internal fun PropsList(props: List<Prop>) {
    val c = LocalPullsColors.current
    if (props.isEmpty()) {
        BodyText("No props.", muted = true)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        props.forEachIndexed { index, prop ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = PullsSpace.x2),
                verticalArrangement = Arrangement.spacedBy(PullsSpace.x1),
            ) {
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderMute))
                Row(horizontalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
                    Text(prop.name, style = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium), color = c.text)
                    Text(prop.type, Modifier.weight(1f), style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted)
                }
                if (prop.default != null) Text("= ${prop.default}", style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.faint)
            }
        }
    }
}

/** Which themes a page shows: both stacked, or one. */
internal enum class ThemeMode(val label: String) {
    Both("Both"),
    Light("Light"),
    Dark("Dark"),
}

/**
 * A frame that forces the light or the dark palette of the colour theme in use for what is inside it, so a component
 * is seen in both at once without touching the app's look. [label] names the state on the frame.
 */
@Composable
internal fun Frame(dark: Boolean, label: String, content: @Composable () -> Unit) {
    val colors = LocalColorTheme.current.colors(dark)
    val shape = RoundedCornerShape(PullsRadius.card)
    CompositionLocalProvider(LocalPullsColors provides colors) {
        Column(
            Modifier.fillMaxWidth().clip(shape).border(1.dp, colors.border, shape).background(colors.base).padding(PullsSpace.x4),
            verticalArrangement = Arrangement.spacedBy(PullsSpace.x3),
        ) {
            Text("$label, ${if (dark) "dark" else "light"}", style = PullsType.sm, color = colors.muted)
            content()
        }
    }
}

/** [content] in the frames [mode] asks for. */
@Composable
internal fun Themed(mode: ThemeMode, label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
        if (mode != ThemeMode.Dark) Frame(dark = false, label = label, content = content)
        if (mode != ThemeMode.Light) Frame(dark = true, label = label, content = content)
    }
}

/** Wraps [content] and, when [inspect] is on, outlines it and prints the size it rendered at. */
@Composable
internal fun Measured(inspect: Boolean, content: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    Column(verticalArrangement = Arrangement.spacedBy(PullsSpace.x1)) {
        Box(Modifier.onSizeChanged { size = it }.then(if (inspect) Modifier.border(1.dp, c.accentTeal) else Modifier)) { content() }
        if (inspect) Mono(sizeLabel(size.width / density.density, size.height / density.density), muted = true)
    }
}

/** `44 × 44 dp`, with a decimal only where the size has one. */
internal fun sizeLabel(width: Float, height: Float): String = "${dp(width)} × ${dp(height)} dp"

internal fun dp(value: Float): String = if (abs(value - value.roundToInt()) < 0.05f) value.roundToInt().toString() else "%.1f".format(value)

/** Puts [text] on the clipboard. */
internal fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("pulls.review", text))
}
