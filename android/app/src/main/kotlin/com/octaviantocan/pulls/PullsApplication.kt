package com.octaviantocan.pulls

import android.app.Application
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.sound.Haptics
import com.octaviantocan.pulls.sound.Sounds
import com.octaviantocan.pulls.update.UpdateChecks
import com.octaviantocan.pulls.core.clearInterruptedUpdateDownloads

class PullsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // First, so the start time is the process's and a crash anywhere after this is kept for the next launch.
        Analytics.install(this, AppSettings.get(this).usageStats)
        clearInterruptedUpdateDownloads(cacheDir)
        Sounds.install(this, AppSettings.get(this))
        Haptics.install(this, AppSettings.get(this))
        UpdateChecks.sync(this)
        com.octaviantocan.pulls.notifications.DeviceNotifications.sync(this)
    }
}
