package com.octaviantocan.pulls.data

import com.octaviantocan.pulls.core.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the pulls.review server's `/api/rpc`, the same actions the web app runs. */
class PullsApi(
    private val server: String,
    private val headers: Map<String, String> = emptyMap(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun call(name: String, args: JsonObject = JsonObject(emptyMap())): JsonElement {
        val connection = open("${server.trimEnd('/')}/api/rpc", "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val body = buildJsonObject {
            put("name", name)
            put("args", args)
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        return read(connection)
    }

    private fun read(connection: HttpURLConnection): JsonElement {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        if (text.isBlank()) error("The server returned nothing (HTTP ${connection.responseCode}).")
        val parsed = json.parseToJsonElement(text).jsonObject
        parsed["error"]?.let { error(it.jsonPrimitive.content) }
        return parsed["result"] ?: JsonNull
    }

    private fun open(url: String, method: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 70_000
            instanceFollowRedirects = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
    }
}

/** Confirms a published APK matches the digest in latest.json. Used by the updater before install. */
fun digestMatches(bytes: ByteArray, expected: String): Boolean = sha256Hex(bytes).equals(expected, ignoreCase = true)
