package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoryPathsTest {
    @Test
    fun percentDecodingKeepsPlusAndUnicodeAndRejectsBrokenEscapes() {
        assertEquals("docs/café/🦉+a.md", decodeUrlPath("docs/caf%C3%A9/%F0%9F%A6%89+a.md"))
        assertNull(decodeUrlPath("broken%"))
        assertNull(decodeUrlPath("broken%0Z"))
        assertEquals("broken%0Z", repositoryPath("broken%0Z"))
    }

    @Test
    fun aLinkIsAPathFromTheRootThatKeepsItsDotFolders() {
        assertEquals("src/a.ts", repositoryPath("src/a.ts"))
        assertEquals("src/a.ts", repositoryPath("./src/a.ts"))
        assertEquals("src/a.ts", repositoryPath("/src/a.ts"))
        // J36: the leading dot of a dot-folder is part of its name, not a `./`.
        assertEquals(".agents/skills/ojin-release/SKILL.md", repositoryPath(".agents/skills/ojin-release/SKILL.md"))
        assertEquals(".agents/skills/ojin-release/SKILL.md", repositoryPath("./.agents/skills/ojin-release/SKILL.md"))
        assertEquals(".github/workflows/ci.yml", repositoryPath("/.github/workflows/ci.yml"))
        assertEquals(".ci/pr-deploy.sh", repositoryPath(".ci/pr-deploy.sh"))
        assertEquals(".env.example", repositoryPath(".env.example"))
        // The runbook's own link, copied out of docs/runbooks/: `..` never climbs above the root.
        assertEquals(".agents/skills/ojin-release/SKILL.md", repositoryPath("../../.agents/skills/ojin-release/SKILL.md"))
        assertEquals("docs/b.md", repositoryPath("docs/runbooks/../b.md"))
        assertEquals("docs/b.md", repositoryPath("docs/./b.md"))
        assertEquals("docs/my notes.md", repositoryPath("docs/my%20notes.md"))
        assertEquals("c++/a+b.md", repositoryPath("c%2B%2B/a+b.md"))
    }

    @Test
    fun aFolderIsNoFile() {
        assertNull(repositoryPath(""))
        assertNull(repositoryPath("/"))
        assertNull(repositoryPath("src/"))
        assertNull(repositoryPath("src/.."))
        assertNull(repositoryPath("."))
        assertNull(repositoryPath(".."))
    }
}
