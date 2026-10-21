package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.core.LinkPlace
import com.octaviantocan.pulls.core.OpenTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val maximumLinkBytes = 16 * 1024
private val identifier = Regex("""[A-Za-z0-9_.-]{1,255}""")
private val commitSha = Regex("""[0-9a-fA-F]{7,64}""")
private val fileSha = Regex("""[0-9a-f]{64}""")
private val commitRange = Regex("""[0-9a-fA-F]{7,64}(?:\.\.\.?[0-9a-fA-F]{7,64})?""")
private val postedId = Regex("""[0-9A-Za-z]{6,32}""")

/** The extension runs the SAME parser, then persists only its canonical destination, never shared prose or URL query. */
fun encodeSharedLink(text: String): String? {
    if (text.length > maximumLinkBytes || text.encodeToByteArray().size > maximumLinkBytes) return null
    val link = HomeLinks.parseShared(text) ?: return null
    val data = link.pack() ?: return null
    return Json.encodeToString(data).takeIf { it.encodeToByteArray().size <= maximumLinkBytes }
}

/** PullyShared re-decodes the bounded data in its own runtime; no Kotlin object crosses an extension process. */
fun decodeSharedLink(payload: String): HomeLink? {
    if (payload.length > maximumLinkBytes || payload.encodeToByteArray().size > maximumLinkBytes) return null
    return runCatching {
        val data = Json.decodeFromString<SharedLinkData>(payload)
        if (data.version != 1) return null
        val link = data.unpack() ?: return null
        // Reject irrelevant or malformed fields instead of silently retaining data outside the destination schema.
        if (link.pack() != data) return null
        // Serialization can coerce a quoted number; compare the shallow validated wire tree to enforce actual types.
        link.takeIf { Json.parseToJsonElement(payload) == Json.parseToJsonElement(Json.encodeToString(data)) }
    }.getOrNull()
}

@Serializable
private data class SharedLinkData(val version: Int, val target: TargetData, val place: PlaceData? = null)

@Serializable
private data class TargetData(
    val kind: String,
    val owner: String? = null,
    val repo: String? = null,
    val number: Int? = null,
    val name: String? = null,
    val base: String? = null,
    val head: String? = null,
    val sha: String? = null,
    val id: String? = null,
)

@Serializable
private data class PlaceData(
    val kind: String,
    val id: Long? = null,
    val file: String? = null,
    val line: Int? = null,
    val left: Boolean = false,
    val commits: String? = null,
    val sha: String? = null,
    val nested: PlaceData? = null,
)

private fun HomeLink.pack(): SharedLinkData? {
    return when (this) {
    is HomeLink.Repo -> if (identifier.matches(owner) && identifier.matches(repo)) SharedLinkData(1, TargetData("repo", owner, repo)) else null
    is HomeLink.Target -> {
        val destination = target.pack() ?: return null
        val point = place?.pack() ?: if (place != null) return null else null
        SharedLinkData(1, destination, point)
    }
}
}

private fun OpenTarget.pack(): TargetData? {
    fun repository(owner: String, repo: String) = identifier.matches(owner) && identifier.matches(repo)
    return when (this) {
        is OpenTarget.Pull -> if (repository(owner, repo) && number > 0 && (host == null || host == "github.com"))
            TargetData(if (host == null) "serverPull" else "githubPull", owner, repo, number) else null
        is OpenTarget.Compare -> if (repository(owner, repo) && safeRef(base) && safeRef(head)) TargetData("compare", owner, repo, base = base, head = head) else null
        is OpenTarget.Commit -> if (repository(owner, repo) && commitSha.matches(sha)) TargetData("commit", owner, repo, sha = sha) else null
        is OpenTarget.Branch -> if (safeRef(name)) TargetData("branch", name = name) else null
        is OpenTarget.Posted -> if (postedId.matches(id)) TargetData("posted", id = id) else null
        is OpenTarget.Local, OpenTarget.Worktree -> null
    }
}

private fun safeRef(value: String) = value.isNotBlank() && value.length <= 1024 && value.none { it.isWhitespace() || it.code < 32 || it.code in 127..159 || it == '?' || it == '#' }

private fun LinkPlace.pack(): PlaceData? {
    return when (this) {
    is LinkPlace.Comment -> if (id > 0) PlaceData("comment", id = id) else null
    is LinkPlace.Review -> if (id > 0) PlaceData("review", id = id) else null
    is LinkPlace.CodeComment -> if (id > 0) PlaceData("codeComment", id = id) else null
    LinkPlace.Description -> PlaceData("description")
    LinkPlace.Conversation -> PlaceData("conversation")
    is LinkPlace.Files -> if ((file == null || fileSha.matches(file)) && (line == null || line > 0 && file != null) &&
        (!left || line != null) && (commits == null || commitRange.matches(commits))) PlaceData("files", file = file, line = line, left = left, commits = commits) else null
    is LinkPlace.Commits -> {
        if (sha != null && !commitSha.matches(sha) || file != null && sha == null) null
        else {
            val nested = file?.pack() ?: if (file != null) return null else null
            PlaceData("commits", sha = sha, nested = nested)
        }
    }
    is LinkPlace.Checks -> if (runId == null || runId > 0) PlaceData("checks", id = runId) else null
}
}

private fun SharedLinkData.unpack(): HomeLink? {
    val value = target
    if (value.kind == "repo") return HomeLink.Repo(value.owner ?: return null, value.repo ?: return null)
    val destination = when (value.kind) {
        "githubPull", "serverPull" -> OpenTarget.Pull(value.owner ?: return null, value.repo ?: return null, value.number ?: return null,
            if (value.kind == "githubPull") "github.com" else null)
        "compare" -> OpenTarget.Compare(value.owner ?: return null, value.repo ?: return null, value.base ?: return null, value.head ?: return null)
        "commit" -> OpenTarget.Commit(value.owner ?: return null, value.repo ?: return null, value.sha ?: return null)
        "branch" -> OpenTarget.Branch(value.name ?: return null)
        "posted" -> OpenTarget.Posted(value.id ?: return null)
        else -> return null
    }
    val point = place?.unpack() ?: if (place != null) return null else null
    return HomeLink.Target(destination, point)
}

private fun PlaceData.unpack(): LinkPlace? {
    return when (kind) {
    "comment" -> id?.let { LinkPlace.Comment(it) }
    "review" -> id?.let { LinkPlace.Review(it) }
    "codeComment" -> id?.let { LinkPlace.CodeComment(it) }
    "description" -> LinkPlace.Description
    "conversation" -> LinkPlace.Conversation
    "files" -> LinkPlace.Files(file, line, left, commits)
    "commits" -> {
        val file = nested?.unpack() as? LinkPlace.Files ?: if (nested != null) return null else null
        LinkPlace.Commits(sha, file)
    }
    "checks" -> LinkPlace.Checks(id)
    else -> null
}
}
