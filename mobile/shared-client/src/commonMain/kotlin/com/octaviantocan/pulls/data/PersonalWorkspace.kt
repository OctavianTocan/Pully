package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Full native services for one freshly verified, repository-authorized GitHub account. */
class PersonalWorkspace internal constructor(
    val accountId: String,
    val githubId: Long,
    val login: String,
    val server: String,
    private val generation: Long,
    headers: Map<String, String>,
    transport: RpcTransport,
    private val owns: () -> Boolean,
    rejected: () -> Unit,
) {
    private var retired = false
    private val retirements = mutableListOf<() -> Unit>()
    private val headers = headers.toMap()
    val current: Boolean get() = !retired && owns()
    private val transport = RpcTransport { request ->
        requireCurrent()
        check(request.url in setOf("$server/api/rpc", "$server/api/events") && request.method == RpcMethod.Post)
        val response = transport.execute(request)
        requireCurrent()
        // Only the workspace's actual RPC rejection retires the account; Access/HTML is not identity evidence.
        if (response.status == 401 && runCatching {
                (Json.parseToJsonElement(response.body) as? JsonObject)?.get("error")
                    .let { it is JsonPrimitive && it.isString }
            }.getOrDefault(false)) rejected()
        requireCurrent()
        response
    }
    val repo: Repo = repository()

    /** Same sanitized events envelope, under the workspace's captured bearer and foreground owner. */
    suspend fun postEvents(body: String): Int {
        requireCurrent()
        return RpcClient(server, transport, headers).postEvents(body)
    }

    /** Native settings remain native; credential custody and the fixed endpoint remain here. */
    fun repository(
        engine: () -> String = { "claude-code" },
        model: () -> String = { "" },
        effort: () -> String = { "" },
        admitted: () -> Boolean = { true },
        observer: RpcObserver = RpcObserver { _, _, _, _ -> },
    ): Repo = Repo({
        requireCurrent()
        if (!admitted()) throw CancellationException("The native workspace changed.")
        RepoConfiguration(server, headers, engine(), model(), effort(), generation)
    }, transport, observer)

    /** Fixed account/session configuration; each request acquires a fresh foreground capability. */
    internal fun presentationRepository(
        owner: PersonalWorkspaceOwner,
        engine: () -> String,
        model: () -> String,
        effort: () -> String,
        admitted: () -> Boolean,
        observer: RpcObserver,
    ): Repo = Repo({
        owner.requireCurrent()
        if (!admitted()) throw CancellationException("The native workspace changed.")
        RepoConfiguration(server, headers, engine(), model(), effort(), generation)
    }, RpcTransport { request ->
        owner.requireCurrent()
        if (!admitted()) throw CancellationException("The native workspace changed.")
        val captured = owner.foregroundWorkspace() ?: throw CancellationException("The workspace is paused.")
        // The presentation can never route an old form through another bearer or account.
        if (captured.accountId != accountId || captured.githubId != githubId || captured.server != server || captured.headers != headers)
            throw CancellationException("The workspace owner changed.")
        val response = captured.transport.execute(request)
        owner.requireCurrent()
        response
    }, observer)

    fun onRetired(action: () -> Unit): () -> Unit {
        if (!current) { action(); return {} }
        retirements += action
        return { retirements -= action }
    }

    internal fun retire() {
        if (retired) return
        retired = true
        retirements.toList().forEach { runCatching(it) }
        retirements.clear()
    }

    private fun requireCurrent() {
        if (!current) throw CancellationException("The GitHub workspace changed.")
    }

    override fun toString(): String = "PersonalWorkspace([bound])"
}
