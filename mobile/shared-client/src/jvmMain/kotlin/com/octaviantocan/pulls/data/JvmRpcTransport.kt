package com.octaviantocan.pulls.data

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

/** Android/JVM adapter: redirects are never followed, and cancelling the caller closes its connection. */
class JvmRpcTransport : RpcTransport {
    override suspend fun execute(request: RpcRequest): RpcResponse = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        val cancelled = AtomicBoolean(false)
        continuation.invokeOnCancellation {
            cancelled.set(true)
            active.get()?.disconnect()
        }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (cancelled.get()) return@Runnable
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = request.connectTimeoutMs
                    readTimeout = request.readTimeoutMs
                    instanceFollowRedirects = false
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                }
                active.set(connection)
                if (cancelled.get()) return@Runnable
                connection.outputStream.use { it.write(request.body.encodeToByteArray()) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = ByteArrayOutputStream()
                stream?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(bytes.size() <= RPC_RESPONSE_LIMIT - count) { "The server response is too large." }
                        bytes.write(buffer, 0, count)
                    }
                }
                val response = RpcResponse(status, bytes.toByteArray().decodeToString(), connection.contentType.orEmpty(), connection.getHeaderField("Location").orEmpty())
                if (continuation.isActive) continuation.resume(response)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                connection?.disconnect()
                active.set(null)
            }
        })
    }
}
