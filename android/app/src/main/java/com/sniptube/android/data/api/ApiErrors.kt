package com.sniptube.android.data.api

sealed class SniptubeApiException(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)

class ServerTimeoutException(cause: Throwable) : SniptubeApiException(
    "The Sniptube server took too long to respond. Check the connection and try again.",
    cause,
)

class ServerConnectionException(cause: Throwable) : SniptubeApiException(
    "Cannot reach the Sniptube server. Check its address and your network connection.",
    cause,
)

class ServerAuthenticationException(val statusCode: Int, detail: String?) : SniptubeApiException(
    detail ?: "The server requires authentication that this app cannot currently provide.",
)

class ServerHttpException(
    val statusCode: Int,
    detail: String?,
) : SniptubeApiException(detail ?: "The Sniptube server returned HTTP $statusCode.")

class InvalidServerResponseException(cause: Throwable? = null) : SniptubeApiException(
    "The Sniptube server returned a response this app could not read.",
    cause,
)
