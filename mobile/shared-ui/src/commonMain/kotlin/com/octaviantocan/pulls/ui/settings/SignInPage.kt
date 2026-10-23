package com.octaviantocan.pulls.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Native account effects; page layout, private input state and request ownership stay shared. */
interface SignInServices {
    val serverLabel: String
    /** Null unless both the configured server and native Google registration support sign-in. */
    val googleClientId: String?
    suspend fun password(email: String, password: String)
    suspend fun google(clientId: String)
    /** Retires this page's pending native selection/commit ownership, without signing the account out. */
    fun close()
}

enum class SignInMark { Pully, Google }

/** The original sign-in page. A new services owner also owns new, unsaved credential input. */
@Composable
fun SignInPage(services: SignInServices, artwork: @Composable (SignInMark, Color, Dp) -> Unit, onServerSettings: (() -> Unit)? = null, onExplore: (() -> Unit)? = null) {
    key(services) {
        DisposableEffect(services) { onDispose { services.close() } }
        SignInContent(services, artwork, onServerSettings, onExplore)
    }
}

@Composable
private fun SignInContent(services: SignInServices, artwork: @Composable (SignInMark, Color, Dp) -> Unit, onServerSettings: (() -> Unit)?, onExplore: (() -> Unit)?) {
    val c = LocalPullsColors.current
    val scope = rememberCoroutineScope()
    val motion = rememberMotionEnabled()
    val server = services.serverLabel
    val googleClientId = services.googleClientId
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<SignInWay?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val ready = busy == null && email.isNotBlank() && (!asked || password.isNotEmpty())
    fun signIn(way: SignInWay, attempt: suspend () -> Unit) {
        if (busy != null) return
        busy = way
        problem = null
        scope.launch {
            try {
                attempt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = (e as? com.octaviantocan.pulls.data.ServerError)?.message ?: "Pully could not sign in. Try again or check Server settings."
            } finally { busy = null }
        }
    }
    fun next() {
        if (!ready) return
        if (!asked) {
            asked = true
            return
        }
        signIn(SignInWay.Password) { services.password(email.trim(), password) }
    }
    fun google(clientId: String) = signIn(SignInWay.Google) {
        // Closing Google's account sheet is not a failure: the page stays as it was.
        services.google(clientId)
    }
    Column(
        Modifier.fillMaxSize().background(c.base).padding(top = headerTopPadding()).imePadding().navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        // Pully's own mark over the heading, as Zillow's sits over its welcome.
        artwork(SignInMark.Pully, c.text, 40.dp)
        Text("Sign in to Pully", Modifier.padding(top = 24.dp), style = PullsType.xxl, color = c.text)
        if (server.isNotBlank()) Text("Your account on $server.", Modifier.padding(top = 8.dp), style = PullsType.read, color = c.muted)
        Spacer(Modifier.weight(1f))
        if (googleClientId != null) {
            CtaButton(
                if (busy == SignInWay.Google) "Signing in…" else "Continue with Google", { google(googleClientId) },
                Modifier.fillMaxWidth(), enabled = busy == null,
                leading = { artwork(SignInMark.Google, c.onCta, 18.dp) },
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(c.borderMute))
                Text("or", Modifier.padding(horizontal = 12.dp), style = PullsType.sm, color = c.faint)
                Box(Modifier.weight(1f).height(1.dp).background(c.borderMute))
            }
        }
        SheetCard {
            SheetField(
                "Email", email, { email = it }, placeholder = "you@example.com", first = true, keyboardType = KeyboardType.Email,
                imeAction = if (asked) ImeAction.Next else ImeAction.Go, onGo = ::next,
            )
            AnimatedVisibility(
                asked,
                enter = if (motion) fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + expandVertically(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) else EnterTransition.None,
                exit = ExitTransition.None,
            ) {
                SheetField(
                    "Password", password, { password = it }, secret = !shown, keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, onGo = ::next,
                    trailing = {
                        Text(
                            if (shown) "Hide" else "Show",
                            Modifier.cueClickable(role = Role.Button) { shown = !shown }.padding(horizontal = 8.dp, vertical = 10.dp),
                            style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText,
                        )
                    },
                )
            }
        }
        problem?.let { Text(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = PullsType.sm, color = c.error) }
        val label = when {
            busy == SignInWay.Password -> "Signing in…"
            asked -> "Sign in"
            else -> "Continue"
        }
        // With Google on the page, Google is the one main action and the email's button is the quiet one.
        if (googleClientId != null) {
            CtaButton(label, ::next, Modifier.fillMaxWidth().padding(top = 12.dp), container = c.paper2, contentColor = c.text, enabled = ready)
        } else {
            CtaButton(label, ::next, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = ready)
        }
        onServerSettings?.let { open ->
            CtaButton(com.octaviantocan.pulls.ui.design.t("connection.serverSettings"), open, Modifier.fillMaxWidth().padding(top = com.octaviantocan.pulls.ui.design.PullsSpace.x3), container = c.paper2, contentColor = c.text, enabled = busy == null)
        }
        onExplore?.let { open ->
            com.octaviantocan.pulls.ui.design.PillButton(com.octaviantocan.pulls.ui.design.t("explore.browse"), open, Modifier.fillMaxWidth().padding(top = com.octaviantocan.pulls.ui.design.PullsSpace.x3))
        }
    }
}

/** Which way the page is signing in, so only that button says "Signing in…". */
private enum class SignInWay { Password, Google }

