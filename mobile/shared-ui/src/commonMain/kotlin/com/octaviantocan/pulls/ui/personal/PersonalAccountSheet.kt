package com.octaviantocan.pulls.ui.personal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.octaviantocan.pulls.data.PersonalAccountRuntime
import com.octaviantocan.pulls.data.PersonalAuthProblem
import com.octaviantocan.pulls.data.PersonalSignInState
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.t

/** Both native hosts own foreground/browser services; account state and presentation are shared. */
@Composable
fun PersonalAccountSheet(
    account: PersonalAccountRuntime,
    onOpenBrowser: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalPullsColors.current
    val scope = rememberCoroutineScope()
    val owner = remember(account) { PersonalAccountSheetOwner(account.openSignIn(scope, onOpenBrowser), onClose) }
    val signIn = owner.signIn
    DisposableEffect(owner) { onDispose { owner.close() } }
    val view by account.view.collectAsState()
    val flow = signIn?.controller?.state?.collectAsState()?.value ?: PersonalSignInState.Idle
    PullsSheet(owner::dismiss, t("personal.title"), expandable = false) {
        Column(verticalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
            when {
                view.account != null -> {
                    Text(t("personal.signedIn", "login" to view.account!!.login), style = PullsType.xl, color = colors.text)
                    Text(t("personal.publicAccount"), style = PullsType.read, color = colors.muted)
                    view.problem?.let { Problem(it) }
                    CtaButton(t("personal.signOut"), account::signOut, Modifier.fillMaxWidth())
                }
                !account.configured -> {
                    Text(t("explore.signInUnavailable"), style = PullsType.read, color = colors.muted)
                }
                view.checking -> RowsSkeleton(rows = 2)
                view.saved -> {
                    view.problem?.let { Problem(it) }
                    CtaButton(t("personal.tryAgain"), account::restore, Modifier.fillMaxWidth())
                    PillButton(t("personal.signOut"), account::signOut)
                }
                flow is PersonalSignInState.Starting || flow is PersonalSignInState.Finishing -> RowsSkeleton(rows = 2)
                flow is PersonalSignInState.Waiting -> {
                    Text(t("personal.finishOnGitHub"), style = PullsType.read, color = colors.text)
                    (flow as PersonalSignInState.Waiting).problem?.let {
                        Problem(it)
                        CtaButton(t("personal.tryAgain"), { signIn?.controller?.retry() }, Modifier.fillMaxWidth())
                    }
                    PillButton(t("personal.cancel"), { signIn?.controller?.cancel() })
                }
                else -> {
                    val problem = (flow as? PersonalSignInState.Failed)?.problem ?: view.problem
                    if (problem != null) Problem(problem)
                    else Text(t("personal.intro"), style = PullsType.read, color = colors.muted)
                    CtaButton(t("personal.continueGitHub"), {
                        if (!account.view.value.saved && !account.view.value.checking) signIn?.controller?.start()
                    }, Modifier.fillMaxWidth(), enabled = signIn != null)
                }
            }
            PillButton(t("explore.browse"), owner::dismiss)
        }
    }
}

@Composable
private fun Problem(problem: PersonalAuthProblem) {
    Text(t("personal.problems.${problem.name}"), style = PullsType.read, color = LocalPullsColors.current.error)
}
