package com.octaviantocan.pulls.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow

/**
 * Whether a tapped github.com link opens in Pulls. Android 12 and later send a web link only to the app that owns the
 * site, or to one the reader allowed under the app's Open by default, and Pulls cannot own github.com. So the switch
 * reads Android's answer and opens that page; Pulls cannot turn it on itself.
 */
object GithubLinks {
    const val HOST = "github.com"

    /** Before Android 12 Android asks which app opens each link, so Pulls is always offered and the row is not needed. */
    val asked: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun allowed(context: Context): Boolean = if (asked) allowedOnS(context) else true

    @RequiresApi(Build.VERSION_CODES.S)
    private fun allowedOnS(context: Context): Boolean {
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return false
        val state = runCatching { manager.getDomainVerificationUserState(context.packageName) }.getOrNull() ?: return false
        if (!state.isLinkHandlingAllowed) return false
        return when (state.hostToStateMap[HOST]) {
            DomainVerificationUserState.DOMAIN_STATE_SELECTED, DomainVerificationUserState.DOMAIN_STATE_VERIFIED -> true
            else -> false
        }
    }

    /** Pulls' own Open by default page, where Add link lists github.com. */
    fun openSettings(context: Context) {
        val page = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(page) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) } }
    }
}

/** Settings' GitHub links group: one switch that shows Android's answer, re-read whenever Pulls comes back. */
@Composable
internal fun GithubLinksGroup() {
    if (!GithubLinks.asked) return
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(GithubLinks.allowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = GithubLinks.allowed(context) }
    val label = "Open GitHub links in Pulls"
    Group("GitHub links") {
        SheetCard {
            SheetRow(label, { GithubLinks.openSettings(context) }, first = true, iconless = true) {
                PullsSwitch(allowed, { GithubLinks.openSettings(context) }, label)
            }
        }
    }
}
