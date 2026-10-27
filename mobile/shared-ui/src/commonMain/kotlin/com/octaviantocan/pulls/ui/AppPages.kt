package com.octaviantocan.pulls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.BlurSwitch
import com.octaviantocan.pulls.ui.design.FootBarLayer
import com.octaviantocan.pulls.ui.design.FootBars
import com.octaviantocan.pulls.ui.design.LocalFirstBeside
import com.octaviantocan.pulls.ui.design.LocalFootBars
import com.octaviantocan.pulls.ui.design.LocalPageWidth
import com.octaviantocan.pulls.ui.design.LocalPaneSpan
import com.octaviantocan.pulls.ui.design.PaneSpan
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PageMotion
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.WindowShape
import com.octaviantocan.pulls.ui.design.listPaneWidth
import com.octaviantocan.pulls.ui.design.readableWidth
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.windowShape
import com.octaviantocan.pulls.ui.home.LocalOpenBeside

/**
 * The pages of the route stack. A route retains its own scroll/folds/input while another route is above it. On a phone
 * and a phone held sideways one page shows at a time (a list page in a readable column, rule 207); in a window wide and
 * tall enough, an unfolded foldable, the list and the pull request it opened sit side by side (rule 206). Either way it
 * keeps clear of a camera cutout or navigation bar at the side (rule 209).
 */
@Composable
fun AppPages(navigation: AppNavigation, motion: PageMotion, modifier: Modifier = Modifier, content: @Composable (Screen) -> Unit) {
    val saved = rememberSaveableStateHolder()
    val retained = remember { mutableSetOf<String>() }
    val screens = navigation.stack.toList()
    val keys = screens.map { navigation.pageKey(it) }.toSet()
    DisposableEffect(keys) {
        (retained - keys).forEach(saved::removeState)
        retained.retainAll(keys); retained.addAll(keys)
        navigation.keepKeys(screens)
        onDispose { }
    }
    BoxWithConstraints(modifier.windowInsetsPadding(SideInsets)) {
        if (windowShape(maxWidth, maxHeight) == WindowShape.Unfolded) {
            TwoPanes(navigation, motion, screens, saved, listPaneWidth(maxWidth), maxWidth, content)
            return@BoxWithConstraints
        }
        val here = screens.indexOfLast { it != Screen.Settings }.coerceAtLeast(0)
        val screen = screens.getOrNull(here) ?: Screen.Home
        val page = AppPage(screen, here, navigation.pageKey(screen))
        val width = maxWidth
        BlurSwitch(page, Modifier.fillMaxSize(), order = { it.depth }, motion = motion) { layer ->
            // A pull request lays itself out, so its code can take the whole width while its words stay readable.
            Box(if (layer.screen is Screen.Review) Modifier.fillMaxSize() else Modifier.fillMaxSize().readableWidth()) {
                CompositionLocalProvider(LocalPageWidth provides width) { Layer(navigation, saved, layer.screen, layer.key, content) }
            }
        }
    }
}

private data class AppPage(val screen: Screen, val depth: Int, val key: String)

/** The side insets every page keeps clear of: a camera cutout and a navigation bar at the side of a sideways phone. */
private val SideInsets: WindowInsets
    @Composable get() = WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)

@Composable
private fun Layer(navigation: AppNavigation, saved: SaveableStateHolder, screen: Screen, key: String, content: @Composable (Screen) -> Unit) {
    val eligible = remember(navigation, key) { { navigation.stack.lastOrNull() === screen } }
    CompositionLocalProvider(LocalBackConsumerEligible provides eligible) {
        saved.SaveableStateProvider(key) { content(screen) }
    }
}

/** A page that lists what opens beside it: the Inbox, a repository, Explore. Everything else is a page that opens. */
internal fun Screen.isList(): Boolean = when (this) {
    Screen.Home, Screen.Explore, is Screen.PublicRepo, is Screen.PersonalPulls, is Screen.PersonalRepositories, is Screen.PersonalRepo, is Screen.Repo -> true
    is Screen.Review, Screen.UiBook, Screen.Settings -> false
}

/**
 * The two panes of an unfolded window as a reading of the one stack (`shared-navigation-keeps-one-compose-stack`): the
 * list pane holds the last list page, and the page beside it holds the top of the stack when that opened from it.
 * Nothing open shows a quiet line in its place.
 */
internal fun panesOf(screens: List<Screen>): Pair<Int, Int?> {
    val pages = screens.filter { it != Screen.Settings }
    val list = pages.indexOfLast { it.isList() }.coerceAtLeast(0)
    val top = pages.lastIndex
    return list to top.takeIf { it > list }
}

/**
 * What a tap in the list pane opened, as the stack should hold it: the page beside the list is replaced, not stacked,
 * so Back from it goes to the list rather than through every pull request tapped before (rule 206). [screens] is the
 * stack just after [added] went on top; the pages between the list and it that opened beside the list go.
 */
internal fun replacedBeside(screens: List<Screen>, added: Screen): List<Int> {
    if (screens.lastOrNull() !== added || added == Screen.Settings || added == Screen.UiBook) return emptyList()
    val below = screens.dropLast(1)
    val list = below.indexOfLast { it.isList() }
    if (list < 0) return emptyList()
    return (list + 1 until below.size).filter { !below[it].isList() && below[it] != Screen.Settings }
}

@Composable
private fun TwoPanes(
    navigation: AppNavigation,
    motion: PageMotion,
    screens: List<Screen>,
    saved: SaveableStateHolder,
    listWidth: androidx.compose.ui.unit.Dp,
    width: androidx.compose.ui.unit.Dp,
    content: @Composable (Screen) -> Unit,
) {
    val colors = LocalPullsColors.current
    val pages = screens.filter { it != Screen.Settings }
    val (listAt, besideAt) = panesOf(screens)
    val listScreen = pages.getOrNull(listAt) ?: Screen.Home
    val besideScreen = besideAt?.let { pages[it] }
    // Which pane the last press landed in, so a page opened from the list replaces the one beside it.
    val pressedList = remember { BooleanArray(1) }
    val seen = remember { intArrayOf(screens.size) }
    LaunchedEffect(screens) {
        val top = screens.lastOrNull()
        val added = screens.size > seen[0]
        seen[0] = screens.size
        if (!added || top == null || !pressedList[0]) return@LaunchedEffect
        val gone = replacedBeside(screens, top)
        if (gone.isEmpty()) return@LaunchedEffect
        gone.asReversed().forEach { navigation.stack.removeAt(it) }
        seen[0] = navigation.stack.size
    }
    // Pages the effect above is about to take out from under the top one: until it has, the top already counts as
    // replacing them, so its Back does not flash on for the frame between.
    val replacing = screens.lastOrNull()?.takeIf { pressedList[0] && screens.size > seen[0] }?.let { replacedBeside(screens, it).size } ?: 0
    val listSpan = remember { mutableStateOf<PaneSpan?>(null) }
    val besideSpan = remember { mutableStateOf<PaneSpan?>(null) }
    val listBars = remember { FootBars() }
    val besideBars = remember { FootBars() }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(listWidth).fillMaxHeight().notePress { pressedList[0] = true }.spanInto(listSpan)) {
            val open = (besideScreen as? Screen.Review)?.target
            CompositionLocalProvider(LocalFootBars provides listBars, LocalPageWidth provides listWidth, LocalPaneSpan provides listSpan.value, LocalOpenBeside provides open) {
                val page = PanePage(listScreen, navigation.pageKey(listScreen), listAt)
                BlurSwitch(page, Modifier.fillMaxSize(), order = { it.depth }, motion = motion.takeIf { besideScreen == null }) { layer ->
                    Layer(navigation, saved, layer.screen!!, layer.key, content)
                }
            }
            FootBarLayer(listBars, Modifier.align(Alignment.BottomCenter))
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.borderMute))
        Box(Modifier.weight(1f).fillMaxHeight().notePress { pressedList[0] = false }.spanInto(besideSpan)) {
            CompositionLocalProvider(LocalFootBars provides besideBars, LocalPageWidth provides width - listWidth - 1.dp, LocalPaneSpan provides besideSpan.value) {
                val page = besideScreen?.let { PanePage(it, navigation.pageKey(it), besideAt) } ?: NothingBeside
                BlurSwitch(page, Modifier.fillMaxSize(), order = { it.depth }, motion = motion.takeIf { besideScreen != null }) { layer ->
                    val screen = layer.screen
                    if (screen == null) NothingOpen()
                    // The shown page's depth from the stack as it is now: a page that replaced the one beside the list keeps
                    // the layer it went on with (PanePage compares by key), whose depth counted the page it replaced.
                    else {
                        val depth = if (layer.key == page.key) page.depth - replacing else layer.depth
                        CompositionLocalProvider(LocalFirstBeside provides (depth == listAt + 1)) { Layer(navigation, saved, screen, layer.key, content) }
                    }
                }
            }
            FootBarLayer(besideBars, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** A pane's page, the same page whatever its depth: a pull request that replaced the one under it does not slide again. */
private class PanePage(val screen: Screen?, val key: String, val depth: Int) {
    override fun equals(other: Any?): Boolean = other is PanePage && other.key == key
    override fun hashCode(): Int = key.hashCode()
}

private val NothingBeside = PanePage(null, "beside:none", 0)

/** Keeps [span] on where this pane sits in the window. */
private fun Modifier.spanInto(span: MutableState<PaneSpan?>): Modifier = onGloballyPositioned {
    val next = PaneSpan(it.positionInWindow().x, it.size.width.toFloat())
    val now = span.value
    if (now == null || now.left != next.left || now.width != next.width) span.value = next
}

/** Notes every press in this pane before the page under it sees it, and takes nothing from it. */
private fun Modifier.notePress(onPress: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) onPress()
        }
    }
}

/** The pane beside the list while nothing is open in it: one quiet line saying what goes there (rule 1). */
@Composable
private fun NothingOpen() {
    val colors = LocalPullsColors.current
    Box(Modifier.fillMaxSize().padding(horizontal = PullsSpace.x8), contentAlignment = Alignment.Center) {
        Text(
            t("panes.nothingOpen"),
            style = PullsType.read, color = colors.muted, textAlign = TextAlign.Center,
        )
    }
}
