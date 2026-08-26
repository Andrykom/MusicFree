package com.mf.musicfree.util

import io.ktor.client.plugins.HttpRequestTimeoutException

fun Throwable.isNetworkError(): Boolean {
    return this is java.net.UnknownHostException ||
            this is java.net.SocketTimeoutException ||
            this is java.io.IOException ||
            this is HttpRequestTimeoutException
}