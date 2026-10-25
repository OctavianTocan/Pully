package com.octaviantocan.pulls.data

/** Tavi25Oct: extractable APK credential, confined to staging workspace and staging downloads. */
fun stagingAccessHeaders(
    server: String,
    path: String,
    defaultServer: String,
    clientId: String,
    clientSecret: String,
): Map<String, String> {
    if (defaultServer != "https://pully-staging.octaviantocan.com" || server != defaultServer ||
        clientId.isBlank() || clientSecret.isBlank() ||
        !(path.startsWith("/workspace/") || path.startsWith("/download/staging/")) ||
        path.any { it == '?' || it == '#' || it == '\\' || it == '%' } ||
        path.split('/').any { it == "." || it == ".." }) return emptyMap()
    return mapOf("CF-Access-Client-Id" to clientId, "CF-Access-Client-Secret" to clientSecret)
}
