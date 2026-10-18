package com.octaviantocan.pulls.core

/** One current observation, never a URL history. */
data class BrowserObservation(val packageName: String, val windowId: Int, val repository: BrowserRepository)

/** Geometry only: no text or other app content is needed to reject covering windows. */
data class BrowserWindowBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val valid: Boolean get() = left < right && top < bottom
    fun intersects(other: BrowserWindowBounds): Boolean = valid && other.valid &&
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

data class BrowserWindowCover(
    val bounds: BrowserWindowBounds,
    val layer: Int,
    val ownOverlay: Boolean = false,
    val inputMethod: Boolean = false,
)

object BrowserWindowPolicy {
    /** Harmless browser scroll/content bursts must not hide a freshly verified unchanged repository. */
    fun keepObservation(
        shown: BrowserObservation?,
        current: BrowserObservation?,
        sameBrowserEvent: Boolean,
        windowsChanged: Boolean,
    ): Boolean = shown != null && shown == current && (sameBrowserEvent || windowsChanged)

    /**
     * Active/focused flags alone do not prove visibility. Any higher window intersecting the browser's
     * content region blocks the helper, including non-focused system shades and keyboards. Only this
     * service's own accessibility overlay is exempt. The Android adapter removes normal system-bar
     * insets from the content region; it must not exempt whole system packages or all system windows.
     */
    fun exposed(content: BrowserWindowBounds, browserLayer: Int, windows: Iterable<BrowserWindowCover>): Boolean =
        content.valid && windows.none {
            it.layer > browserLayer && (it.inputMethod || !it.ownOverlay && it.bounds.intersects(content))
        }
}
