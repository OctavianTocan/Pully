package com.octaviantocan.pulls.pastui

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle

/**
 * Added to a past version of Pulls by `build-past-ui.py`; never compiled into the app itself.
 *
 * The current app opens a past version with its server address and Access token in the launch intent, so the old
 * copy reads the same server without being set up by hand. A content provider is created before the old app's own
 * code runs, which is the one place a file added from outside can hook in without touching the old sources. It
 * copies the three values into the preferences every version since 0.4.0 reads (`pulls-server`), before the
 * activity is created. Only apps signed with the same key can open that activity (the PAST_UI signature permission
 * build-past-ui.py puts on it), so no other app can point the old copy at another server.
 */
class PastUiBoot : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) = adopt(activity, activity.intent)
            // Before Android 10 there is no pre-create callback; adopting here is late but still sets the next start right.
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (Build.VERSION.SDK_INT < 29) adopt(activity, activity.intent)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        return true
    }

    private fun adopt(context: Context, intent: Intent?) {
        val url = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: return
        context.getSharedPreferences("pulls-server", Context.MODE_PRIVATE).edit()
            .putString("url", url)
            .putString("accessId", intent.getStringExtra(EXTRA_ACCESS_ID).orEmpty())
            .putString("accessSecret", intent.getStringExtra(EXTRA_ACCESS_SECRET).orEmpty())
            .commit()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        // The same names as PastVersions in the current app.
        const val EXTRA_URL = "com.octaviantocan.pulls.past.URL"
        const val EXTRA_ACCESS_ID = "com.octaviantocan.pulls.past.ACCESS_ID"
        const val EXTRA_ACCESS_SECRET = "com.octaviantocan.pulls.past.ACCESS_SECRET"
    }
}
