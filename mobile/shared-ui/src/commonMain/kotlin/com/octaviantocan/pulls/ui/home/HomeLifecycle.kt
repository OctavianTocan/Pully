package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Lifecycle changes are native events; the exact production refresh decisions stay in the shared screen. */
@Composable
internal fun HomeLifecycle(
    onStart: () -> Unit = {},
    onResume: () -> Unit = {},
    onStop: () -> Unit = {},
) {
    val native = LocalHomeScreenServices.current.native
    val start by rememberUpdatedState(onStart)
    val resume by rememberUpdatedState(onResume)
    val stop by rememberUpdatedState(onStop)
    DisposableEffect(native) {
        val subscription = native.observeLifecycle(object : HomeLifecycleObserver {
            override fun started() = start()
            override fun resumed() = resume()
            override fun stopped() = stop()
        })
        onDispose { subscription.cancel() }
    }
}
