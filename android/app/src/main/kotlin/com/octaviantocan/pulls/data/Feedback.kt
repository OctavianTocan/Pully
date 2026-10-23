package com.octaviantocan.pulls.data

import android.content.Context
import android.os.Build
import com.octaviantocan.pulls.ui.settings.FeedbackState

/** A report of [text] from this phone, as it is now. */
fun FeedbackReport.Companion.of(context: Context, text: String, screen: String, place: String?, dark: Boolean): FeedbackReport {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    return FeedbackReport(
        text = text,
        version = info.versionName.orEmpty(),
        versionCode = code,
        screen = screen,
        place = place,
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        android = Build.VERSION.RELEASE.orEmpty(),
        sdk = Build.VERSION.SDK_INT,
        theme = if (dark) "dark" else "light",
        locale = AppSettings.get(context).locale,
        sentAt = java.time.Instant.now().toString(),
    )
}

/** Unsaved words survive dismiss/failure and are cleared synchronously on source retirement. */
object FeedbackDraft : FeedbackState()
