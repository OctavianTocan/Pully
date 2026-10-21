package com.octaviantocan.pulls.data

import com.octaviantocan.pulls.core.sha256Hex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Blocking compatibility for existing analytics/live-server callers; feature repositories use suspend RPC directly. */
class PullsApi(server: String, headers: Map<String, String> = emptyMap()) {
    private val client = RpcClient(server, JvmRpcTransport(), headers)

    fun call(name: String, args: JsonObject = JsonObject(emptyMap())): JsonElement = runBlocking { client.call(name, args) }

    fun postEvents(body: String): Int = runBlocking { client.postEvents(body) }
}

/** APK verification stays in the Android/JVM updater path. */
fun digestMatches(bytes: ByteArray, expected: String): Boolean = sha256Hex(bytes).equals(expected, ignoreCase = true)
