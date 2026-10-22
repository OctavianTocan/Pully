package com.octaviantocan.pulls.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal actual fun personalAuthTransport(): RpcTransport = JvmPersonalAuthTransport()

/** Dedicated client: never consults URLConnection's process-wide cookies, auth cache or authenticator. */
internal class JvmPersonalAuthTransport : RpcTransport {
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).cache(null)
        .dispatcher(Dispatcher().apply { maxRequests = 3; maxRequestsPerHost = 3 })
        .build()

    override suspend fun execute(request: RpcRequest): RpcResponse = suspendCancellableCoroutine { continuation ->
        val bounded = client.newBuilder()
            .connectTimeout(request.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(request.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS).build()
        val native = Request.Builder().url(request.url).apply {
            request.headers.forEach { (name, value) -> header(name, value) }
            if (request.method == RpcMethod.Get) get()
            else post(request.body.toRequestBody("application/json".toMediaType()))
        }.build()
        val call = bounded.newCall(native)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val body = response.body
                        require((body?.contentLength() ?: 0) <= request.responseLimit) { "Sign-in response is too large." }
                        val bytes = ByteArrayOutputStream()
                        body?.byteStream()?.use { input ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(bytes.size() <= request.responseLimit - count) { "Sign-in response is too large." }
                                bytes.write(buffer, 0, count)
                            }
                        }
                        val result = RpcResponse(response.code, bytes.toByteArray().decodeToString(throwOnInvalidSequence = true), response.header("Content-Type").orEmpty(), response.header("Location").orEmpty())
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}
