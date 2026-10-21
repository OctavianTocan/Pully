package com.octaviantocan.pulls.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import com.octaviantocan.pulls.ui.design.BlurSwitch
import com.octaviantocan.pulls.ui.design.PageMotion

/** A route retains its own scroll/folds/input while another route is above it. */
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
    val here = screens.indexOfLast { it != Screen.Settings }.coerceAtLeast(0)
    val screen = screens.getOrNull(here) ?: Screen.Home
    val page = AppPage(screen, here, navigation.pageKey(screen))
    BlurSwitch(page, modifier, order = { it.depth }, motion = motion) { layer ->
        val eligible = remember(navigation, layer.key) { { navigation.stack.lastOrNull() === layer.screen } }
        CompositionLocalProvider(LocalBackConsumerEligible provides eligible) {
            saved.SaveableStateProvider(layer.key) { content(layer.screen) }
        }
    }
}

private data class AppPage(val screen: Screen, val depth: Int, val key: String)
