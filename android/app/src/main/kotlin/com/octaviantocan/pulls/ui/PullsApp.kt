package com.octaviantocan.pulls.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.ui.book.UiBook
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.ToastHost
import com.octaviantocan.pulls.ui.design.Toaster
import com.octaviantocan.pulls.ui.home.CheckoutScreen
import com.octaviantocan.pulls.ui.home.HomeScreen
import com.octaviantocan.pulls.ui.home.HomeUi
import com.octaviantocan.pulls.ui.home.RepoScreen
import com.octaviantocan.pulls.ui.review.ReviewScreen
import com.octaviantocan.pulls.ui.settings.SettingsScreen
import com.octaviantocan.pulls.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The web routes: `/` home, `/:owner/:repo` a repo's PRs, a diff page, `/checkout`, and the settings modal as a screen. */
internal sealed interface Screen {
    data object Home : Screen
    data class Repo(val owner: String, val repo: String) : Screen
    /** [tour] opens the pull request on its tour instead of its overview. */
    data class Review(val target: OpenTarget, val tour: Boolean = false) : Screen
    data object Checkout : Screen
    data object Settings : Screen
    data object UiBook : Screen
}

@Composable
fun PullsApp(initialTarget: String?) {
    val context = LocalContext.current
    val messages = com.octaviantocan.pulls.ui.design.LocalMessages.current
    val stack = remember {
        mutableStateListOf<Screen>(Screen.Home).apply {
            // Same parsing as the home URL field: PRs, commits, compares, and repository links.
            when (val link = initialTarget?.let(com.octaviantocan.pulls.ui.home.HomeLinks::parse)) {
                is com.octaviantocan.pulls.ui.home.HomeLink.Target -> add(Screen.Review(link.target))
                is com.octaviantocan.pulls.ui.home.HomeLink.Repo -> add(Screen.Repo(link.owner, link.repo))
                null -> {}
            }
        }
    }
    var updateNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { updateNote = withContext(Dispatchers.IO) { checkUpdate(context) } }
    val nav = remember {
        Nav(
            openTarget = { stack.add(Screen.Review(it)) },
            openTour = { stack.add(Screen.Review(it, tour = true)) },
            openRepo = { owner, repo -> stack.add(Screen.Repo(owner, repo)) },
            home = { stack.clear(); stack.add(Screen.Home) },
            settings = { if (stack.last() != Screen.Settings) stack.add(Screen.Settings) },
            uiBook = { if (stack.last() != Screen.UiBook) stack.add(Screen.UiBook) },
            checkout = { stack.add(Screen.Checkout) },
            back = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
        )
    }
    BackHandler(enabled = stack.size > 1) { nav.back() }
    val settings = com.octaviantocan.pulls.data.AppSettings.get(context)
    // Every code view reads `PullsType.mono`, which follows this.
    if (com.octaviantocan.pulls.ui.design.CodeFonts.selected != settings.codeFont) com.octaviantocan.pulls.ui.design.CodeFonts.selected = settings.codeFont
    val dark = LocalPullsColors.current.dark
    // The inbox's tab, chip and search live above the screens, so they are still there after a review.
    val homeUi = remember { HomeUi() }
    val toaster = remember { Toaster() }
    // `KeyboardLayer.vue`'s always-on commands.
    UseCommands {
        val group = messages.t("palette.group.general")
        listOf(
            Command("settings", messages.t("keys.settings"), group, icon = "gear-duotone") { nav.settings() },
            Command("home", messages.t("keys.home"), group, icon = "house-line-duotone") { nav.home() },
            Command("theme", messages.t("keys.theme"), group, icon = "moon-stars-duotone") { settings.updateTheme(if (dark) "light" else "dark") },
        )
    }
    CompositionLocalProvider(LocalToaster provides toaster) {
        Box(Modifier.fillMaxSize().background(LocalPullsColors.current.base)) {
            // Settings is a modal over the page it was opened from, as on the web.
            when (val current = stack.lastOrNull { it != Screen.Settings } ?: Screen.Home) {
                Screen.Home -> HomeScreen(nav, updateNote, homeUi)
                is Screen.Repo -> RepoScreen(current.owner, current.repo, nav)
                is Screen.Review -> ReviewScreen(current.target, nav, startOnTour = current.tour)
                Screen.Checkout -> CheckoutScreen(nav)
                Screen.UiBook -> UiBook(nav.back)
                Screen.Settings -> {}
            }
            if (stack.last() == Screen.Settings) SettingsScreen(nav)
            CommandPalette()
            ToastHost(toaster)
        }
    }
}

internal fun checkUpdate(context: Context): String? = runCatching {
    val config = ServerStore.load(context)
    AppUpdater(context).check(config.url, config.headers(), installedVersionCode(context))?.let { "Update ${it.versionName} is ready. ${it.notes}" }
}.onFailure { android.util.Log.w("pulls", "update check failed", it) }.getOrNull()

/** The installed app's version code, so the update banner only shows for a newer build. */
internal fun installedVersionCode(context: Context): Int {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
}
