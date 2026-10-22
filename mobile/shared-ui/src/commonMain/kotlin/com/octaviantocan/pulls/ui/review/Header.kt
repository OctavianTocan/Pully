package com.octaviantocan.pulls.ui.review

import androidx.compose.ui.graphics.Color

// --- Shared bits of the page top ---

/** UnoCSS palette stops the page-top templates name (`text-green-600 dark:text-green-400`...). */
object WebColors {
    fun green(dark: Boolean) = Color(if (dark) 0xFF3CC98F else 0xFF1C8A5A)
    fun red(dark: Boolean) = Color(if (dark) 0xFFE8777C else 0xFFC94F55)
    val amber500 = Color(0xFFF59E0B)
    /** `text-amber-600 dark:text-amber-400`. */
    fun amberIcon(dark: Boolean) = Color(if (dark) 0xFFFBBF24 else 0xFFD97706)
    /** `text-amber-700 dark:text-amber-400`. */
    fun amberText(dark: Boolean) = Color(if (dark) 0xFFFBBF24 else 0xFFB45309)
    /** `text-orange-700 dark:text-orange-400`. */
    fun orange(dark: Boolean) = Color(if (dark) 0xFFFB923C else 0xFFC2410C)
    /** The accent text: `--link` (a lighter teal in the dark theme). */
    fun primary(dark: Boolean) = Color(if (dark) 0xFF6CC3CB else 0xFF1E6F78)
    val purple500 = Color(0xFFA855F7)
}
