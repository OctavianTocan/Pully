package com.octaviantocan.pulls.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.octaviantocan.pulls.notifications.AiResults
import com.octaviantocan.pulls.notifications.CheckNotifications
import com.octaviantocan.pulls.notifications.DeviceNotifications
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.update.UpdatePrefs

/** Local categories reflect Android's permission and each independently disabled channel. */
@Composable
internal fun NotificationSettings() {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        DeviceNotifications.sync(context)
        revision++
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        DeviceNotifications.sync(context)
        revision++
    }
    fun turn(checks: Boolean, on: Boolean) {
        if (checks) CheckNotifications.setEnabled(context, on) else AiResults.setEnabled(context, on)
        revision++
        val allowed = if (checks) CheckNotifications.allowed(context) else AiResults.allowed(context)
        if (!on || allowed) return
        val systemAllowed = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT >= 33 && !systemAllowed && !UpdatePrefs.askedForNotifications(context)) {
            UpdatePrefs.markAskedForNotifications(context)
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (checks) CheckNotifications.openSettings(context) else AiResults.openSettings(context)
        }
    }
    // revision also refreshes preferences changed outside this sheet or in system settings.
    val checks = remember(revision) { CheckNotifications.enabled(context) && CheckNotifications.allowed(context) }
    val results = remember(revision) { AiResults.enabled(context) && AiResults.allowed(context) }
    Text(
        "Follow a pull request to hear about new check failures. Get results for AI work you start on pull requests or posted diffs on this device. Background checks run about every 15 minutes and Android may delay them.",
        style = PullsType.body,
        color = LocalPullsColors.current.muted,
    )
    SheetCard {
        SheetRow("Check failures", { turn(true, !checks) }, first = true, iconless = true) {
            PullsSwitch(checks, { turn(true, it) }, "Check failures")
        }
        SheetRow("AI results", { turn(false, !results) }, iconless = true) {
            PullsSwitch(results, { turn(false, it) }, "AI results")
        }
    }
}
