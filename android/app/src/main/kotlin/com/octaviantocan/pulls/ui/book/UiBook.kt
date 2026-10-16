package com.octaviantocan.pulls.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.octaviantocan.pulls.ui.design.SheetSearch
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.design.BlurSwitch
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PageBack
import com.octaviantocan.pulls.ui.design.PageMotion
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.UnderlineTabs
import com.octaviantocan.pulls.ui.design.floatShadow

/**
 * The UI Book: the design system as screens inside the app, built from the same kit and tokens as
 * the screens, so what it shows is what ships. One typed page list (`book.json`) drives the index,
 * the pager at the foot of each page and the coverage check. The page open is `current`; null is the index.
 */
@Composable
internal fun UiBook(onClose: () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val book = remember { BookData.load(context) }
    var current by rememberSaveable { mutableStateOf<String?>(null) }
    val page = book.pages.firstOrNull { it.id == current }
    val back: () -> Unit = { if (current != null) current = null else onClose() }
    // A back swipe on a page moves it with the finger toward the index; on the index it closes the book like any page.
    val bookMotion = remember { PageMotion() }
    PageBack(bookMotion, enabled = current != null) { current = null }
    Column(Modifier.fillMaxSize().background(c.base).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PullsSpace.x4, vertical = PullsSpace.x2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PullsSpace.x3),
        ) {
            FloatIcon(Lu.back, "Back", back)
            Text(page?.title ?: "UI Book", style = PullsType.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1)
        }
        // A page opens from the index and the pager moves through the book as pages, the way they go (rule 105): the
        // index sits before the first page, and each page where the book lists it.
        BlurSwitch(current, Modifier.weight(1f), order = { id -> book.pages.indexOfFirst { it.id == id } }, motion = bookMotion) { id ->
            val shown = book.pages.firstOrNull { it.id == id }
            if (shown == null) {
                BookIndex(book) { current = it }
            }
            else {
                key(shown.id) { PageHost(book, shown) { current = it } }
            }
        }
    }
}

/** The Settings row that opens the book. */
@Composable
internal fun UiBookRow(onClick: () -> Unit) {
    SheetCard {
        SheetRow("UI Book", onClick, first = true, iconless = true, value = { LuIcon(Lu.chev, tint = LocalPullsColors.current.faint, size = 16.dp) })
    }
}

@Composable
private fun BookIndex(book: BookData, open: (String) -> Unit) {
    val c = LocalPullsColors.current
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim()
    val matches = book.pages.filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) || it.group.contains(needle, ignoreCase = true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageColumn {
            BodyText("The kit and the tokens as they ship. Open a page to see every state in light and dark.", muted = true)
            SearchField(query) { query = it }
            matches.groupBy { it.group }.forEach { (group, pages) ->
                SectionTitle(group)
                SheetCard {
                    pages.forEachIndexed { index, item ->
                        SheetRow(item.title, { open(item.id) }, first = index == 0, iconless = true, value = { LuIcon(Lu.chev, tint = c.faint, size = 16.dp) })
                    }
                }
            }
            if (matches.isEmpty()) BodyText("No page matches.", muted = true)
        }
    }
}

/** The book's search: the kit's own sheet search, as every list with a search has (N6). */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    SheetSearch(query, onChange, "Search the book")
}

/** A page, then the pager: the pages before and after it in `book.json`. */
@Composable
private fun PageHost(book: BookData, page: BookPage, open: (String) -> Unit) {
    val component = page.component?.let { book.components[it] }
    val index = book.pages.indexOfFirst { it.id == page.id }
    val previous = book.pages.getOrNull(index - 1)
    val next = book.pages.getOrNull(index + 1)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        if (component != null) ComponentPage(book, page, component) else BookRoute(page.id, book)
        PageColumn {
            SheetCard {
                if (previous != null) SheetRow("Previous: ${previous.title}", { open(previous.id) }, first = true, icon = { LuIcon(Lu.prev, tint = LocalPullsColors.current.text, size = 20.dp) })
                if (next != null) SheetRow("Next: ${next.title}", { open(next.id) }, first = previous == null, icon = { LuIcon(Lu.next, tint = LocalPullsColors.current.text, size = 20.dp) })
            }
        }
    }
}

/**
 * A component page, the way the web book lays it out: the summary, variants as tabs, every state
 * forced in light and dark, an Inspect switch that prints the rendered size, the props and the
 * source read from the Kotlin at build time, usage notes, and a prompt that briefs an agent.
 */
@Composable
private fun ComponentPage(book: BookData, page: BookPage, component: BookComponent) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val previews = remember(component.id) { bookPreviews(component.id) }
    val declarations = book.declarations[component.id].orEmpty()
    var variant by rememberSaveable(component.id) { mutableIntStateOf(component.variants.indexOfFirst { it.isDefault }.coerceAtLeast(0)) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var inspect by rememberSaveable { mutableStateOf(false) }
    val modes = ThemeMode.entries
    val selected = previews.firstOrNull { it.id == component.variants.getOrNull(variant)?.id }
    PageColumn {
        PageTitle(page.title, component.summary)
        if (component.variants.size > 1) UnderlineTabs(component.variants.map { it.title }, variant, { variant = it })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
            CapsuleTabs(modes.map { it.label }, mode, { mode = it }, Modifier.weight(1f))
            PullsSwitch(inspect, { inspect = it }, "Inspect")
            Text("Inspect", style = PullsType.sm, color = LocalPullsColors.current.text)
        }
        selected?.states?.forEach { state ->
            Themed(modes[mode], state.label) { Measured(inspect, state.content) }
        }
        SectionTitle("Props")
        declarations.forEach { declaration ->
            Mono("${declaration.file}:${declaration.line}", muted = true)
            PropsList(declaration.props)
        }
        SectionTitle("Source")
        CodeBlock(declarations.joinToString("\n\n") { it.source })
        SectionTitle("Usage")
        component.usage.forEach { BodyText("- $it") }
        if (component.rules.isNotEmpty()) {
            SectionTitle("Rules")
            component.rules.forEach { n ->
                BodyText("Rule $n: ${book.rules.firstOrNull { it.n == n }?.title.orEmpty()}")
            }
        }
        CtaButton("Copy prompt", { copyToClipboard(context, promptFor(page, component, declarations)); toaster.show("Prompt copied", cue = Cue.Success) }, Modifier.fillMaxWidth())
    }
}

/** The text of Copy prompt: enough for an agent to use the component without restyling it. */
internal fun promptFor(page: BookPage, component: BookComponent, declarations: List<Declaration>): String = buildString {
    val first = declarations.firstOrNull()
    appendLine("Use ${component.composable} (${page.title}) from the Pully Android kit${first?.let { ", ${it.file}:${it.line}" }.orEmpty()}.")
    appendLine(component.summary)
    if (first != null) appendLine("Props: " + first.props.joinToString(", ") { prop -> "${prop.name}: ${prop.type}${prop.default?.let { " = $it" }.orEmpty()}" })
    appendLine("Usage:")
    component.usage.forEach { appendLine("- $it") }
    if (component.rules.isNotEmpty()) appendLine("Follow rules ${component.rules.joinToString(", ")} in android/DESIGN.md.")
    append("Do not restyle it. Pass its parameters, and read colours, sizes and radii from the tokens.")
}
