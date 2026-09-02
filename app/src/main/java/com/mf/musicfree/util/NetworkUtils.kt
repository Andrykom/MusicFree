package com.mf.musicfree.util

import io.ktor.client.plugins.HttpRequestTimeoutException

fun Throwable.isNetworkError(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is java.net.UnknownHostException ||
            cause is java.net.SocketTimeoutException ||
            cause is java.nio.channels.UnresolvedAddressException ||
            cause is HttpRequestTimeoutException
        ) return true
        // Любой IOException считаем сетевой (ConnectException, etc.)
        if (cause is java.io.IOException) return true
        cause = cause.cause
    }
    return false
}