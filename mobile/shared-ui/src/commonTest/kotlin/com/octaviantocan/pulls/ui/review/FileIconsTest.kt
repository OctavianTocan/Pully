package com.octaviantocan.pulls.ui.review

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import com.octaviantocan.pulls.ui.design.sharedVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class FileIconsTest {
    @Test
    fun fileNamesRetainSpecificIconsBeforeGenericExtensions() {
        assertEquals("ic_cat_npm", fileIconName("app/package.json"))
        assertEquals("ic_cat_json", fileIconName("package-lock.json"))
        assertEquals("ic_cat_typescript_def", fileIconName("src/react.d.mts"))
        assertEquals("ic_cat_typescript_react", fileIconName("src/view.tsx"))
        assertEquals("ic_cat_typescript", fileIconName("src/main.cts"))
        assertEquals("ic_cat_javascript_react", fileIconName("src/view.jsx"))
        assertEquals("ic_cat_image", fileIconName("assets/photo.jpeg"))
        assertEquals("ic_cat_file", fileIconName("README.MD"))
        assertEquals("ic_cat_file", fileIconName("LICENSE"))
    }

    @Test
    fun canonicalVueIconKeepsBothOutlinedPaths() {
        val vector = assertNotNull(sharedVector("ic_cat_vue"))
        assertEquals(16f, vector.viewportWidth)
        assertEquals(16f, vector.viewportHeight)
        assertEquals(2, vector.root.size)
        val first = assertIs<VectorPath>(vector.root[0])
        val second = assertIs<VectorPath>(vector.root[1])
        assertEquals(PathParser().parsePathString("M1 1.5h5.44L8 4.56L9.56 1.5H15l-6.99 13z").toNodes(), first.pathData)
        assertEquals(PathParser().parsePathString("M12.05 1.73L8 9.28L3.95 1.73").toNodes(), second.pathData)
        for (path in listOf(first, second)) {
            assertEquals(SolidColor(Color.Transparent), path.fill)
            assertEquals(SolidColor(Color(0xFFA6DA95)), path.stroke)
            assertEquals(1f, path.strokeLineWidth)
            assertEquals(StrokeCap.Round, path.strokeLineCap)
            assertEquals(StrokeJoin.Round, path.strokeLineJoin)
        }
    }
}
