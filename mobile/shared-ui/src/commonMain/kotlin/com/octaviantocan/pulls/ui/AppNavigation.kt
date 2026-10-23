package com.octaviantocan.pulls.ui

import androidx.compose.runtime.mutableStateListOf

/** The existing Compose route stack; native back adapters invoke [nav], never own a second stack. */
class AppNavigation(initialScreen: Screen? = null, rootScreen: Screen = Screen.Home) {
    private var homeScreen = rootScreen
    private var nextKey = 0L
    private val routeKeys = mutableListOf<Pair<Screen, String>>()
    internal fun pageKey(screen: Screen): String = routeKeys.firstOrNull { it.first === screen }?.second
        ?: "page:${++nextKey}".also { routeKeys += screen to it }
    internal fun keepKeys(screens: List<Screen>) { routeKeys.removeAll { pair -> screens.none { it === pair.first } } }

    val stack = mutableStateListOf<Screen>(rootScreen).apply { initialScreen?.let(::add) }
    fun reset(rootScreen: Screen) {
        homeScreen = rootScreen
        stack.clear()
        stack.add(rootScreen)
    }
    val nav = Nav(
        openTarget = { stack.add(Screen.Review(it)) },
        openTour = { stack.add(Screen.Review(it, tour = true)) },
        openFrom = { target, from, tour -> stack.add(Screen.Review(target, tour = tour, from = from)) },
        openPlace = { target, from, place -> stack.add(Screen.Review(target, from = from, place = PlaceRequest(place))) },
        openRepo = { owner, repo -> stack.add(Screen.Repo(owner, repo)) },
        openRepoTours = { owner, repo -> stack.add(Screen.Repo(owner, repo, tours = true)) },
        home = { reset(homeScreen) },
        settings = { if (stack.last() != Screen.Settings) stack.add(Screen.Settings) },
        uiBook = { if (stack.last() != Screen.UiBook) stack.add(Screen.UiBook) },
        back = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
    )
}
