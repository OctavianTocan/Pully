package com.octaviantocan.pulls.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.octaviantocan.pulls.ActiveAccountTheme
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.*
import com.octaviantocan.pulls.sound.Haptics
import com.octaviantocan.pulls.sound.Sounds
import com.octaviantocan.pulls.ui.design.AppFonts
import com.octaviantocan.pulls.ui.design.CodeFonts
import com.octaviantocan.pulls.ui.design.ColorThemes
import com.octaviantocan.pulls.ui.design.LocalColorTheme
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import com.octaviantocan.pulls.ui.personal.GitHubAccountRequest
import com.octaviantocan.pulls.ui.settings.GithubLinksOffer
import com.octaviantocan.pulls.ui.settings.LocalProviderSignInBrowser
import com.octaviantocan.pulls.update.WhatsNew
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.junit.rules.TestName
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

/** Real Android entry/renderers and native mount, with only network/storage/browser/native-resume ports replaced. */
@RunWith(JUnit4::class)
class GitHubWorkspaceEntryTest {
    @get:Rule val testName = TestName()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var fixture: Fixture
    private lateinit var life: EntryLifecycle
    private lateinit var navigation: AppNavigation

    @Before fun openConfirmedEntry() {
        fixture = Fixture(
            holdInitialInbox = testName.methodName == "interruptedFirstInboxRestartsAfterAdmissionAndRejectsTheLateOldReply",
            existingRemoteTask = testName.methodName.startsWith("existingRemoteWork"),
        )
        if (fixture.existingRemoteTask && Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                compose.activity.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        compose.runOnUiThread {
            val context = compose.activity
            NativeWorkspaceBinding.active?.close()
            NativeEntry.select(context, NativeEntryMode.Explore)
            // Each fixture starts without a durable Inbox; a previous test must not bypass its initial read.
            context.getSharedPreferences("pulls-home-source-v2-${fixture.namespace}", 0).edit().clear().apply()
            context.getSharedPreferences("pully-explore", 0).edit().putBoolean("welcomeSeen", true).apply()
            // No system dialogs or unrelated update/network work in this fixture host.
            context.getSharedPreferences("pulls-ui-workspace-${fixture.namespace}", 0).edit()
                .putString("locale", "en").putString("theme", "light").putString("colorTheme", "pulls")
                .putString("appFont", "geist").putString("codeFont", "geist-mono")
                .putBoolean("sounds", true).putBoolean("haptics", true).putBoolean("updateNotifications", false).apply()
            GithubLinksOffer.done(context)
            WhatsNew.markSeen(context)
            life = EntryLifecycle().also { it.registry.currentState = Lifecycle.State.RESUMED }
            navigation = AppNavigation(rootScreen = Screen.Explore)
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides life,
                LocalProviderSignInBrowser provides { _: String -> life.registry.currentState = Lifecycle.State.STARTED },
            ) {
                ActiveAccountTheme {
                    // Probes observe the exact theme and locale supplied to the real production tree.
                    Text("appearance:${LocalPullsColors.current.dark}:${LocalMessages.current.t("common.settings")}:${LocalColorTheme.current.id}")
                    NativeEntry.get(compose.activity).guestSource()?.let { source ->
                    GuestPullsApp(navigation, source, null,
                        GitHubAccountRequest(NativeEntry.get(compose.activity)), onExistingAccount = {},
                        runtime = { _, scope, current, identityChanged ->
                            PersonalAccountRuntime(fixture.origin, fixture.vault, scope, scope, { 1200 }, current,
                                identityChanged, fixture.transport).also { fixture.runtime = it }
                        },
                        // Skip updater/device setup only; the real binding owns global AiWork admission.
                        onPrivateResume = { _, _ -> },
                        publicTransport = RpcTransport { RpcResponse(503, "", "application/json") },
                    )
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("inbox.filter.waiting").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            AppSettings.get(compose.activity).apply {
                updateLocale("en"); updateTheme("light"); updateColorTheme("pulls")
                updateAppFont("geist"); updateCodeFont("geist-mono"); updateSounds(true); updateHaptics(true)
                updateInboxFilter("waiting"); updateUpdateNotifications(false)
            }
        }
    }

    @After fun closeEntry() {
        compose.runOnUiThread {
            if (::fixture.isInitialized && ::life.isInitialized) {
                life.registry.currentState = Lifecycle.State.DESTROYED
                fixture.close()
            }
            NativeWorkspaceBinding.active?.close()
            Commands.close()
        }
    }

    @Test fun confirmedLoginRendersProductionHomeRoutesNativeRepoAndSignOutReturnsToExplore() {
        compose.onNodeWithTag("inbox.filter.waiting").assertIsDisplayed()
        compose.onNodeWithTag("inbox.filter.authored").assertExists()
        lateinit var old: Repo
        compose.runOnIdle {
            assertEquals(Screen.Home, navigation.stack.first())
            val binding = NativeWorkspaceBinding.active!!
            assertEquals("fixture-account-42", binding.owner.accountId)
            old = Repo(compose.activity)
            assertSame(binding.repo, old)
            runBlocking { old.accountStatus() }
            val request = fixture.requests.last { it.url.contains("/workspace/") }
            assertEquals("https://accounts.example/workspace/api/rpc", request.url)
            assertEquals("Bearer ${fixture.token}", request.headers["Authorization"])
            assertFalse(request.headers.containsKey("X-Pully-Session"))
            binding.signOut()
            assertNull(NativeWorkspaceBinding.active)
            assertEquals(listOf(Screen.Explore), navigation.stack.toList())
            val before = fixture.requests.size
            try { runBlocking { old.accountStatus() }; fail("A retired native repository dispatched") }
            catch (_: CancellationException) { }
            assertEquals(before, fixture.requests.size)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("inbox.filter.waiting").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(Screen.Explore, navigation.stack.last()) }
        compose.onNodeWithText("Explore", substring = false).assertIsDisplayed()
    }

    @Test fun homeQueryFilterAndUiBookRouteSurvivePauseWithoutRevivingCapturedTransport() {
        compose.onNodeWithTag("inbox.filter.authored").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("owned query")
        lateinit var binding: NativeWorkspaceBinding
        lateinit var captured: PersonalWorkspace
        compose.runOnIdle {
            binding = NativeWorkspaceBinding.active!!
            captured = binding.workspace
            navigation.nav.uiBook()
        }
        pause()
        compose.onNodeWithText("UI Book").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(binding, NativeWorkspaceBinding.active)
            assertEquals(Screen.UiBook, navigation.stack.last())
            assertFalse(captured.current)
            val before = fixture.requests.size
            try { runBlocking { binding.repo.accountStatus() }; fail("Paused dispatch was admitted") }
            catch (_: CancellationException) { }
            assertEquals(before, fixture.requests.size)
        }
        resume()
        compose.onNodeWithText("UI Book").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(binding, NativeWorkspaceBinding.active)
            assertEquals(Screen.UiBook, navigation.stack.last())
            assertFalse(captured.current)
            assertNotSame(captured, binding.workspace)
            navigation.nav.back()
        }
        compose.onNode(hasSetTextAction()).assertTextContains("owned query")
        compose.onNodeWithTag("inbox.filter.authored").assertIsSelected()
    }

    @Test fun interruptedFirstInboxRestartsAfterAdmissionAndRejectsTheLateOldReply() {
        assertTrue("This fixture must hold the first Inbox response", fixture.holdInitialInbox)
        try { compose.waitUntil(5_000) { fixture.cachedReads.get() == 1 } }
        catch (error: Throwable) { throw AssertionError("Initial Inbox fixture: cached=${fixture.cachedReads.get()}, fresh=${fixture.freshReads.get()}, held=${fixture.holdInitialInbox}", error) }
        compose.onNodeWithTag("inbox.filter.authored").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("owned")
        lateinit var binding: NativeWorkspaceBinding
        lateinit var old: PersonalWorkspace
        var epoch = 0L
        compose.runOnIdle {
            binding = NativeWorkspaceBinding.active!!
            old = binding.workspace
            epoch = RepoIdentity.generation
        }
        pause()
        compose.runOnIdle {
            assertFalse(old.current)
            assertNull(fixture.runtime.workspace())
        }
        resume()
        compose.waitUntil(5_000) { fixture.cachedReads.get() >= 2 && fixture.freshReads.get() >= 1 }
        compose.onNodeWithText("Fresh owned Inbox").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("owned")
        compose.onNodeWithTag("inbox.filter.authored").assertIsSelected()
        compose.runOnIdle {
            assertSame(binding, NativeWorkspaceBinding.active)
            assertEquals(epoch, RepoIdentity.generation)
            assertFalse(old.current)
            fixture.releaseInitialInbox.complete(Unit)
        }
        compose.waitUntil(5_000) { fixture.initialInboxReturned }
        compose.onNodeWithText("Fresh owned Inbox").assertIsDisplayed()
        compose.onNodeWithText("Stale owned Inbox").assertDoesNotExist()
        compose.runOnIdle {
            val stored = compose.activity.getSharedPreferences("pulls-home-source-v2-${fixture.namespace}", 0)
            assertFalse(stored.all.values.any { it.toString().contains("Stale owned Inbox") })
        }
    }

    @Test fun existingRemoteWorkReattachesAfterPauseOnlyWithoutStartingAnotherTask() =
        assertRemoteWorkReattaches(stopped = false)

    @Test fun existingRemoteWorkReattachesAfterStopStartAndAdmittedResumeWithoutStartingAnotherTask() =
        assertRemoteWorkReattaches(stopped = true)

    private fun assertRemoteWorkReattaches(stopped: Boolean) {
        val manager = compose.activity.getSystemService(NotificationManager::class.java)
        fun hasNotice(title: String) = manager.activeNotifications.any {
            it.id == 2 && it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == title
        }
        // Reattach independently of Home's own task-list effect.
        compose.runOnIdle { navigation.nav.uiBook() }
        // This is the real AiWork follower and Android NotificationManager, not a fake callback counter.
        compose.waitUntil(5_000) { hasNotice("Existing remote analysis") }
        pause()
        if (stopped) compose.runOnIdle { life.registry.currentState = Lifecycle.State.CREATED }
        compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == 2 } }
        val reads = fixture.taskReads.get()
        compose.runOnIdle {
            fixture.remoteTaskTitle = "Remote analysis after resume"
            assertNull(fixture.runtime.workspace())
            if (stopped) life.registry.currentState = Lifecycle.State.STARTED
        }
        compose.runOnIdle { assertEquals("ON_START must not dispatch before native workspace admission", reads, fixture.taskReads.get()) }
        resume()
        compose.waitUntil(5_000) { fixture.taskReads.get() > reads && hasNotice("Remote analysis after resume") }
        compose.runOnIdle {
            assertTrue(fixture.requests.none { request ->
                request.url.endsWith("/api/rpc") && Json.parseToJsonElement(request.body).jsonObject["name"]?.jsonPrimitive?.content == "ai-task-start"
            })
            assertTrue(NativeWorkspaceBinding.active!!.workspace.current)
        }
    }

    @Test fun settingsSubpageAndUnsavedServerFormSurviveBrowserPause() {
        settings()
        click("Advanced")
        click("Server")
        compose.onNodeWithContentDescription("Server URL").performTextReplacement("https://draft.example")
        pause(); resume()
        compose.onNode(hasSetTextAction() and hasText("https://draft.example")).assertExists()
        compose.runOnIdle {
            assertEquals(Screen.Settings, navigation.stack.last())
            assertNotEquals("https://draft.example", ServerStore.load(compose.activity).url)
        }
    }

    @Test fun claudePendingPasteSurvivesBrowserAndFinishesThroughResumedAccountTransport() {
        settings(); click("Claude Code"); click("Sign in to Claude Code")
        compose.runOnIdle { assertEquals(Lifecycle.State.STARTED, life.registry.currentState) }
        resume()
        compose.onNodeWithText("Finish signing in").assertExists()
        compose.onNodeWithContentDescription("Code").performTextInput("fixture-code")
        compose.onNodeWithContentDescription("Code").performImeAction()
        compose.waitUntil(5_000) { fixture.finishedClaude }
        compose.onNodeWithText("Signed in", substring = false).assertExists()
        compose.runOnIdle { assertTrue(fixture.runtime.workspace()!!.current) }
    }

    @Test fun codexWaitingCodeSurvivesBrowserAndPollingRestartsAfterResume() {
        settings(); click("Codex"); click("Sign in to Codex")
        compose.onNodeWithText("ABCD-EFGH").assertExists()
        compose.runOnIdle {
            assertEquals(Lifecycle.State.STARTED, life.registry.currentState)
            assertNull(fixture.runtime.workspace())
            fixture.completeCodex = true
        }
        resume()
        compose.waitUntil(5_000) { fixture.finishedCodex }
        compose.onNodeWithText("Signed in", substring = false).assertExists()
    }

    @Test fun reviewSubmissionDraftSurvivesPauseAndTrueSignOutRetiresThePrivateRoute() {
        compose.runOnIdle { navigation.nav.openTarget(OpenTarget.Pull("fixture", "repository", 7)) }
        compose.waitUntil(5_000) { fixture.requests.any { it.body.contains("review-data") } }
        compose.onNodeWithContentDescription("More").assertIsEnabled().performClick()
        click("Leave a comment")
        val reviewInput = hasSetTextAction() and hasAnyAncestor(isDialog())
        try { compose.waitUntil(5_000) { compose.onAllNodes(reviewInput).fetchSemanticsNodes().size == 1 } }
        catch (error: Throwable) { throw AssertionError("Review fixture: inputs=${compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size}, dialogs=${compose.onAllNodes(isDialog()).fetchSemanticsNodes().size}", error) }
        compose.onNode(reviewInput).performTextInput("Unsent review draft")
        pause(); resume()
        compose.onNode(hasSetTextAction() and hasText("Unsent review draft")).assertExists()
        compose.runOnIdle {
            assertTrue(navigation.stack.last() is Screen.Review)
            NativeWorkspaceBinding.active!!.signOut()
            assertNull(NativeWorkspaceBinding.active)
            assertEquals(listOf(Screen.Explore), navigation.stack.toList())
        }
        compose.onNodeWithText("Unsent review draft").assertDoesNotExist()
    }

    @Test fun actualAppearanceAndFeedbackFollowAccountChoicesThenExistingServerPreferences() {
        lateinit var selected: AppSettings
        lateinit var palette: String
        compose.runOnIdle {
            selected = AppSettings.get(compose.activity)
            palette = ColorThemes.all.first { it.id != "pulls" && it.colors(true).dark }.id
            selected.updateColorTheme(palette)
            selected.updateTheme("dark"); selected.updateLocale("es")
            selected.updateAppFont("serif"); selected.updateCodeFont("system")
            selected.updateSounds(false); selected.updateHaptics(false)
        }
        compose.onNodeWithText("appearance:true:Ajustes:$palette").assertExists()
        compose.runOnIdle {
            assertEquals("serif", AppFonts.selected)
            assertEquals("system", CodeFonts.selected)
            assertFalse(Sounds.allowed())
            assertFalse(Haptics.enabled())
            NativeWorkspaceBinding.active!!.close()
            NativeEntry.select(compose.activity, NativeEntryMode.ExistingServer)
            val legacy = AppSettings.get(compose.activity)
            assertNotSame(selected, legacy)
            legacy.updateTheme("light"); legacy.updateLocale("en"); legacy.updateColorTheme("pulls")
            legacy.updateAppFont("geist"); legacy.updateCodeFont("geist-mono"); legacy.updateHaptics(true)
        }
        compose.onNodeWithText("appearance:false:Settings:pulls").assertExists()
        compose.runOnIdle {
            assertEquals("geist", AppFonts.selected)
            assertEquals("geist-mono", CodeFonts.selected)
            assertTrue(Haptics.enabled())
        }
    }

    private fun settings() { compose.runOnIdle { navigation.nav.settings() } }
    private fun click(text: String) {
        val nodes = compose.onAllNodesWithText(text, substring = false)
        val node = nodes[nodes.fetchSemanticsNodes().lastIndex]
        // Settings rows live in a scrolling sheet; a bottom CTA is already laid out outside that scroller.
        runCatching { node.performScrollTo() }
        compose.waitUntil(5_000) { runCatching { node.assertIsEnabled(); true }.getOrDefault(false) }
        node.performClick()
    }
    private fun pause() { compose.runOnIdle { life.registry.currentState = Lifecycle.State.STARTED } }
    private fun resume() {
        compose.runOnIdle { life.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private class EntryLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class MemoryBlob(initial: ByteArray) : PersonalSessionBlobStorage {
        private var bytes: ByteArray? = initial.copyOf()
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(contents: ByteArray) { bytes = contents.copyOf() }
    }

    private class Fixture(val holdInitialInbox: Boolean = false, val existingRemoteTask: Boolean = false) {
        val origin = PersonalAuthOrigin.parse("https://accounts.example")
        val token = "pully_" + "A".repeat(43)
        val namespace = sourceStorageNamespace("${origin.base}/workspace", "fixture-account-42", true)!!
        val vault = PersonalSessionVault(MemoryBlob(
            """{"version":1,"accepted":{"origin":"${origin.base}","token":"$token","accountId":"fixture-account-42","githubId":42,"authority":"privateRepositories"},"pending":[]}""".encodeToByteArray(),
        ))
        val requests = CopyOnWriteArrayList<RpcRequest>()
        lateinit var runtime: PersonalAccountRuntime
        val releaseInitialInbox = CompletableDeferred<Unit>()
        val cachedReads = AtomicInteger()
        val freshReads = AtomicInteger()
        val taskReads = AtomicInteger()
        @Volatile var initialInboxReturned = false
        @Volatile var remoteTaskTitle = "Existing remote analysis"
        fun close() { releaseInitialInbox.complete(Unit); if (this::runtime.isInitialized) runtime.close() }
        private fun inboxRow(title: String) = """{"owner":"fixture","repo":"repository","number":7,"title":"$title","author":"fixture-reader","role":"authored","state":"open","updatedAt":"2026-10-25T12:00:00Z"}"""
        @Volatile var finishedClaude = false
        @Volatile var completeCodex = false
        @Volatile var finishedCodex = false
        val transport = RpcTransport { request ->
            requests += request
            if (request.url.endsWith("/api/personal/v1/session")) RpcResponse(200,
                """{"accountId":"fixture-account-42","githubId":42,"login":"fixture-reader","authority":"privateRepositories","sessionExpiresAt":3600,"accessExpiresAt":null,"refreshAvailable":false}""", "application/json")
            else if (request.url.endsWith("/sign-out")) RpcResponse(200, "true", "application/json")
            else if (request.url.endsWith("/api/events")) RpcResponse(204, "")
            else if (request.url.endsWith("/workspace/api/rpc")) {
                val body = Json.parseToJsonElement(request.body).jsonObject
                val args = body["args"]!!.jsonObject
                val result = when (body["name"]!!.jsonPrimitive.content) {
                    "account-status" -> """{"hasAccount":true}"""
                    "account-me" -> """{"email":"fixture-reader","createdAt":"2026-10-25T00:00:00Z"}"""
                    "my-pulls-cached" -> {
                        val first = cachedReads.incrementAndGet() == 1
                        if (holdInitialInbox && first) {
                            withContext(NonCancellable) { releaseInitialInbox.await() }
                            initialInboxReturned = true
                            """{"pulls":[${inboxRow("Stale owned Inbox")}],"fetchedAt":${System.currentTimeMillis() + 60_000}}"""
                        } else "null"
                    }
                    "my-pulls" -> {
                        freshReads.incrementAndGet()
                        if (holdInitialInbox) "[${inboxRow("Fresh owned Inbox")}]" else "[]"
                    }
                    "ai-task-list-all" -> {
                        taskReads.incrementAndGet()
                        if (existingRemoteTask) """[{"id":"existing-task-1","key":"github:fixture/repository#7","kind":"analyze","status":"running","title":"$remoteTaskTitle","startedAt":${System.currentTimeMillis() - 10_000}}]""" else "[]"
                    }
                    "recent-diffs", "posted-diffs" -> "[]"
                    "account-settings", "account-settings-merge", "cli-models" -> "{}"
                    "github-pr" -> """{"key":"github:fixture/repository#7","diff":{"title":"Fixture pull request","label":"#7","head":{"sha":"fixture-head","ref":"feature"},"base":{"sha":"fixture-base","ref":"main"},"ref":{"kind":"github-pr"},"pullRequest":{"state":"open"},"files":[]}}"""
                    "pr-overview" -> """{"overview":{"id":"PR_fixture_7","number":7,"title":"Fixture pull request","state":"open","head":{"oid":"fixture-head","ref":"feature"},"base":{"oid":"fixture-base","ref":"main"},"viewer":{"canWrite":true},"checks":[],"requestedReviewers":[],"latestReviews":[]}}"""
                    "review-data" -> """{"threads":[],"summaries":[],"viewer":"fixture-reader","canWrite":true}"""
                    "reviewed-get" -> """{"reviewed":[],"changedSinceReviewed":[]}"""
                    "pr-stack", "pr-text", "review-state" -> "null"
                    "ai-login-status" -> """{"account":true,"claude":{"signedIn":$finishedClaude},"codex":{"signedIn":$finishedCodex}}"""
                    "ai-login-start" -> if (args["engine"]?.jsonPrimitive?.content == "claude")
                        """{"url":"https://claude.ai/fixture","paste":true}"""
                        else """{"url":"https://auth.openai.com/codex/device","code":"ABCD-EFGH","paste":false}"""
                    "ai-login-finish" -> {
                        if (args["engine"]?.jsonPrimitive?.content == "claude") {
                            check(args["code"]?.jsonPrimitive?.content == "fixture-code")
                            finishedClaude = true
                        } else finishedCodex = completeCodex
                        """{"signedIn":${finishedClaude || finishedCodex},"pending":${!finishedClaude && !finishedCodex}}"""
                    }
                    else -> "{}"
                }
                RpcResponse(200, """{"result":$result}""", "application/json")
            } else RpcResponse(503, """{"error":"Fixture has no public reads"}""", "application/json")
        }
    }
}
