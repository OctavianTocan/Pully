package com.octaviantocan.pulls.core

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** The only information retained after inspecting a browser's address bar. */
data class BrowserRepository(val owner: String, val repo: String) {
    val label: String get() = "$owner/$repo"
}

/**
 * A snapshot of browser chrome, not page content. [inWebContent] must include web
 * ancestors, even when a virtual page node presents a native-looking resource ID.
 * Only [text] is inspected: descriptions, titles, suggestions and page links are
 * not evidence of the current address.
 */
data class BrowserAddressNode(
    val viewId: String?,
    val text: String?,
    val visible: Boolean = true,
    val focused: Boolean = false,
    val editable: Boolean = false,
    val inWebContent: Boolean = false,
)

/** Conservative, local-only policy for the browser's committed repository address. */
object BrowserRepo {
    // These are exact native address-field IDs, not patterns for arbitrary nodes.
    // Browser versions can change them: a missing field deliberately yields no repo.
    // Chromium's url_bar follows its upstream layout. Every adapter still needs an
    // on-device accessibility check against the browser version being supported.
    private val addressFields: Map<String, Set<String>> = buildMap {
        listOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "org.chromium.chrome",
            "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly",
            "com.microsoft.emmx", "com.microsoft.emmx.beta", "com.microsoft.emmx.dev",
            "com.microsoft.emmx.canary",
            "com.vivaldi.browser", "com.vivaldi.browser.snapshot",
        ).forEach { put(it, setOf("$it:id/url_bar")) }
        listOf("org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix")
            .forEach { put(it, setOf("$it:id/mozac_browser_toolbar_url_view")) }
        listOf("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta")
            .forEach { put(it, setOf("$it:id/location_bar_edit_text")) }
        listOf("com.opera.browser", "com.opera.browser.beta")
            .forEach { put(it, setOf("$it:id/url_field")) }
    }

    // GitHub.com managed users can append an underscore and a 3–8 character
    // enterprise shortcode; ordinary usernames have alphanumeric/hyphen parts.
    private val ownerName = Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*(?:_[A-Za-z0-9]{3,8})?")
    private val repositoryName = Regex("[A-Za-z0-9_.-]{1,100}")

    // GitHub routes which can have two path components without naming a repository.
    // Do not include real owners such as github, actions or education here.
    private val reservedOwners = setOf(
        "about", "account", "admin", "api", "apps", "auth", "blog", "business",
        "careers", "codespaces", "collections", "contact", "copilot", "customer-stories",
        "dashboard", "devices", "discussions", "enterprise", "enterprises", "events",
        "explore", "features", "gist", "help", "home", "inbox", "integrations", "issues",
        "join", "login", "logout", "marketplace", "mobile", "new", "notifications", "orgs",
        "organizations", "password_reset", "pricing", "pulls", "readme", "repositories",
        "resources", "search", "security", "sessions", "settings", "signup", "site", "solutions", "sponsors",
        "stars", "topics", "trending", "users", "watching",
    )

    /** Empty for unknown packages; callers must not fall back to scanning page text. */
    fun addressBarIds(packageName: String): Set<String> = addressFields[packageName].orEmpty()

    /**
     * All visible native address candidates must agree. A focused field may contain
     * text the person has not navigated to yet, so it invalidates the observation.
     * Missing, malformed or conflicting evidence never falls back to a previous URL.
     */
    fun detect(packageName: String, nodes: Iterable<BrowserAddressNode>): BrowserRepository? {
        val ids = addressBarIds(packageName)
        if (ids.isEmpty()) return null
        var detected: BrowserRepository? = null
        for (node in nodes) {
            if (!node.visible || node.inWebContent || node.viewId !in ids) continue
            if (node.focused) return null
            val candidate = parseAddress(node.text ?: return null) ?: return null
            val previous = detected
            if (previous != null && (
                !previous.owner.equals(candidate.owner, ignoreCase = true) ||
                    !previous.repo.equals(candidate.repo, ignoreCase = true)
                )) return null
            detected = detected ?: candidate
        }
        return detected
    }

    /**
     * Parses an entire address, never a link embedded in prose. This establishes a
     * GitHub repository-shaped address, not whether that repository exists or is
     * accessible. Repository subpages resolve to the root; no full URL is returned.
     * Uncertain display forms (encoded, elided or internationalized) fail closed.
     */
    fun parseAddress(raw: String): BrowserRepository? {
        if (raw.isEmpty() || raw.length > 8192) return null
        if (raw.any { it.code !in 33..126 || it == '%' || it == '\\' }) return null
        val address = when {
            raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("github.com/", ignoreCase = true) ||
                raw.startsWith("www.github.com/", ignoreCase = true) -> "https://$raw"
            else -> return null
        }
        val uri = try {
            URI(address)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque) return null
        if (uri.rawUserInfo != null || uri.port != -1) return null
        if (!uri.host.equals("github.com", ignoreCase = true) &&
            !uri.host.equals("www.github.com", ignoreCase = true)
        ) return null
        // Exact authority also excludes an empty explicit port and a trailing dot.
        if (!uri.rawAuthority.equals(uri.host, ignoreCase = true)) return null
        val path = uri.rawPath ?: return null
        if (!path.startsWith('/') || path.contains("//")) return null
        val parts = path.removePrefix("/").removeSuffix("/").split('/')
        if (parts.size < 2 || parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        val owner = parts[0]
        val repo = parts[1]
        if (owner.length > 39 || !ownerName.matches(owner)) return null
        if (owner.lowercase(Locale.ROOT) in reservedOwners) return null
        if (!repositoryName.matches(repo) || repo == "." || repo == "..") return null
        return BrowserRepository(owner, repo)
    }
}
