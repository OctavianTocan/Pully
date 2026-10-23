package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.ServerEndpoint
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.ui.home.HomeObservations

/** These are distinct account connections, not aliases for a legacy engine or pasted subscription tokens. */
enum class AiConnectionKind(val titleKey: String) {
    ClaudeSubscription("aiConnections.kinds.claudeSubscription"),
    ChatGPTSubscription("aiConnections.kinds.chatGPTSubscription"),
    PiEndpoint("aiConnections.kinds.piEndpoint"),
}

enum class AiConnectionStatus { Disconnected, Pending, Unverified, Connected, RefreshNeeded, Unavailable, Unsupported }

enum class AiConnectionOperation { Connect, ConfigurePi, Remove }
data class AiProviderCapabilities(val configure: Boolean = false, val models: Boolean = false, val execute: Boolean = false)

/** Client-owned copy; arbitrary server errors, endpoint paths and credentials are never UI descriptions. */
enum class AiConnectionNotice {
    PiUnverified, ClaudeApprovalRequired, ChatGPTRegistrationRequired, DeploymentUnsupported, ProviderUnavailable, RefreshRequired,
}

/** A mutation names exactly the connection revision drawn by its caller. The server derives the principal. */
data class AiConnectionVersion(val id: String, val revision: Long) {
    init { require(id.isNotBlank() && id.none { it.isISOControl() } && revision > 0) }
}

/** Redacted origin only. This validates display shape, not the server's DNS, egress or redirect policy. */
class AiEndpointOrigin private constructor(val value: String) {
    override fun toString() = value
    companion object {
        fun parse(value: String): AiEndpointOrigin {
            val endpoint = ServerEndpoint.parse(value)
            require(endpoint.scheme == "https" && '/' !in endpoint.base.substringAfter("://"))
            return AiEndpointOrigin(endpoint.base)
        }
    }
}

data class AiConnectionView(
    val kind: AiConnectionKind,
    val status: AiConnectionStatus,
    val accountId: String? = null,
    val version: AiConnectionVersion? = null,
    val endpointOrigin: AiEndpointOrigin? = null,
    val preferredModel: String? = null,
    val hasCredential: Boolean = false,
    val approvalRequired: Boolean = false,
    val capabilities: AiProviderCapabilities = AiProviderCapabilities(),
    val operations: Set<AiConnectionOperation> = emptySet(),
    val notice: AiConnectionNotice? = null,
) {
    init {
        require(accountId == null || accountId.isNotBlank())
        require(status != AiConnectionStatus.Connected || version != null)
        require(!capabilities.execute || status == AiConnectionStatus.Connected)
        require(AiConnectionOperation.Remove !in operations || version != null)
        require(AiConnectionOperation.ConfigurePi !in operations || kind == AiConnectionKind.PiEndpoint)
        require(AiConnectionOperation.ConfigurePi !in operations || capabilities.configure)
        require(endpointOrigin == null || kind == AiConnectionKind.PiEndpoint)
        require(status != AiConnectionStatus.Unsupported || operations.all { it == AiConnectionOperation.Remove })
        require(!approvalRequired || AiConnectionOperation.Connect !in operations)
    }
}

/** Only write requests carry a user-entered credential. The status API never returns one. */
sealed interface AiCredentialWrite {
    data object Keep : AiCredentialWrite
    data object Clear : AiCredentialWrite
    class Replace(val value: String) : AiCredentialWrite {
        init { require(value.isNotEmpty() && value.length <= 16_384 && value.all { it in '!'..'~' }) }
        override fun toString() = "Replace([redacted])"
    }
}

/** Null fields retain existing values; an empty preferred model selects the provider default. */
class AiPiChange(val endpoint: String?, val preferredModel: String?, val credential: AiCredentialWrite) {
    init {
        require(endpoint == null || endpoint.encodeToByteArray().size <= 2048 && endpoint.none { it.isISOControl() } && ServerEndpoint.parse(endpoint).scheme == "https")
        require(preferredModel == null || preferredModel.encodeToByteArray().size <= 256 && preferredModel.none { it.isISOControl() } && (preferredModel.isEmpty() || preferredModel.isNotBlank()))
    }
    override fun toString() = "AiPiChange(endpoint=[redacted], preferredModel=[redacted], credential=$credential)"
}

enum class AiConnectionProblem {
    Unavailable, ConnectionChanged, AuthorizationExpired, InvalidEndpoint, CredentialRejected, Unsupported,
}

class AiConnectionException(val problem: AiConnectionProblem) : Exception(problem.name)
fun interface AiConnectionsSubscription { fun cancel() }

/** Read authority is required independently of GitHub login or any legacy private-review graph. */
interface AiConnectionsReadGate {
    val ready: Boolean
    val generation: Long
    fun observe(changed: (Boolean) -> Unit): AiConnectionsSubscription
    suspend fun <T> read(block: suspend () -> T): T
}

/** Reuse the actual tracked cancellation policy while retaining a separate public/local/account source lifetime. */
class AiConnectionsReadAuthority(initialReady: Boolean = false) : AiConnectionsReadGate {
    private val reads = HomeObservations(initialReady)
    override val ready: Boolean get() = reads.ready
    override val generation: Long get() = reads.generation
    override fun observe(changed: (Boolean) -> Unit): AiConnectionsSubscription {
        val subscription = reads.observe(changed)
        return AiConnectionsSubscription(subscription::cancel)
    }
    override suspend fun <T> read(block: suspend () -> T): T = reads.read(block)
    fun pause() = reads.pause()
    fun resume() = reads.resume()
    fun close() = reads.close()
}

/**
 * One immutable source adapter, independently eligible for public/local or signed-in AI. Implementations
 * enforce actual authority, connection revision,
 * provider deployment policy and safe transport. No HTTP route, subscription login or operator fallback
 * is implied by this presentation port. Its source owner closes the state before credentials change.
 */
interface AiConnectionsServices {
    /** A real personal adapter requires its Pully account ID; a public/local adapter has no such account. */
    val accountId: String?
    val sharedDefault: AiSharedDefaultStatus
    val reads: AiConnectionsReadGate
    fun current(): Boolean
    /** All three kinds, exactly once, with current redacted server/deployment status. */
    suspend fun load(): List<AiConnectionView>
    suspend fun connect(kind: AiConnectionKind, expected: AiConnectionVersion?): AiConnectionView
    suspend fun configurePi(change: AiPiChange, expected: AiConnectionVersion?): AiConnectionView
    suspend fun remove(kind: AiConnectionKind, expected: AiConnectionVersion): AiConnectionView
}

enum class AiSharedDefaultStatus { Unavailable, Available, Selected }

/** Real native feedback only, subject to its user/system settings; the controller does not invent a silent host. */
fun interface AiConnectionsFeedback { fun play(cue: Cue, emphasis: Emphasis) }
