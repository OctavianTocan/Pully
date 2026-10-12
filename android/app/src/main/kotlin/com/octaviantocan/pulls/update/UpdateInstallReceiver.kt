package com.octaviantocan.pulls.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The system reports the install we committed. An app that is not the device owner always gets
 * `STATUS_PENDING_USER_ACTION` first: its `EXTRA_INTENT` is the system "Update this app?" dialog,
 * which must be started or the install never happens. No third-party updater is involved.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    UpdateBus.publish(InstallResult.Failed("The installer did not ask for confirmation."))
                    return
                }
                UpdateBus.publish(InstallResult.Confirming)
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateBus.publish(InstallResult.Installed)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                UpdateBus.publish(InstallResult.Failed(message ?: "Install failed (status $status)"))
            }
        }
    }
}

sealed class InstallResult {
    data object Confirming : InstallResult()
    data object Installed : InstallResult()
    data class Failed(val message: String) : InstallResult()
}

/** The last install result, as Compose state so the update banner shows it. */
object UpdateBus {
    var last by mutableStateOf<InstallResult?>(null)
        private set

    fun publish(result: InstallResult) {
        last = result
    }
}
