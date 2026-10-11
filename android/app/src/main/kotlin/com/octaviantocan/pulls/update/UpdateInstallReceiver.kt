package com.octaviantocan.pulls.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** The system reports the install we committed. No third-party updater is involved. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        UpdateBus.publish(if (status == PackageInstaller.STATUS_SUCCESS) InstallResult.Installed else InstallResult.Failed(message ?: "Install failed"))
    }
}

sealed class InstallResult {
    data object Installed : InstallResult()
    data class Failed(val message: String) : InstallResult()
}

object UpdateBus {
    @Volatile var last: InstallResult? = null
        private set

    fun publish(result: InstallResult) {
        last = result
    }
}
