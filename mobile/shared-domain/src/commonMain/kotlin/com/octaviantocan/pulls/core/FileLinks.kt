package com.octaviantocan.pulls.core

/** Port of the web `file-links.ts`: links from one diff file to GitHub. */
object FileLinks {
    /** A file's page on GitHub at a given commit (`githubBlobUrl`). */
    fun blob(owner: String, repo: String, sha: String, path: String): String =
        githubBlobLink(owner, repo, sha, path)

    /** GitHub's diff view scrolled to one file (`githubFileDiffUrl`): `#diff-<sha256 of path>`. */
    fun fileDiff(url: String, pullRequest: Boolean, path: String): String {
        val page = if (pullRequest) "$url/files" else url
        return "$page#diff-" + sha256Utf8(path)
    }

    /**
     * A link's target as a path from the repository root, or null when it names a folder. A leading `/` or `./` goes and
     * `..` steps up a folder, never above the root: the AI writes from the root, and a `../../.agents/x.md` it copied
     * out of `docs/runbooks/` still finds `.agents/x.md`. A folder whose name starts with a dot (`.agents`, `.github`,
     * `.ci`) is a folder like any other and keeps its dot, so the link does not land on a path that is not there. A `%20`
     * in the link is the space in the file's name.
     */
    fun repoPath(link: String): String? = repositoryPath(link)

    /** Repository coordinates for a target that lives on GitHub, or null for local diffs. */
    fun repoOf(target: OpenTarget): Pair<String, String>? = githubRepository(target)

}
