package com.octaviantocan.pulls.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Direction
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.Material
import com.octaviantocan.pulls.sound.Sounds
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.sound.directionOf
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.book.UiBookRow
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.EngineMark
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Messages
import com.octaviantocan.pulls.ui.design.PullsPagedSheet
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.ThemeWipe
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.review.CodeFontPage
import com.octaviantocan.pulls.ui.review.EffortPage
import com.octaviantocan.pulls.ui.review.ModelCatalogs
import com.octaviantocan.pulls.ui.review.fetchCatalog
import com.octaviantocan.pulls.ui.review.ModelPage
import com.octaviantocan.pulls.ui.review.analysisModelLabel
import com.octaviantocan.pulls.ui.update.ChangelogEntries
import com.octaviantocan.pulls.update.AppVersion
import com.octaviantocan.pulls.update.UpdateChecks
import com.octaviantocan.pulls.update.UpdateNotice
import com.octaviantocan.pulls.update.UpdatePrefs
import com.octaviantocan.pulls.update.WhatsNew
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Per-engine model memory, so switching engines back restores the model (the web keeps `claudeCodeModel` and `codexModel`). */
internal class EngineModels(context: Context) {
    private val prefs = context.getSharedPreferences("pulls-settings-ui", Context.MODE_PRIVATE)
    fun model(engine: String): String? = prefs.getString("model:$engine", null)
    fun setModel(engine: String, id: String) = prefs.edit().putString("model:$engine", id).apply()
}

@Composable
internal fun SettingsPage.title(): String = title(AccountUi.opened?.name)

@Composable
fun SettingsScreen(nav: Nav) = SettingsScreen(nav, rememberAndroidSettingsServices())

@Composable
internal fun ColumnScope.SettingsPageContent(nav: Nav, page: SettingsPage, onPage: (SettingsPage) -> Unit) =
    SettingsPageContent(nav, page, rememberAndroidSettingsServices(), onPage)

/**
 * The installed version, which opens what each version changed, and the switch for the notification the background
 * check sends when a new one is out. The switch is on only when Android lets Pully notify too; turning it on asks, and
 * when Android will not ask again it opens the app's notification settings instead.
 */
@Composable
internal fun UpdatesGroup(settings: AppSettings, open: (SettingsPage) -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(UpdateNotice.allowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = UpdateNotice.allowed(context) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = UpdateNotice.allowed(context)
        UpdateChecks.sync(context)
    }
    fun turn(on: Boolean) {
        settings.updateUpdateNotifications(on)
        UpdateChecks.sync(context)
        if (!on || allowed) return
        if (Build.VERSION.SDK_INT >= 33 && !UpdatePrefs.askedForNotifications(context)) {
            UpdatePrefs.markAskedForNotifications(context)
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        }
    }
    val on = settings.updateNotifications && allowed
    Group("Updates") {
        SheetCard {
            SheetRow("What's new", { open(SettingsPage.WhatsNew) }, first = true, iconless = true) {
                Text(AppVersion.name(context), style = PullsType.sm, color = c.muted)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
            SheetRow("Update notifications", { turn(!on) }, iconless = true) {
                PullsSwitch(on, { turn(it) }, "Update notifications")
            }
        }
    }
}

