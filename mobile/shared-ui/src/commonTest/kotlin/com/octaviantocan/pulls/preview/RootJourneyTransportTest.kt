package com.octaviantocan.pulls.preview

import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.*
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.MemorySettingsPreferences
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.ui.review.*
import com.octaviantocan.pulls.ui.settings.*
import com.octaviantocan.pulls.ui.settings.AccountConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RootJourneyTransportTest {
    @Test
    fun actualAccountClientAndSessionParseTheSameFixtureUsedByTheNativeRoot() = runTest {
        val storage = SampleAccountStorage()
        val requests = mutableListOf<RpcRequest>()
        val fixture = RootJourneyTransport()
        val transport = RpcTransport { request -> requests += request; fixture.execute(request) }
        var invalidations = 0
        val runtime = AccountRuntime(storage, transport, backgroundScope, { "" }, { invalidations++ }, {}, RpcObserver { _, _, _, _ -> })
        runtime.start(); runCurrent()
        assertEquals(AccountPhase.SignIn, runtime.state.phase)
        val signIn = runtime.signInServices()
        signIn.password(RootJourneyTransport.EMAIL, RootJourneyTransport.PASSWORD)
        runCurrent()
        assertEquals(AccountPhase.Ready, runtime.state.phase)
        assertEquals(RootJourneyTransport.EMAIL, runtime.state.email)
        assertTrue(invalidations > 0)
        assertTrue(runtime.repo.myPulls("open").any { it.number == 76 })
        val theme = mapOf("theme" to SyncedSetting(JsonPrimitive("dark"), 100L))
        assertEquals(theme, runtime.repo.mergeAccountSettings(theme))
        val cache = ReviewSessionCache(backgroundScope)
        val settings = AppSettings(MemorySettingsPreferences(), object : SettingsEffects {
            override fun touched(name: String) = Unit
            override fun settingsChanged(key: String, value: String) = Unit
            override fun usageStatsEnabled(enabled: Boolean) = Unit
        }) { "en" }
        val target = OpenTarget.Pull("pully", "mobile-fixtures", 76, "github.com")
        val session = ReviewSession(target, runtime.repo, backgroundScope, settings, cache = cache, effects = NoSessionEffects)
        session.load(); runCurrent()
        assertNull(session.loadError)
        val opened = assertNotNull(session.opened)
        assertEquals(RootJourneyTransport.KEY, opened.key)
        assertEquals("fixture-76", opened.headSha)
        assertEquals("github-pr", opened.refKind)
        val file = session.files.single()
        assertEquals("src/Review.kt", file.path)
        assertEquals(listOf("fun review() = \"android\"", "fun review() = \"shared\""), file.patchLines.map { it.text })
        val overview = assertNotNull(parsePrOverviewData(session.overview, fresh = true, expectedHead = opened.headSha))
        assertEquals("Actual shared review fixture description. This is sample data.", overview.overview.body)
        assertTrue(requests.any { it.methodName() == "pr-text" })
        assertFalse(session.canWrite)
        session.detach(); cache.clear(); signIn.close(); runtime.close()
    }

    @Test
    fun restoredSampleCredentialsUseTheRealRuntimeVerificationPath() = runTest {
        val storage = SampleAccountStorage(restored = true)
        val requests = mutableListOf<RpcRequest>()
        val fixture = RootJourneyTransport()
        val runtime = AccountRuntime(storage, RpcTransport { request -> requests += request; fixture.execute(request) },
            backgroundScope, { "" }, {}, {}, RpcObserver { _, _, _, _ -> })
        runtime.start(); runCurrent()
        assertEquals(AccountPhase.Ready, runtime.state.phase)
        assertEquals(RootJourneyTransport.EMAIL, runtime.state.email)
        assertTrue(requests.first().headers.isEmpty())
        assertTrue(requests.any { it.methodName() == "account-me" && it.headers["X-Pully-Session"] == RootJourneyTransport.SESSION })
        assertFalse(storage.restored)
        runtime.close()
    }

    @Test
    fun fixtureRejectsOrdinaryCredentialsOtherSourcesAndUnsupportedWork() = runTest {
        val transport = RootJourneyTransport()
        fun request(server: String = PREVIEW_SERVER, headers: Map<String, String> = emptyMap(), name: String = "account-status", args: JsonObject = JsonObject(emptyMap())) =
            RpcRequest("$server/api/rpc", headers, buildJsonObject { put("name", name); put("args", args) }.toString())
        assertFailsWith<IllegalArgumentException> { transport.execute(request(server = "https://ordinary.example")) }
        assertFailsWith<IllegalArgumentException> { transport.execute(request(headers = mapOf("CF-Access-Client-Secret" to "fixture-other-secret"))) }
        assertFailsWith<IllegalArgumentException> { transport.execute(request(headers = mapOf("X-Pully-Session" to "fixture-other-session"))) }
        assertFailsWith<IllegalArgumentException> { transport.execute(request(name = "account-me")) }
        val headers = mapOf("X-Pully-Session" to RootJourneyTransport.SESSION)
        assertFailsWith<IllegalArgumentException> {
            transport.execute(request(headers = headers, name = "github-pr", args = buildJsonObject {
                put("owner", "pully"); put("repo", "mobile-fixtures"); put("number", 75)
            }))
        }
        assertFailsWith<IllegalArgumentException> {
            transport.execute(request(headers = headers, name = "review-state", args = buildJsonObject { put("key", "a-different-review") }))
        }
        val repo = Repo({ RepoConfiguration(PREVIEW_SERVER, headers) }, transport)
        assertFailsWith<RpcConnectionException> { repo.open(OpenTarget.Pull("pully", "mobile-fixtures", 75, "github.com")) }
        assertFailsWith<RpcConnectionException> { repo.savedState("a-different-review") }
        for (method in listOf("ai-task-start", "ai-task-cancel", "reviewed-set", "review-submit", "review-discard", "pr-action", "comment-create")) {
            assertFailsWith<IllegalStateException> { repo.call(method) }
        }
    }
}

private fun RpcRequest.methodName() = Json.parseToJsonElement(body).jsonObject.getValue("name").jsonPrimitive.content

/** A test-only storage port. The simulator journey separately exercises the actual private Keychain host. */
private class SampleAccountStorage(var restored: Boolean = false) : AccountStorage {
    private var session = if (restored) RootJourneyTransport.SESSION else null
    private var generation = 0L
    private var observer: AccountStorageObserver? = null
    override fun connection(verification: Boolean) = AccountConnection(PREVIEW_SERVER,
        if (session != null && (!restored || verification)) mapOf("X-Pully-Session" to session!!) else emptyMap(), "claude-code", "", generation)
    override fun account() = StoredAccount(if (session != null && !restored) RootJourneyTransport.EMAIL else null, restored, null)
    override fun observe(observer: AccountStorageObserver): AccountStorageSubscription {
        this.observer = observer
        return object : AccountStorageSubscription { override fun cancel() { this@SampleAccountStorage.observer = null } }
    }
    override fun acceptSession(session: String, email: String, expectedGeneration: Long): String? {
        if (expectedGeneration != generation || session != RootJourneyTransport.SESSION || email != RootJourneyTransport.EMAIL) return "The sample account changed."
        replace(session, false)
        return null
    }
    override fun confirmRestoredSession(email: String?, expectedGeneration: Long): String? {
        if (expectedGeneration != generation) return "The sample account changed."
        replace(if (email == RootJourneyTransport.EMAIL) session else null, false)
        return null
    }
    override fun forgetSession(): String? { replace(null, false); return null }
    override fun saveServer(server: String, accessId: String, accessSecret: String) = "The test sample server is fixed."
    override fun saveAnalysis(engine: String, model: String, expectedGeneration: Long) =
        if (expectedGeneration == generation) "The test sample analysis settings are fixed." else "The sample account changed."
    override fun resetCredentials() = forgetSession()
    override fun retryStorage(): String? = null
    override fun googleAvailable(serverClientId: String) = false
    override fun googleToken(serverClientId: String, expectedGeneration: Long, completed: AccountTokenObserver): AccountStorageSubscription {
        completed.completed(null, null)
        return object : AccountStorageSubscription { override fun cancel() = Unit }
    }
    private fun replace(value: String?, restoring: Boolean) {
        observer?.invalidating()
        session = value; restored = restoring; generation++
        observer?.changed()
    }
}
