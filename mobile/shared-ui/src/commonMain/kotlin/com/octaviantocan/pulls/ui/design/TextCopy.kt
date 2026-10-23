package com.octaviantocan.pulls.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.octaviantocan.pulls.ui.home.LocalNativeHomeActions

/** All code/AI copy controls use the same required native clipboard service as Home and Overview. */
@Composable
internal fun rememberTextCopy(): (String) -> Unit {
    val native = LocalNativeHomeActions.current
    return remember(native) { native::copyText }
}
