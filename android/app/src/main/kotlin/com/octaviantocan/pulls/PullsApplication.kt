package com.octaviantocan.pulls

import android.app.Application
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.NativeEntry
import com.octaviantocan.pulls.sound.Haptics
import com.octaviantocan.pulls.sound.Sounds
import com.octaviantocan.pulls.update.UpdateChecks
import com.octaviantocan.pulls.core.clearInterruptedUpdateDownloads
import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.ui.review.ReviewSessionCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PullsApplication : Application() {
    private val reviewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val reviewSessions = ReviewSessionCache(reviewScope) { com.octaviantocan.pulls.ui.review.androidPageCopies(this) }
    val homeScreenData = com.octaviantocan.pulls.ui.home.HomeScreenDataOwner()

    override fun onCreate() {
        super.onCreate()
        RepoIdentity.observeInvalidation {
            // The cache lock revokes old size leases synchronously even if invalidation came off-main.
            com.octaviantocan.pulls.data.PullSizes.clear()
            reviewScope.launch { reviewSessions.clear(); homeScreenData.clear() }
        }
        // First, so the start time is the process's and a crash anywhere after this is kept for the next launch.
        Analytics.install(this, AppSettings.get(this).usageStats, NativeEntry.privateConnection(this))
        clearInterruptedUpdateDownloads(cacheDir)
        Sounds.install(this) { AppSettings.get(this) }
        Haptics.install(this) { AppSettings.get(this) }
        UpdateChecks.sync(this)
        com.octaviantocan.pulls.notifications.DeviceNotifications.sync(this)
    }
}
