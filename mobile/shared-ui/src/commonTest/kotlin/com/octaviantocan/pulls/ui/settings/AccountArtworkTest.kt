package com.octaviantocan.pulls.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import com.octaviantocan.pulls.ui.design.sharedVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Canonical 56e9bec account-vector regression; shared here before the complete FileIcons slice. */
class AccountArtworkTest {
    @Test
    fun canonicalAccountArtworkKeepsTheOriginalPathsAndPullsTranslation() {
        val google = assertNotNull(sharedVector("ic_si_google"))
        assertEquals(24f, google.viewportWidth)
        assertEquals(24f, google.viewportHeight)
        assertEquals(1, google.root.size)
        assertEquals(SolidColor(Color.White), assertIs<VectorPath>(google.root[0]).fill)

        val pulls = assertNotNull(sharedVector("ic_stat_pulls"))
        assertEquals(72f, pulls.viewportWidth)
        assertEquals(72f, pulls.viewportHeight)
        val group = assertIs<VectorGroup>(pulls.root[0])
        assertEquals(-18f, group.translationX)
        assertEquals(-18f, group.translationY)
        assertEquals(3, group.size)
        val first = assertIs<VectorPath>(group[0])
        assertEquals(PathParser().parsePathString("M44.3,19.6h3.8").toNodes(), first.pathData.take(2))
        for (index in 0 until group.size) {
            val path = assertIs<VectorPath>(group[index])
            assertEquals(SolidColor(Color.White), path.fill)
        }
    }

}
