package com.octaviantocan.pulls.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow

/**
 * Whether a tapped github.com link opens in Pulls. Android 12 and later send a web link only to the app that owns the
 * site, or to one the reader allowed under the app's Open by default, and Pulls cannot own github.com. So Pulls reads
 * Android's answer for each address it asks for and opens that page; it cannot turn them on itself.
 */
object GithubLinks {
    const val HOST = "github.com"

    /** The addresses Pulls asks for (the manifest's), in the order Android's page lists them. */
    val HOSTS = listOf("github.com", "www.github.com", "prs.octaviantocan.com")

    /** The GitHub app, which takes github.com links before any browser when its own Open supported links is on. */
    const val GITHUB_APP = "com.github.android"

    /** Before Android 12 Android asks which app opens each link, so Pulls is always offered and the row is not needed. */
    val asked: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Pulls opens github.com links: both GitHub addresses are on, and Open supported links is on. */
    fun allowed(context: Context): Boolean = if (asked) state(context).let { it.handling && it.hosts["github.com"] == true && it.hosts["www.github.com"] == true } else true

    /** Android's answer: whether Open supported links is on, and which of [HOSTS] are turned on. */
    data class State(val handling: Boolean, val hosts: Map<String, Boolean>) {
        /** How many of [HOSTS] are on. */
        val on: Int get() = HOSTS.count { hosts[it] == true }
    }

    fun state(context: Context): State = if (asked) stateOnS(context) else State(true, HOSTS.associateWith { true })

    @RequiresApi(Build.VERSION_CODES.S)
    private fun stateOnS(context: Context): State {
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return State(false, emptyMap())
        val user = runCatching { manager.getDomainVerificationUserState(context.packageName) }.getOrNull() ?: return State(false, emptyMap())
        val hosts = HOSTS.associateWith { host ->
            when (user.hostToStateMap[host]) {
                DomainVerificationUserState.DOMAIN_STATE_SELECTED, DomainVerificationUserState.DOMAIN_STATE_VERIFIED -> true
                else -> false
            }
        }
        return State(user.isLinkHandlingAllowed, hosts)
    }

    /** The GitHub app is on the phone, so it may take github.com links before Pulls is asked. */
    fun githubAppInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(GITHUB_APP, 0) }.isSuccess

    /** Pulls' own Open by default page, where Supported web addresses lists github.com. */
    fun openSettings(context: Context) = openByDefault(context, context.packageName)

    /** The GitHub app's Open by default page, where its Open supported links is turned off. */
    fun openGithubAppSettings(context: Context) = openByDefault(context, GITHUB_APP)

    private fun openByDefault(context: Context, pkg: String) {
        val page = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$pkg"))
        runCatching { context.startActivity(page) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) } }
    }
}

/** Android's answer, read again whenever Pulls comes back from its page. */
@Composable
private fun rememberLinkState(): GithubLinks.State {
    val context = LocalContext.current
    var state by remember { mutableStateOf(GithubLinks.state(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = GithubLinks.state(context) }
    return state
}

/**
 * Settings' GitHub links group: one row that says On or Off and opens the GitHub links page. It was a switch that
 * only opened Android's page, and read as off while that page said Open supported links was on, with nothing saying
 * the addresses under it were what was off ("it seems like it's displayed disabled, even though it does say open
 * supported links ... I don't really know what I'm supposed to do here": recording 16 Oct 13:06, 01:37 to 02:06; O2).
 */
@Composable
internal fun GithubLinksGroup(open: () -> Unit) {
    if (!GithubLinks.asked) return
    val c = LocalPullsColors.current
    val state = rememberLinkState()
    val on = state.handling && state.hosts["github.com"] == true && state.hosts["www.github.com"] == true
    Group("GitHub links") {
        SheetCard {
            SheetRow("Open GitHub links in Pulls", open, first = true, iconless = true) {
                Text(if (on) "On" else "Off", style = PullsType.sm, color = c.muted)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
}

/**
 * The GitHub links page: each address Android has on or off, the one button that opens Android's page, what to turn on
 * there, the GitHub app's own page when it is on the phone, and why a link tapped inside a browser stays there (O2, O3).
 */
@Composable
internal fun ColumnScope.GithubLinksPage() {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val state = rememberLinkState()
    val githubApp = remember { GithubLinks.githubAppInstalled(context) }
    Group("Android's answer") {
        SheetCard {
            SheetRow("Open supported links", null, first = true, iconless = true) { OnOff(state.handling) }
            GithubLinks.HOSTS.forEach { host -> SheetRow(host, null, iconless = true) { OnOff(state.hosts[host] == true) } }
        }
    }
    Text(
        "On Android's page, turn on Open supported links, then Supported web addresses, and every address in it.",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = PullsType.read, color = c.muted,
    )
    CtaButton("Open Android's page", { GithubLinks.openSettings(context) }, Modifier.fillMaxWidth().padding(top = 16.dp))
    if (githubApp) Group("The GitHub app") {
        Text(
            "The GitHub app is on this phone and can take github.com links first. Android moves an address to Pulls when you turn it on here; if links still open in the GitHub app, turn Open supported links off on its page.",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), style = PullsType.read, color = c.muted,
        )
        SheetCard {
            SheetRow("Open the GitHub app's page", { GithubLinks.openGithubAppSettings(context) }, first = true, iconless = true) {
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
    Group("In a browser") {
        Text(
            "A link tapped inside Chrome or another browser stays in the browser: Android hands a link to Pulls only when it is opened from another app, such as WhatsApp or Slack. In a browser, share the page to Pulls instead.",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), style = PullsType.read, color = c.muted,
        )
    }
}

@Composable
private fun OnOff(on: Boolean) {
    val c = LocalPullsColors.current
    Text(if (on) "On" else "Off", style = PullsType.sm, color = if (on) c.text else c.muted)
}
