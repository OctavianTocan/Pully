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

    /**
     * Sends a batch of usage events (`{"events":[...]}`, already encoded) to `POST /api/events`, on the same server and
     * with the same Access headers as [call]. Returns the HTTP status; the server answers 204 when it took them.
     */
    fun postEvents(body: String): Int {
        val connection = open("${server.trimEnd('/')}/api/events", "POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            runCatching { (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() } }
            return status
        } finally {
            connection.disconnect()
        }
    }

    private fun read(connection: HttpURLConnection): JsonElement {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        if (text.isBlank()) throw ServerError(status, "The server returned nothing (HTTP $status).")
        val parsed = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ServerError(status, e.message ?: e.toString(), e)
        }
        parsed["error"]?.let { throw ServerError(status, it.jsonPrimitive.content) }
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

/** A call the server answered with an error, or with something that is not an answer; [status] is its HTTP status. */
class ServerError(val status: Int, message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Confirms a published APK matches the digest in latest.json. Used by the updater before install. */
fun digestMatches(bytes: ByteArray, expected: String): Boolean = sha256Hex(bytes).equals(expected, ignoreCase = true)
