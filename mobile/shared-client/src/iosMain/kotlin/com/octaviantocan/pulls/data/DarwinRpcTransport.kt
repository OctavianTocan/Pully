package com.octaviantocan.pulls.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.create
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ephemeral iOS requests: no shared cookies/cache, no redirects, bounded streaming and native cancellation. */
@OptIn(ExperimentalForeignApi::class)
class DarwinRpcTransport : RpcTransport {
    override suspend fun execute(request: RpcRequest): RpcResponse = suspendCancellableCoroutine { continuation ->
        val nativeRequest = NSMutableURLRequest.requestWithURL(requireNotNull(NSURL(string = request.url)) { "Invalid server address." }).apply {
            setHTTPMethod("POST")
            setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
            setValue("application/json", forHTTPHeaderField = "Content-Type")
            request.headers.forEach { (name, value) -> setValue(value, forHTTPHeaderField = name) }
            val bytes = request.body.encodeToByteArray()
            setHTTPBody(if (bytes.isEmpty()) null else bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            })
        }
        val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
            HTTPShouldSetCookies = false
            HTTPCookieStorage = null
            URLCache = null
            URLCredentialStorage = null
            timeoutIntervalForRequest = request.readTimeoutMs / 1000.0
            timeoutIntervalForResource = request.timeoutMs / 1000.0
        }
        var session: NSURLSession? = null
        val delegate = RpcSessionDelegate { response, failure ->
            if (continuation.isActive) {
                if (failure != null) continuation.resumeWithException(failure)
                else continuation.resume(checkNotNull(response))
            }
            session?.finishTasksAndInvalidate()
        }
        val nativeSession = NSURLSession.sessionWithConfiguration(configuration, delegate, null)
        session = nativeSession
        val task = nativeSession.dataTaskWithRequest(nativeRequest)
        continuation.invokeOnCancellation {
            task.cancel()
            nativeSession.invalidateAndCancel()
        }
        if (continuation.isActive) task.resume() else nativeSession.invalidateAndCancel()
    }
}

/** NSURLSession invokes these delegate callbacks serially on its private operation queue. */
@OptIn(ExperimentalForeignApi::class)
private class RpcSessionDelegate(private val completed: (RpcResponse?, Exception?) -> Unit) : NSObject(), NSURLSessionDataDelegateProtocol {
    private val chunks = mutableListOf<ByteArray>()
    private var size = 0
    private var finished = false

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        if (didReceiveResponse.expectedContentLength > RPC_RESPONSE_LIMIT.toLong()) {
            finish(null, IllegalStateException("The server response is too large."))
            completionHandler(NSURLSessionResponseCancel)
            session.invalidateAndCancel()
        } else {
            completionHandler(NSURLSessionResponseAllow)
        }
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        if (finished) return
        if (didReceiveData.length > (RPC_RESPONSE_LIMIT - size).toULong()) {
            finish(null, IllegalStateException("The server response is too large."))
            dataTask.cancel()
            session.invalidateAndCancel()
            return
        }
        val bytes = ByteArray(didReceiveData.length.toInt()).apply {
            if (isNotEmpty()) usePinned { pinned -> memcpy(pinned.addressOf(0), didReceiveData.bytes, didReceiveData.length) }
        }
        size += bytes.size
        chunks += bytes
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        if (finished) return
        if (didCompleteWithError != null) {
            finish(null, IllegalStateException("Cannot reach the Pully server."))
            return
        }
        val response = task.response as? NSHTTPURLResponse
        if (response == null) {
            finish(null, IllegalStateException("Missing HTTP response."))
            return
        }
        val bytes = ByteArray(size)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        finish(RpcResponse(response.statusCode.toInt(), bytes.decodeToString(), response.MIMEType.orEmpty()), null)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        completionHandler(null)
    }

    private fun finish(response: RpcResponse?, failure: Exception?) {
        if (finished) return
        finished = true
        chunks.clear()
        completed(response, failure)
    }
}
