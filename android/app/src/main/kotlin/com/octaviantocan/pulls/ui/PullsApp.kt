package com.octaviantocan.pulls.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.core.PrLinks
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.home.CheckoutScreen
import com.octaviantocan.pulls.ui.home.HomeScreen
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
    data class Review(val target: OpenTarget) : Screen
    data object Checkout : Screen
    data object Settings : Screen
}

@Composable
fun PullsApp(initialTarget: String?) {
    val context = LocalContext.current
    val messages = com.octaviantocan.pulls.ui.design.LocalMessages.current
    val stack = remember {
        mutableStateListOf<Screen>(Screen.Home).apply {
            initialTarget?.let(PrLinks::parse)?.let { add(Screen.Review(it)) }
        }
    }
    var updateNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { updateNote = withContext(Dispatchers.IO) { checkUpdate(context) } }
    val nav = remember {
        Nav(
            openTarget = { stack.add(Screen.Review(it)) },
            openRepo = { owner, repo -> stack.add(Screen.Repo(owner, repo)) },
            home = { stack.clear(); stack.add(Screen.Home) },
            settings = { stack.add(Screen.Settings) },
            checkout = { stack.add(Screen.Checkout) },
            back = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
        )
    }
    BackHandler(enabled = stack.size > 1) { nav.back() }
    val settings = com.octaviantocan.pulls.data.AppSettings.get(context)
    val dark = LocalPullsColors.current.dark
    // `KeyboardLayer.vue`'s always-on commands.
    UseCommands {
        val group = messages.t("palette.group.general")
        listOf(
            Command("settings", messages.t("keys.settings"), group, icon = "gear-duotone") { nav.settings() },
            Command("home", messages.t("keys.home"), group, icon = "house-line-duotone") { nav.home() },
            Command("theme", messages.t("keys.theme"), group, icon = "moon-stars-duotone") { settings.updateTheme(if (dark) "light" else "dark") },
        )
    }
    Box(Modifier.fillMaxSize().background(LocalPullsColors.current.base)) {
        when (val current = stack.last()) {
            Screen.Home -> HomeScreen(nav, updateNote)
            is Screen.Repo -> RepoScreen(current.owner, current.repo, nav)
            is Screen.Review -> ReviewScreen(current.target, nav)
            Screen.Checkout -> CheckoutScreen(nav)
            Screen.Settings -> SettingsScreen(nav)
        }
        CommandPalette()
    }
}

internal fun checkUpdate(context: Context): String? = runCatching {
    val config = ServerStore.load(context)
    AppUpdater(context).check(config.url, config.headers(), installedVersionCode(context))?.let { "Update ${it.versionName} is ready. ${it.notes}" }
}.getOrNull()

/** The installed app's version code, so the update banner only shows for a newer build. */
internal fun installedVersionCode(context: Context): Int {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
}
