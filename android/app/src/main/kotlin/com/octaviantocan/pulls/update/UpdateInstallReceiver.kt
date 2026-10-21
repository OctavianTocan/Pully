package com.octaviantocan.pulls.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * The system reports the install we committed. An app that is not the device owner always gets
 * `STATUS_PENDING_USER_ACTION` first: its `EXTRA_INTENT` is the system "Update this app?" dialog,
 * which the resumed update screen starts. The broadcast itself must not depend on permission
 * to launch an activity from the background. No third-party updater is involved.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val past = intent.getBooleanExtra(EXTRA_PAST, false)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val report: (InstallResult) -> Unit = if (past) PastVersions::installed else { result -> Updates.installed(context, sessionId, result) }
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    report(InstallResult.Failed("The installer did not ask for confirmation."))
                    return
                }
                if (past) {
                    report(InstallResult.Confirming)
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { report(InstallResult.Failed("Could not open installer.")) }
                } else Updates.confirm(context, sessionId, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> report(InstallResult.Installed)
            PackageInstaller.STATUS_FAILURE_ABORTED -> report(InstallResult.Cancelled)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                report(InstallResult.Failed(message ?: "Install failed (status $status)"))
            }
        }
    }

    companion object {
        /** Set on installs of a past version, whose result goes to [PastVersions]. */
        const val EXTRA_PAST = "com.octaviantocan.pulls.update.PAST"
    }
}

sealed class InstallResult {
    data object Confirming : InstallResult()
    data object Installed : InstallResult()

    /** The reader said no in the system dialog. */
    data object Cancelled : InstallResult()
    data class Failed(val message: String) : InstallResult()
}
