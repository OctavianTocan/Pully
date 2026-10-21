package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import com.octaviantocan.pulls.data.Account
import com.octaviantocan.pulls.ui.design.ModalReveal
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.SheetTopGap
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.onboarding.ConnectionSetupScreen
import kotlinx.coroutines.launch

/** Failed account discovery is actionable on a fresh install without covering a verified offline account. */
@Composable
internal fun ConnectionSetupGate(onLeave: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ModalReveal(
        Account.needsConnectionHelp, onLeave,
        DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        var settings by remember { mutableStateOf(false) }
        ConnectionSetupScreen(
            checking = Account.checkingConnection,
            problem = Account.connectionProblem,
            onRetry = { scope.launch { checkAccount(context) } },
            onServerSettings = { settings = true },
        )
        if (settings) ServerConnectionSheet(onClose = { settings = false })
    }
}

/** The same Server fields used by Settings, reachable even when the sign-in screen covers navigation. */
@Composable
internal fun ServerConnectionSheet(onClose: () -> Unit) {
    PullsSheet(onClose, t("connection.serverSettings"), expandable = false) {
        Spacer(Modifier.height(SheetTopGap))
        ServerPage()
    }
}
