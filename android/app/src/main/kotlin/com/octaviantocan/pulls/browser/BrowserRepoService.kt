package com.octaviantocan.pulls.browser

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.octaviantocan.pulls.MainActivity
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.core.BrowserAddressNode
import com.octaviantocan.pulls.core.BrowserRepo
import com.octaviantocan.pulls.core.BrowserRepository
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.ui.design.AppFont
import com.octaviantocan.pulls.ui.design.ColorThemes
import com.octaviantocan.pulls.ui.design.Messages
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import java.lang.ref.WeakReference

/**
 * A user-enabled accessibility overlay, never a browser-history collector. Only known native address-bar IDs
 * are inspected. No page text, clipboard, URL persistence, analytics or network calls belong in this service.
 */
class BrowserRepoService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var button: TextView? = null
    private var shown: Address? = null
    private val inspect = Runnable { update() }

    private data class Address(val packageName: String, val windowId: Int, val repository: BrowserRepository)

    override fun onServiceConnected() {
        connected = WeakReference(this)
        // Enabling the system service alone does not bypass the in-app disclosure.
        if (!BrowserHelperSettings.allowed(this)) { disableSelf(); return }
        update()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Overlay add/remove can emit package-less window events. Preserve a still-valid observation
        // instead of creating an add -> windows changed -> remove -> add feedback loop.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && shown != null && readAddress() == shown) return
        // Our own non-focusable overlay does not represent a browser navigation.
        if (event.packageName?.toString() == packageName) {
            if (shown != null && readAddress() != shown) hide()
            return
        }
        hide()
        handler.removeCallbacks(inspect)
        if (!BrowserHelperSettings.allowed(this)) { disableSelf(); return }
        // Never inspect a non-browser event's content. Window changes are rechecked against the active root.
        val browser = event.packageName?.toString().orEmpty()
        if (BrowserRepo.addressBarIds(browser).isEmpty() && event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        handler.postDelayed(inspect, SETTLE_MS)
    }

    private fun update() {
        handler.removeCallbacks(inspect)
        if (!BrowserHelperSettings.allowed(this)) { hide(); disableSelf(); return }
        val current = readAddress()
        if (current == null) { hide(); return }
        if (shown != current || button == null) show(current)
        // Recheck while visible, including a toolbar disappearing without a useful accessibility event.
        // This is not URL history: each read replaces the one current normalized repository in memory.
        if (button != null) handler.postDelayed(inspect, RECHECK_MS)
    }

    private fun readAddress(): Address? = try {
        inspectAddress()
    } catch (_: RuntimeException) {
        null
    }

    private fun inspectAddress(): Address? {
        if (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != false) return null
        val visibleWindows = getWindows()
        // The helper itself can become active during a tap but never takes focus. A system shade,
        // keyboard or another app invalidates the observation instead of exposing the browser behind it.
        val active = visibleWindows.firstOrNull { it.isActive } ?: return null
        val ownOverlay = active.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            active.root?.let { overlayRoot ->
                try { overlayRoot.packageName?.toString() == packageName } finally { recycle(overlayRoot) }
            } == true
        val foreground = if (ownOverlay) visibleWindows.firstOrNull {
            it.isFocused && it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
        } else active.takeIf {
            it.isFocused && it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
        }
        val root = foreground?.root ?: return null
        try {
            val browser = root.packageName?.toString() ?: return null
            val ids = BrowserRepo.addressBarIds(browser)
            if (ids.isEmpty()) return null
            val candidates = ids.flatMap { id ->
                root.findAccessibilityNodeInfosByViewId(id).map { node ->
                    try {
                        val nativeVisible = node.isVisibleToUser && node.packageName?.toString() == browser && !inWebContent(node)
                        BrowserAddressNode(
                            viewId = node.viewIdResourceName,
                            // Do not even materialize text from a hidden or web-content candidate.
                            text = if (nativeVisible) node.text?.toString() else null,
                            visible = nativeVisible,
                            focused = node.isFocused || node.isAccessibilityFocused,
                            editable = node.isEditable,
                        )
                    } finally { recycle(node) }
                }
            }
            val repo = BrowserRepo.detect(browser, candidates) ?: return null
            return Address(browser, root.windowId, repo)
        } catch (_: RuntimeException) {
            // A disappearing window, stale node or OEM failure must never leave an old repository actionable.
            return null
        } finally { recycle(root) }
    }

    private fun inWebContent(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        var depth = 0
        while (parent != null) {
            val current = parent
            try {
                val name = current.className?.toString().orEmpty()
                if (name.contains("WebView", ignoreCase = true)) return true
                if (++depth > MAX_ANCESTORS) return true
                parent = current.parent
            } finally { recycle(current) }
        }
        return node.className?.toString()?.contains("WebView", ignoreCase = true) == true
    }

    private fun show(address: Address) {
        hide()
        val settings = AppSettings.get(this)
        val dark = when (settings.theme) {
            "dark" -> true
            "light" -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val colors = ColorThemes.of(settings.colorTheme).colors(dark)
        val density = resources.displayMetrics.density
        fun pixels(dp: Float) = (dp * density).toInt()
        val label = Messages.load(this, settings.locale).t("settings.browserHelper.openRepository", "repo" to address.repository.label)
        val view = TextView(this).apply {
            text = label
            contentDescription = label
            setTextColor(colors.text.toArgb())
            textSize = PullsType.base.fontSize.value
            typeface = when (AppFont.of(settings.appFont)) {
                AppFont.Geist -> ResourcesCompat.getFont(this@BrowserRepoService, R.font.geist_medium)
                AppFont.MonaSans -> ResourcesCompat.getFont(this@BrowserRepoService, R.font.mona_sans_medium)
                AppFont.System -> Typeface.create("sans-serif", Typeface.NORMAL)
                AppFont.Serif -> Typeface.create("serif", Typeface.NORMAL)
            }
            gravity = Gravity.CENTER
            setPadding(pixels(PullsSpace.x4.value), pixels(PullsSpace.x3.value), pixels(PullsSpace.x4.value), pixels(PullsSpace.x3.value))
            minHeight = pixels(PullsSpace.x6.value * 2)
            maxWidth = resources.displayMetrics.widthPixels - pixels(PullsSpace.x8.value)
            background = GradientDrawable().apply {
                setColor(colors.raised.toArgb())
                cornerRadius = pixels(PullsRadius.card.value).toFloat()
                setStroke(pixels(1f).coerceAtLeast(1), colors.border.toArgb())
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener {
                // Never open a cached URL after a tab/window change. Re-read the live address at the tap.
                val now = readAddress()
                hide()
                if (now != address || !BrowserHelperSettings.allowed(this@BrowserRepoService)) return@setOnClickListener
                val repo = address.repository
                val target = "https://github.com/${repo.owner}/${repo.repo}"
                runCatching {
                    startActivity(Intent(this@BrowserRepoService, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW).setData(Uri.parse(target))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }
            }
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = pixels(PullsSpace.x4.value)
            y = pixels(PullsSpace.x8.value * 3)
        }
        try {
            windowManager.addView(view, layout)
            button = view
            shown = address
        } catch (_: RuntimeException) { hide() }
    }

    private fun hide() {
        button?.let { runCatching { windowManager.removeViewImmediate(it) } }
        button = null
        shown = null
    }

    override fun onInterrupt() { handler.removeCallbacks(inspect); hide() }
    override fun onUnbind(intent: Intent?): Boolean { onInterrupt(); return super.onUnbind(intent) }
    override fun onDestroy() {
        onInterrupt()
        if (connected.get() === this) connected.clear()
        super.onDestroy()
    }

    companion object {
        private const val SETTLE_MS = 160L
        private const val RECHECK_MS = 600L
        private const val MAX_ANCESTORS = 64
        private var connected = WeakReference<BrowserRepoService>(null)
        internal fun refresh() { connected.get()?.let { it.handler.post { it.update() } } }
        @Suppress("DEPRECATION")
        private fun recycle(node: AccessibilityNodeInfo) { node.recycle() }
    }
}
