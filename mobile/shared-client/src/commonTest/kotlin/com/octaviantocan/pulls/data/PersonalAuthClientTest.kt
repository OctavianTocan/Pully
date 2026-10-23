package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalAuthClientTest {
    @Test
    fun rejectsCredentialBearingNonHttpsOrPathBasedOrigins() {
        for (origin in listOf("http://accounts.example", "https://user:password@accounts.example", "https://accounts.example/path", "https://accounts.example?token=secret", "https://accounts.example#fragment", "https://accounts.example\\evil"))
            assertFailsWith<IllegalArgumentException> { PersonalAuthOrigin.parse(origin) }
        assertEquals("https://accounts.example", PersonalAuthOrigin.parse("https://ACCOUNTS.example:443/").base)
    }

    @Test
    fun consumesTheActualFixedWireWithoutLegacyAuthorityOrBrowserProof() = runTest {
        val f = Fixture()
        assertTrue(f.client.enabled())
        val attempt = f.client.start(f.proof)
        assertFalse(attempt.authorizeUrl.contains(f.proof.secret))
        assertEquals(1_800L, attempt.expiresAt)
        assertEquals(2_000L, attempt.pollAfterMs)
        val start = Json.parseToJsonElement(f.requests[1].body).jsonObject
        assertEquals(setOf("nativeChallenge"), start.keys)
        assertEquals(JsonPrimitive(f.proof.challenge), start["nativeChallenge"])
        assertEquals(PersonalAttemptPhase.Ready, f.client.status(attempt).phase)
        val session = f.client.claim(attempt)
        assertEquals(f.session, session.token)
        assertEquals("https://accounts.example", session.origin)
        assertEquals(42L, session.status.githubId)
        assertEquals(PersonalAuthority.PublicRead, session.status.authority)
        assertFalse(session.toString().contains(session.token))
        assertFalse(attempt.toString().contains(f.proof.secret))
        assertEquals(1_800L, session.status.sessionExpiresAt)
        assertEquals(session.status.copy(sessionExpiresAt = 3_600L), f.client.account(session.token))
        f.client.signOut(session.token)
        f.client.disconnect(session.token)
        for (request in f.requests) {
            assertTrue(request.url.startsWith("https://accounts.example/api/personal/v1/"))
            assertFalse('?' in request.url)
            assertEquals(32 * 1024, request.responseLimit)
            assertTrue(request.headers.keys.all { it in setOf("Accept", "Cache-Control", "Authorization") })
            if (request.url.endsWith("/status") || request.url.endsWith("/claim")) {
                assertEquals(setOf("attemptId", "nativeProof"), Json.parseToJsonElement(request.body).jsonObject.keys)
                assertFalse("Authorization" in request.headers)
            }
            if (request.url.endsWith("/session") || request.url.endsWith("/sign-out") || request.url.endsWith("/disconnect"))
                assertEquals("Bearer ${f.session}", request.headers["Authorization"])
        }
    }

    @Test
    fun disabledCapabilityIsRealAndMalformedCapabilityIsNotEnabled() = runTest {
        val f = Fixture()
        f.respond = { json("""{"github":{"enabled":false}}""") }
        assertFalse(f.client.enabled())
        for (body in listOf("{}", """{"github":{"enabled":"true"}}""", """{"github":null}""")) {
            f.respond = { json(body) }
            assertEquals(PersonalAuthProblem.InvalidResponse, assertFailsWith<PersonalAuthError> { f.client.enabled() }.problem)
        }
    }

    @Test
    fun aBrowserCanOnlyOpenTheConfiguredPublicConsentOnFixedGithub() = runTest {
        val f = Fixture()
        for (url in listOf(
            f.authorize.replace("https://github.com/", "https://github.com.evil/"),
            f.authorize.replace("https://github.com/", "https://user@github.com/"),
            f.authorize.replace("https://github.com/", "http://github.com/"),
            f.authorize.replace("accounts.example", "other.example"),
            f.authorize.replace("scope=offline_access", "scope=repo+offline_access"),
            f.authorize + "&returnUrl=https%3A%2F%2Fevil.example",
            f.authorize + "&state=" + f.id,
            f.authorize + "#fragment",
            f.authorize.replace("code_challenge_method=S256", "code_challenge_method=plain"),
        )) {
            f.respond = { f.startReply(url) }
            assertEquals(PersonalAuthProblem.InvalidResponse, assertFailsWith<PersonalAuthError> { f.client.start(f.proof) }.problem)
        }
    }

    @Test
    fun githubCliDeviceSignInCarriesOnlyGithubsDevicePageAndAShortCode() = runTest {
        val f = Fixture()
        f.respond = { f.startReply(GITHUB_DEVICE_URL, "WDJB-MJHT") }
        val attempt = f.client.start(f.proof)
        assertEquals("https://github.com/login/device", attempt.authorizeUrl)
        assertEquals("WDJB-MJHT", attempt.userCode)
        for ((url, code) in listOf(
            GITHUB_DEVICE_URL to null,
            GITHUB_DEVICE_URL to "wdjb-mjht",
            GITHUB_DEVICE_URL to "WDJB MJHT",
            GITHUB_DEVICE_URL to "ABC",
            "https://github.com/login/device?user_code=WDJB-MJHT" to "WDJB-MJHT",
            "https://github.com.evil/login/device" to "WDJB-MJHT",
            f.authorize to "WDJB-MJHT",
        )) {
            f.respond = { f.startReply(url, code) }
            assertEquals(PersonalAuthProblem.InvalidResponse, assertFailsWith<PersonalAuthError> { f.client.start(f.proof) }.problem)
        }
    }

    @Test
    fun statusAndAccountFieldsRequireTheirActualTypesAndApplicationAuthority() = runTest {
        val f = Fixture()
        val attempt = f.client.start(f.proof)
        for (body in listOf("""{"phase":"failed"}""", """{"phase":"ready","failure":"denied"}""", """{"phase":true}""")) {
            f.respond = { json(body) }
            assertEquals(PersonalAuthProblem.InvalidResponse, assertFailsWith<PersonalAuthError> { f.client.status(attempt) }.problem)
        }
        for (account in listOf(f.account.replace("\"githubId\":42", "\"githubId\":\"42\""), f.account.replace("\"refreshAvailable\":false", "\"refreshAvailable\":\"false\""), f.account.replace("\"publicRead\"", "\"operator\""))) {
            f.respond = { json(account) }
            assertEquals(PersonalAuthProblem.InvalidResponse, assertFailsWith<PersonalAuthError> { f.client.account(f.session) }.problem)
        }
        f.respond = { json("""{"session":"${f.session}","status":${f.claimAccount.replace("publicRead", "privateRepositories")}}""") }
        assertEquals(PersonalAuthProblem.InvalidResponse, assertFailsWith<PersonalAuthError> { f.client.claim(attempt) }.problem)
    }

    @Test
    fun errorRecoveryIsTypedAndNeverReflectsCredentialsOrHtml() = runTest {
        val f = Fixture()
        for ((status, kind, expected) in listOf(
            Triple(401, "unauthorized", PersonalAuthProblem.Unauthorized), Triple(401, "reconnect", PersonalAuthProblem.Reconnect),
            Triple(403, "denied", PersonalAuthProblem.Denied), Triple(410, "expired", PersonalAuthProblem.Expired),
            Triple(429, "rateLimited", PersonalAuthProblem.RateLimited), Triple(503, "unavailable", PersonalAuthProblem.Unavailable),
            Triple(401, "unavailable", PersonalAuthProblem.HttpRejected),
        )) {
            f.respond = { json(buildJsonObject { put("error", buildJsonObject { put("kind", kind); put("message", "Reflected ${f.proof.secret} ${f.session}") }) }.toString(), status) }
            val error = assertFailsWith<PersonalAuthError> { f.client.account(f.session) }
            assertEquals(expected, error.problem)
            assertEquals(status, error.httpStatus)
            assertFalse(error.message.orEmpty().contains(f.proof.secret))
            assertFalse(error.message.orEmpty().contains(f.session))
        }
        for ((response, expected) in listOf(
            RpcResponse(401, "<!DOCTYPE html><html>private-proxy-page", "text/html") to PersonalAuthProblem.WebPage,
            RpcResponse(401, "not-json", "application/json") to PersonalAuthProblem.InvalidResponse,
            json("{}", 401) to PersonalAuthProblem.HttpRejected,
            RpcResponse(302, "", "", "https://redirect.example/?token=private") to PersonalAuthProblem.Redirect,
        )) {
            f.respond = { response }
            val error = assertFailsWith<PersonalAuthError> { f.client.account(f.session) }
            assertEquals(expected, error.problem)
            assertFalse(error.message.orEmpty().contains("private"))
        }
    }

    @Test
    fun obsoleteSourcesCannotStartOrAcceptAnAttempt() = runTest {
        val f = Fixture()
        val source = f.client.source()
        f.generation++
        assertFailsWith<CancellationException> { f.client.enabled(source) }
        assertTrue(f.requests.isEmpty())
        val held = CompletableDeferred<Unit>()
        f.respond = { withContext(NonCancellable) { held.await() }; f.startReply() }
        val pending = async { f.client.start(f.proof) }
        runCurrent()
        f.generation++
        held.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
    }

    @Test
    fun retiredCleanupOnlyUsesItsCapturedOriginAndProof() = runTest {
        val f = Fixture()
        val attempt = f.client.start(f.proof)
        f.generation++
        assertFailsWith<CancellationException> { f.client.status(attempt) }
        f.client.cancelRetired(attempt)
        val request = f.requests.last()
        assertEquals("https://accounts.example/api/personal/v1/auth/github/cancel", request.url)
        assertEquals(JsonPrimitive(f.proof.secret), Json.parseToJsonElement(request.body).jsonObject["nativeProof"])
        assertTrue(request.headers.keys.none { it == "Authorization" })
        val other = PersonalAuthClient(PersonalAuthOrigin.parse("https://other.example"), RpcTransport { error("Must not send another origin's proof") })
        assertFailsWith<CancellationException> { other.cancelRetired(attempt) }
    }

    @Test
    fun networkFailuresAreBoundedAndCallerCancellationStillPropagates() = runTest {
        val f = Fixture()
        f.respond = { error("native-secret-address?token=${f.session}") }
        val unreachable = assertFailsWith<PersonalAuthError> { f.client.enabled() }
        assertEquals(PersonalAuthProblem.Unreachable, unreachable.problem)
        assertFalse(unreachable.message.orEmpty().contains(f.session))
        f.respond = { delay(20_001); json("{}") }
        assertEquals(PersonalAuthProblem.Timeout, assertFailsWith<PersonalAuthError> { f.client.enabled() }.problem)
        var detached = false
        f.respond = { try { awaitCancellation() } finally { detached = true } }
        val pending = async { f.client.enabled() }
        runCurrent()
        pending.cancelAndJoin()
        assertTrue(detached)
    }

    internal class Fixture {
        val proof = PersonalAuthProof.fromEntropy(ByteArray(32) { 1 })
        val id = proofBase64.encode(ByteArray(32) { 2 })
        val session = "pully_" + proofBase64.encode(ByteArray(32) { 3 })
        val authorize = "https://github.com/login/oauth/authorize?client_id=pully-test&redirect_uri=https%3A%2F%2Faccounts.example%2Fapi%2Fpersonal%2Fv1%2Fauth%2Fgithub%2Fcallback&response_type=code&state=$id&code_challenge=$id&code_challenge_method=S256&scope=offline_access&prompt=select_account"
        val account = """{"accountId":"account-42","githubId":42,"login":"reader","authority":"publicRead","sessionExpiresAt":3600,"accessExpiresAt":null,"refreshAvailable":false}"""
        val claimAccount = account.replace("\"sessionExpiresAt\":3600", "\"sessionExpiresAt\":1800")
        var generation = 0L
        val requests = mutableListOf<RpcRequest>()
        var respond: suspend (RpcRequest) -> RpcResponse = { request -> when {
            request.url.endsWith("/capabilities") -> json("""{"github":{"enabled":true}}""")
            request.url.endsWith("/start") -> startReply()
            request.url.endsWith("/status") -> json("""{"phase":"ready"}""")
            request.url.endsWith("/claim") -> json("""{"session":"$session","status":$claimAccount}""")
            request.url.endsWith("/cancel") -> json("""{"cancelled":true}""")
            request.url.endsWith("/session") -> json(account)
            else -> json("true")
        } }
        val client = PersonalAuthClient(PersonalAuthOrigin.parse("https://accounts.example"), RpcTransport { requests += it; respond(it) }, { generation })
        fun startReply(url: String = authorize, userCode: String? = null): RpcResponse = json(buildJsonObject {
            put("attemptId", id); put("authorizeUrl", url); put("expiresAt", 1800); put("pollAfterMs", 2000)
            if (userCode != null) put("userCode", userCode)
        }.toString())
    }

    companion object {
        internal fun json(body: String, status: Int = 200): RpcResponse = RpcResponse(status, body, "application/json")
    }
}
