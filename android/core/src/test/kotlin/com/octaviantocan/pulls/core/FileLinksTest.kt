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

}
