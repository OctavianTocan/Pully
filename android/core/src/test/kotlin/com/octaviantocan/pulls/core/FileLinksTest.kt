package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileLinksTest {
    @Test
    fun blobEncodesEachSegmentLikeEncodeURIComponent() {
        assertEquals(
            "https://github.com/o/r/blob/abc/src/a%20b/%23c.ts",
            FileLinks.blob("o", "r", "abc", "src/a b/#c.ts"),
        )
    }

    @Test
    fun pullDiffLinkPointsAtFilesWithSha256Anchor() {
        // sha256("README.md"), the anchor GitHub uses for that file (checked with sha256sum).
        assertEquals(
            "https://github.com/o/r/pull/1/files#diff-b335630551682c19a781afebcf4d07bf978fb1f8ac04c6bf87428ed5106870f5",
            FileLinks.fileDiff("https://github.com/o/r/pull/1", true, "README.md"),
        )
    }

    @Test
    fun compareLinkHasNoFilesSuffix() {
        assert(FileLinks.fileDiff("https://github.com/o/r/compare/a...b", false, "x").startsWith("https://github.com/o/r/compare/a...b#diff-"))
    }

    @Test
    fun localTargetsHaveNoRepo() {
        assertNull(FileLinks.repoOf(OpenTarget.Worktree))
        assertEquals("o" to "r", FileLinks.repoOf(OpenTarget.Pull("o", "r", 3)))
    }

    @Test
    fun aLinkIsAPathFromTheRootThatKeepsItsDotFolders() {
        assertEquals("src/a.ts", FileLinks.repoPath("src/a.ts"))
        assertEquals("src/a.ts", FileLinks.repoPath("./src/a.ts"))
        assertEquals("src/a.ts", FileLinks.repoPath("/src/a.ts"))
        // J36: the leading dot of a dot-folder is part of its name, not a `./`.
        assertEquals(".agents/skills/ojin-release/SKILL.md", FileLinks.repoPath(".agents/skills/ojin-release/SKILL.md"))
        assertEquals(".agents/skills/ojin-release/SKILL.md", FileLinks.repoPath("./.agents/skills/ojin-release/SKILL.md"))
        assertEquals(".github/workflows/ci.yml", FileLinks.repoPath("/.github/workflows/ci.yml"))
        assertEquals(".ci/pr-deploy.sh", FileLinks.repoPath(".ci/pr-deploy.sh"))
        assertEquals(".env.example", FileLinks.repoPath(".env.example"))
        // The runbook's own link, copied out of docs/runbooks/: `..` never climbs above the root.
        assertEquals(".agents/skills/ojin-release/SKILL.md", FileLinks.repoPath("../../.agents/skills/ojin-release/SKILL.md"))
        assertEquals("docs/b.md", FileLinks.repoPath("docs/runbooks/../b.md"))
        assertEquals("docs/b.md", FileLinks.repoPath("docs/./b.md"))
        assertEquals("docs/my notes.md", FileLinks.repoPath("docs/my%20notes.md"))
        assertEquals("c++/a+b.md", FileLinks.repoPath("c%2B%2B/a+b.md"))
    }

    @Test
    fun aFolderIsNoFile() {
        assertNull(FileLinks.repoPath(""))
        assertNull(FileLinks.repoPath("/"))
        assertNull(FileLinks.repoPath("src/"))
        assertNull(FileLinks.repoPath("src/.."))
        assertNull(FileLinks.repoPath("."))
        assertNull(FileLinks.repoPath(".."))
    }
}
