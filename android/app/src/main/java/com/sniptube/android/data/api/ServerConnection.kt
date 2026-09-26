package com.sniptube.android.data.api

import android.content.SharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ServerConnection private constructor(val baseUrl: HttpUrl) {
    val identity: String = baseUrl.toString()

    fun endpoint(vararg pathSegments: String): HttpUrl {
        val builder = baseUrl.newBuilder()
        pathSegments.forEach(builder::addPathSegment)
        return builder.build()
    }

    fun resolveServerUrl(pathOrUrl: String): HttpUrl {
        val resolved = baseUrl.resolve(pathOrUrl)
            ?: throw InvalidServerUrlException("The server returned an invalid file URL.")
        if (
            resolved.scheme != baseUrl.scheme ||
            resolved.host != baseUrl.host ||
            resolved.port != baseUrl.port
        ) {
            throw InvalidServerUrlException("The server returned a file URL for a different host.")
        }
        return resolved
    }

    companion object {
        fun parse(rawUrl: String): ServerConnection {
            val value = rawUrl.trim()
            if (value.isEmpty()) {
                throw InvalidServerUrlException("Enter the Sniptube server URL.")
            }
            val parsed = value.toHttpUrlOrNull()
                ?: throw InvalidServerUrlException("Enter a complete http:// or https:// server URL.")
            if (parsed.scheme != "http" && parsed.scheme != "https") {
                throw InvalidServerUrlException("Only http:// and https:// server URLs are supported.")
            }
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
                throw InvalidServerUrlException("Do not put credentials in the server URL.")
            }
            if (parsed.query != null || parsed.fragment != null) {
                throw InvalidServerUrlException("The server URL cannot contain a query or fragment.")
            }

            val normalized = if (parsed.encodedPath.endsWith('/')) {
                parsed
            } else {
                parsed.newBuilder().addPathSegment("").build()
            }
            return ServerConnection(normalized)
        }
    }
}

class InvalidServerUrlException(message: String) : IllegalArgumentException(message)

interface ConnectionSettings {
    fun readServerUrl(): String?

    fun writeServerUrl(value: String)
}

class SharedPreferencesConnectionSettings(
    private val preferences: SharedPreferences,
) : ConnectionSettings {
    override fun readServerUrl(): String? = preferences.getString(SERVER_URL_KEY, null)

    override fun writeServerUrl(value: String) {
        preferences.edit().putString(SERVER_URL_KEY, value).apply()
    }

    private companion object {
        const val SERVER_URL_KEY = "server_url"
    }
}

class ServerConnectionStore(
    private val settings: ConnectionSettings,
) {
    fun loadOrNull(): ServerConnection? = settings.readServerUrl()?.let(ServerConnection::parse)

    fun load(): ServerConnection = loadOrNull()
        ?: throw IllegalStateException("Configure your Sniptube server before connecting.")

    fun update(rawUrl: String): ServerConnection {
        val connection = ServerConnection.parse(rawUrl)
        settings.writeServerUrl(connection.baseUrl.toString())
        return connection
    }
}
