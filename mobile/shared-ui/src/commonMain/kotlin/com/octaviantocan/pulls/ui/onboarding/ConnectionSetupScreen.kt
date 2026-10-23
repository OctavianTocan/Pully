package com.octaviantocan.pulls.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.octaviantocan.pulls.data.ConnectionProblem
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.t

/** Current private-server setup/recovery; hosts replace it with Explore when public browsing is available. */
@Composable
fun ConnectionSetupScreen(checking: Boolean, problem: ConnectionProblem?, onRetry: () -> Unit, onServerSettings: () -> Unit, onExplore: (() -> Unit)? = null) {
    val colors = LocalPullsColors.current
    Column(
        Modifier.fillMaxSize().background(colors.base).padding(top = headerTopPadding()).navigationBarsPadding()
            .padding(horizontal = PullsSpace.x4, vertical = PullsSpace.x6),
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(t("connection.welcome"), style = PullsType.xxl, color = colors.text)
            Text(t("connection.intro"), Modifier.padding(top = PullsSpace.x3), style = PullsType.read, color = colors.muted)
            Text(
                if (checking) t("connection.checking") else t("connection.problem.${problem?.name ?: "FirstConnection"}"),
                Modifier.padding(top = PullsSpace.x8), style = PullsType.read, color = colors.muted,
            )
            Spacer(Modifier.height(PullsSpace.x6))
        }
        CtaButton(t("connection.serverSettings"), onServerSettings, Modifier.fillMaxWidth())
        CtaButton(
            t(if (checking) "connection.checking" else "common.retry"), onRetry,
            Modifier.fillMaxWidth().padding(top = PullsSpace.x3), container = colors.paper2, contentColor = colors.text, enabled = !checking,
        )
        if (onExplore != null) PillButton(t("explore.browse"), onExplore, Modifier.fillMaxWidth().padding(top = PullsSpace.x3))
    }
}
