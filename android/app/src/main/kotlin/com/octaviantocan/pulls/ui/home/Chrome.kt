package com.octaviantocan.pulls.ui.home

import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.t
import kotlinx.coroutines.delay

/** The web's UnoCSS breakpoints, from the window width (media queries are viewport-based). */
data class Breakpoints(val widthDp: Int) {
    val sm get() = widthDp >= 640
    val md get() = widthDp >= 768
    val lg get() = widthDp >= 1024
}

@Composable
fun rememberBreakpoints(): Breakpoints = Breakpoints(LocalConfiguration.current.screenWidthDp)

/** Tailwind colors the web rows use for checks and review state (`text-green-600 dark:text-green-400` …). */
object StatusColors {
    fun green(dark: Boolean) = if (dark) Color(0xFF4ADE80) else Color(0xFF16A34A)
    fun red(dark: Boolean) = if (dark) Color(0xFFF87171) else Color(0xFFDC2626)
    val amber500 = Color(0xFFF59E0B)
    fun amberText(dark: Boolean) = if (dark) Color(0xFFFBBF24) else Color(0xFFB45309)
}

/** A non-Phosphor vector (`i-carbon-logo-github`, `i-simple-icons-*`, `i-octicon-*`). */
@Composable
fun DrawableIcon(@DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = LocalPullsColors.current.text, size: Dp = 18.dp, contentDescription: String? = null) {
    Icon(painterResource(id), contentDescription, modifier.size(size), tint = tint)
}

/** `press-tint` + `hover:bg-hover`: the row tints while pressed. */
@Composable
fun Modifier.pressTint(onClick: () -> Unit, role: Role = Role.Button, label: String? = null): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val c = LocalPullsColors.current
    return this
        .background(if (pressed) c.hover else Color.Transparent)
        .clickable(interaction, null, role = role, onClickLabel = label, onClick = onClick)
}

/** `useNow({ interval })`. */
@Composable
fun rememberNow(intervalMs: Long = 60_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** `formatTimeAgo`: `Intl.RelativeTimeFormat(locale, { numeric: 'auto' })` through ICU. */
fun timeAgo(thenMs: Long, nowMs: Long, locale: String): String {
    val (value, unit) = relativeTime(thenMs, nowMs)
    val formatter = RelativeDateTimeFormatter.getInstance(ULocale.forLanguageTag(locale))
    if (Build.VERSION.SDK_INT >= 28) {
        val icuUnit = when (unit) {
            TimeUnit.Year -> RelativeDateTimeFormatter.RelativeDateTimeUnit.YEAR
            TimeUnit.Month -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MONTH
            TimeUnit.Week -> RelativeDateTimeFormatter.RelativeDateTimeUnit.WEEK
            TimeUnit.Day -> RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY
            TimeUnit.Hour -> RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR
            TimeUnit.Minute -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE
            TimeUnit.Second -> RelativeDateTimeFormatter.RelativeDateTimeUnit.SECOND
        }
        return formatter.format(value.toDouble(), icuUnit)
    }
    if (unit == TimeUnit.Second) return formatter.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
    val relUnit = when (unit) {
        TimeUnit.Year -> RelativeDateTimeFormatter.RelativeUnit.YEARS
        TimeUnit.Month -> RelativeDateTimeFormatter.RelativeUnit.MONTHS
        TimeUnit.Week -> RelativeDateTimeFormatter.RelativeUnit.WEEKS
        TimeUnit.Day -> RelativeDateTimeFormatter.RelativeUnit.DAYS
        TimeUnit.Hour -> RelativeDateTimeFormatter.RelativeUnit.HOURS
        else -> RelativeDateTimeFormatter.RelativeUnit.MINUTES
    }
    val direction = if (value < 0) RelativeDateTimeFormatter.Direction.LAST else RelativeDateTimeFormatter.Direction.NEXT
    return formatter.format(kotlin.math.abs(value).toDouble(), direction, relUnit)
}

@Composable
fun timeAgo(thenMs: Long?, nowMs: Long): String {
    if (thenMs == null) return ""
    val locale = AppSettings.get(LocalContext.current).locale
    return remember(thenMs, nowMs / 30_000, locale) { runCatching { timeAgo(thenMs, nowMs, locale) }.getOrDefault("") }
}

fun parseInstant(text: String): Long? = runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()

/** The `+pulls.review` wordmark: `text-lg font-mono`, accent magenta / orange / teal. */
@Composable
fun Wordmark(onClick: () -> Unit) {
    val c = LocalPullsColors.current
    Box(Modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.accentMagenta)) { append("+") }
                append("pulls")
                withStyle(SpanStyle(color = c.accentOrange)) { append(".") }
                withStyle(SpanStyle(color = c.accentTeal)) { append("review") }
            },
            style = PullsType.lg.copy(fontFamily = PullsType.mono),
            color = c.text,
        )
    }
}

/**
 * `AppHeader`: sticky `h-14` bar below the status bar, the wordmark, then `NavControls`
 * with the GitHub repository link. The bottom border shows once the page scrolled 10px.
 */
@Composable
fun AppHeader(nav: Nav, scrolled: Boolean) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.base)
            .bottomBorder(if (scrolled) c.border else Color.Transparent)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(horizontal = if (rememberBreakpoints().sm) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Wordmark(nav.home)
        Spacer(Modifier.weight(1f))
        NavControlsRow(nav) {
            GithubIconButton(t("common.githubRepository")) { uri.openUri("https://github.com/antfu/pulls.review") }
        }
    }
}

@Composable
fun GithubIconButton(label: String, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    Box(
        Modifier.size(TOUCH).clip(androidx.compose.foundation.shape.CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DrawableIcon(R.drawable.ic_carbon_logo_github, tint = c.text.copy(alpha = 0.65f), size = 20.dp, contentDescription = label)
    }
}

fun Modifier.bottomBorder(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    val y = size.height - width.toPx() / 2
    drawLine(color, Offset(0f, y), Offset(size.width, y), width.toPx())
}

fun Modifier.topBorder(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    drawLine(color, Offset(0f, width.toPx() / 2), Offset(size.width, width.toPx() / 2), width.toPx())
}

/** `AppFooter`, which the local home and checkout pages show. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppFooter() {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    val style = PullsType.xs
    @Composable
    fun link(text: String, url: String) = Text(text, Modifier.clickable { uri.openUri(url) }.padding(vertical = 4.dp), style = style, color = c.text)
    @Composable
    fun plain(text: String) = Text(text, Modifier.padding(vertical = 4.dp), style = style, color = c.fade)
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.Center,
    ) {
        Row { plain("made by "); link("@antfu", "https://github.com/antfu") }
        plain("·")
        Row { plain("supported by "); link("▲ Vercel", "https://vercel.com"); plain(" and "); link("sponsors", "https://github.com/sponsors/antfu") }
        plain("·")
        plain("MIT")
        plain("·")
        Text("GitHub", Modifier.clickable { uri.openUri("https://github.com/antfu/pulls.review") }.padding(vertical = 4.dp), style = style, color = c.fade)
    }
}

/** A dropdown list styled like the web's (`border border-base rounded-lg bg-base p-1 shadow-lg`). */
@Composable
fun PullsDropdown(expanded: Boolean, onDismiss: () -> Unit, offset: DpOffset = DpOffset(0.dp, 4.dp), content: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        shape = RoundedCornerShape(8.dp),
        containerColor = c.base,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.border),
    ) {
        Column(Modifier.widthIn(min = 160.dp).padding(horizontal = 4.dp)) { content() }
    }
}

/** One option row: `rounded-md px-2 py-1.5 text-sm coarse:min-h-11`, `color-active` with a check when selected. */
@Composable
fun DropdownOption(text: String, selected: Boolean, onClick: () -> Unit, checkStart: Boolean = false, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = LocalPullsColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TOUCH)
            .clip(RoundedCornerShape(6.dp))
            .pressTint(onClick, role = Role.RadioButton)
            .padding(start = if (checkStart) 4.dp else 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val color = if (selected) c.activeText else c.text
        if (checkStart) Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { if (selected) PhIcon("check-bold", tint = c.activeText, size = 13.dp) }
        Text(text, Modifier.weight(1f, fill = false), style = PullsType.sm, color = color, maxLines = 1, softWrap = false)
        trailing()
        if (!checkStart && selected) { Spacer(Modifier.weight(1f)); PhIcon("check", tint = color, size = 13.dp) }
    }
}

/** `FormSelect`: `border border-base rounded bg-raised px-2.5 text-sm`, a caret, and its option list. */
@Composable
fun SelectField(value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .defaultMinSize(minWidth = 192.dp, minHeight = TOUCH)
                .clip(RoundedCornerShape(4.dp))
                .background(c.raised)
                .border(1.dp, c.border, RoundedCornerShape(4.dp))
                .clickable(role = Role.DropdownList, onClickLabel = label) { open = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(options.firstOrNull { it.first == value }?.second ?: value, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            PhIcon("caret-down", tint = c.fade, size = 16.dp)
        }
        PullsDropdown(open, { open = false }) {
            options.forEach { (key, text) -> DropdownOption(text, key == value, { onChange(key); open = false }, checkStart = true) }
        }
    }
}

/** `DiffPill`: a rounded-full bordered link with a bold label and optional muted extra. */
@Composable
fun DiffPill(parent: String, onClick: () -> Unit, modifier: Modifier = Modifier, extra: String? = null) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .defaultMinSize(minHeight = TOUCH)
            .clip(shape)
            .border(1.dp, c.border, shape)
            .pressTint(onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(parent, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (extra != null) Text(extra, style = PullsType.xs, color = c.fade, maxLines = 1)
    }
}

/** A plain heading-sized icon button used in page headers (`ActionIconButton`). */
@Composable
fun HeaderIcon(icon: String, label: String, onClick: () -> Unit, tint: Color? = null) = IconButton(icon, label, onClick, tint = tint)
