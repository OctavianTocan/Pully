package com.octaviantocan.pulls.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.AppNavigation
import com.octaviantocan.pulls.ui.AppPages
import com.octaviantocan.pulls.ui.LocalSharedBackDispatcher
import com.octaviantocan.pulls.ui.SharedBackDispatcher
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.design.*
import com.octaviantocan.pulls.ui.home.*

/** Actual Home/repository screens and one shared Compose stack; fixtures are explicitly labelled. */
@Composable
fun PullyPreview(services: HomeScreenServices, limitation: HomePreviewFeature?, clearLimitation: () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkPullsColors else LightPullsColors
    val messages = rememberMessages(services.settings.locale)
    val navigation = remember { AppNavigation() }
    val homeUi = remember { HomeUi(services.settings.inboxFilter) }
    val motion = remember { PageMotion() }
    val backs = remember { SharedBackDispatcher() }
    val toaster = remember { Toaster() }
    // Controller/source lifetime owns caches; entering/leaving AppPages routes does not revoke them.
    DisposableEffect(services.data) { onDispose { services.data.invalidate(); services.home.invalidate() } }
    PullsMaterialTheme(colors, messages) {
        CompositionLocalProvider(LocalToaster provides toaster, LocalSharedBackDispatcher provides backs) {
            HomeBackEffect(services.native, motion, backs, enabled = navigation.stack.size > 1) { navigation.nav.back() }
            Box(Modifier.fillMaxSize().background(colors.base)) {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = PullsSpace.x4, vertical = PullsSpace.x2), verticalArrangement = Arrangement.spacedBy(PullsSpace.x1)) {
                        Text(t("mobilePreview.title"), Modifier.semantics { heading() }, style = PullsType.lg, color = colors.text)
                        Text(t("mobilePreview.fixtureData"), style = PullsType.sm, color = colors.muted)
                    }
                    AppPages(navigation, motion, Modifier.weight(1f).fillMaxWidth()) { screen ->
                        when (screen) {
                            Screen.Home -> HomeScreen(navigation.nav, homeUi, services)
                            is Screen.Repo -> RepoScreen(screen.owner, screen.repo, navigation.nav, screen.tours, services)
                            else -> error("An unsupported internal-preview route passed its capability boundary.")
                        }
                    }
                }
                if (limitation != null) PullsSheet(clearLimitation, t("mobilePreview.limitationTitle"), expandable = false) {
                    Text(t("mobilePreview.limitationBody"), Modifier.padding(top = SheetTopGap), style = PullsType.row, color = colors.text)
                }
                ToastHost(toaster)
            }
        }
    }
}
