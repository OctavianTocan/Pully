package com.octaviantocan.pulls.data

/** Existing ephemeral NSURLSession transport has no cookie, credential or shared response cache. */
internal actual fun personalAuthTransport(): RpcTransport = DarwinRpcTransport()
