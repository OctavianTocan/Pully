package com.octaviantocan.pulls.notifications

import android.content.Context
import com.octaviantocan.pulls.data.Account

/** Device-local subscriptions; never a shared GitHub viewer's personal inbox. */
object DeviceNotifications {
    fun sync(context: Context) {
        Account.load(context)
        CheckNotifications.sync(context)
        AiResults.sync(context)
    }

    /** Invalidate late responses before account or server credentials change. */
    fun reset(context: Context) {
        CheckNotifications.clearForSourceChange(context)
        AiResults.reset(context)
    }
}
