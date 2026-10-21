package com.octaviantocan.pulls.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow

/** A resource subscription on this device, independent of the shared server's GitHub identity. */
@Composable
fun ChecksWatchRow(target: OpenTarget.Pull?, canFollow: Boolean = true) {
    if (target == null) return
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val permissionPrefs = remember { context.getSharedPreferences("pully-check-notifications", android.content.Context.MODE_PRIVATE) }
    var watching by remember(target) { mutableStateOf(CheckNotifications.isWatching(context, target)) }
    if (!canFollow && !watching) return
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { watching = CheckNotifications.isWatching(context, target) }
    fun follow() {
        CheckNotifications.watch(context, target)
        watching = true
        toaster.show("Following checks. Background checks may be delayed.")
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && CheckNotifications.allowed(context)) follow()
        else toaster.show("Notifications are off. Enable them in Android settings.")
    }
    fun turn(on: Boolean) {
        if (!on) {
            CheckNotifications.unwatch(context, target)
            watching = false
        } else if (CheckNotifications.allowed(context)) follow()
        else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (permissionPrefs.getBoolean("askedPermission", false)) CheckNotifications.openSettings(context)
            else {
                permissionPrefs.edit().putBoolean("askedPermission", true).apply()
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else CheckNotifications.openSettings(context)
    }
    SheetCard {
        SheetRow("Notify when checks fail", { turn(!watching) }, first = true, iconless = true) {
            PullsSwitch(watching, { turn(it) }, "Notify when checks fail")
        }
    }
}
