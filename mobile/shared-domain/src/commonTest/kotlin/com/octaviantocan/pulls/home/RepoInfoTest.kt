package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.ui.home.asRepoInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class RepoInfoTest {
    private val info = Json.parseToJsonElement("""{"currentBranch":"feat/android-app","defaultBranch":{"name":"main","ref":"origin/main"},"branches":["feat/a","main","feat/android-app"],"tags":["v1"],"commits":[{"sha":"3d6c4713a9","subject":"refactor"}]}""").jsonObject.asRepoInfo()

    @Test
    fun readsRepoInfo() {
        assertEquals("feat/android-app", info.currentBranch)
        assertEquals("main" to "origin/main", info.defaultBranch)
        assertEquals(listOf("feat/a", "main", "feat/android-app"), info.branches)
        assertEquals(listOf("3d6c4713a9" to "refactor"), info.commits)
        assertEquals(listOf("v1"), info.tags)
    }
}
