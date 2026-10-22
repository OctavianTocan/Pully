package com.octaviantocan.pulls.browser

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.octaviantocan.pulls.data.AppSettings

/** Local consent only. Android's accessibility grant is always the reader's own separate choice. */
object BrowserHelperSettings {
    private const val PREFS = "pully-browser-helper"
    private const val ALLOWED = "disclosure-accepted"

    fun allowed(context: Context): Boolean = AppSettings.get(context).experiments.browserHelper
        && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ALLOWED, false)

    fun setAllowed(context: Context, allowed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ALLOWED, allowed).apply()
        BrowserRepoService.refresh()
    }

    fun serviceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val component = ComponentName(context, BrowserRepoService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == component
        }
    }
}
