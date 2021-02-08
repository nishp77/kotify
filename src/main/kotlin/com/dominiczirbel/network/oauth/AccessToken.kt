package com.dominiczirbel.network.oauth

import com.dominiczirbel.network.Spotify
import com.dominiczirbel.network.await
import com.dominiczirbel.network.bodyFromJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Represents an access token which may be used to access the Spotify API.
 *
 * The [accessToken] itself can be used as an authorization header on requests to the Spotify API, alongside [tokenType]
 * (always "Bearer").
 *
 * Access tokens have an expiration time, namely [expiresIn] seconds after they are granted. [isExpired] checks the
 * expiration status of this token, based on the [received] time.
 *
 * Some access tokens (those granted via Authorization Code Flows) allow the client to access the Spotify API on behalf
 * of a particular user; these have a [scope] which specifies what API access the user has granted.
 *
 * Some access tokens (also those granted via an Authorization Code Flow) use a [refreshToken] to get a new token when
 * they have expired.
 *
 * TODO utility function to check the scopes (i.e. make sure that a given list has been granted)
 */
@Serializable
data class AccessToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    val scope: String? = null,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val received: Long = System.currentTimeMillis()
) {
    // TODO tighten by a few seconds to account for network time
    val isExpired
        get() = System.currentTimeMillis() >= received + TimeUnit.SECONDS.toMillis(expiresIn)

    /**
     * A simple in-memory and filesystem cache for a single [AccessToken].
     *
     * This is used to manage access tokens, typically granted by [OAuth], and allows storing the current token and
     * refreshing it when it is expired.
     */
    object Cache {
        /**
         * The file at which the access token is saved, relative to the current working directory.
         */
        internal val file = File("access_token.json")

        /**
         * Whether to log access token updates to the console; used to disable logging when testing the cache directly.
         */
        internal var log: Boolean = true

        private var token: AccessToken? = null

        /**
         * Determines if the cache currently has a token (either in memory or on disk, loading it if there is one on
         * disk but not in memory).
         */
        val hasToken: Boolean
            get() = getFromCache() != null

        /**
         * Requires that the currently cached token has a [AccessToken.refreshToken], i.e. that it came from an
         * authorization code flow. If there is a non-refreshable access token, it is [clear]ed.
         */
        fun requireRefreshable() {
            if (getFromCache()?.refreshToken == null) {
                log("Current token is not refreshable, clearing")
                clear()
            }
        }

        /**
         * Gets the currently cached [AccessToken], or throw a [NoAccessTokenError] if there is no token cached.
         *
         * If the cached token has expired and has a [AccessToken.refreshToken], it is refreshed (i.e. a new
         * [AccessToken] is fetched based on the old [AccessToken.refreshToken]) and the new token is returned.
         */
        suspend fun getOrThrow(clientId: String = OAuth.DEFAULT_CLIENT_ID): AccessToken {
            return get(clientId) ?: throw NoAccessTokenError
        }

        /**
         * Gets the currently cached [AccessToken] or null if there is no token cached.
         *
         * If the cached token has expired and has a [AccessToken.refreshToken], it is refreshed (i.e. a new
         * [AccessToken] is fetched based on the old [AccessToken.refreshToken]) and the new token is returned.
         */
        suspend fun get(clientId: String = OAuth.DEFAULT_CLIENT_ID, refresh: Boolean = true): AccessToken? {
            val token = getFromCache() ?: return null

            if (refresh && token.isExpired) {
                log("Current access token is expired; refreshing")
                refresh(clientId)
            }

            return getFromCache()
        }

        /**
         * Puts the given [AccessToken] in the cache, immediately writing it to disk.
         */
        fun put(accessToken: AccessToken) {
            log("Putting new access token")
            token = accessToken
            save(accessToken)
        }

        /**
         * Clears the [AccessToken] cache, removing the currently cached token and deleting it on disk.
         */
        fun clear() {
            token = null
            Files.deleteIfExists(file.toPath())
            log("Cleared access token")
        }

        /**
         * Clears in-memory state of the cache, in order to test loading the token from disk. Should only be used in
         * tests.
         */
        internal fun reset() {
            token = null
        }

        /**
         * Returns the currently cached token, loading it from disk if there is not one in memory.
         */
        private fun getFromCache(): AccessToken? {
            return token ?: load().also { token = it }
        }

        /**
         * Writes [token] to disk.
         */
        private fun save(token: AccessToken) {
            val json = Json.encodeToString(token)
            Files.write(file.toPath(), json.split('\n'))
            log("Saved access token to $file")
        }

        /**
         * Reads the token from disk and returns it, or null if there is no token file.
         */
        private fun load(): AccessToken? {
            return try {
                FileReader(file).use { it.readLines().joinToString(separator = "\n") }
                    .let { Json.decodeFromString<AccessToken>(it) }
                    .also { log("Loaded access token from $file") }
            } catch (_: FileNotFoundException) {
                null.also { log("No saved access token at $file") }
            }
        }

        /**
         * Attempts to refresh the in-memory token via its [AccessToken.refreshToken] (or does nothing if it has no
         * refresh token) to get a fresh [AccessToken].
         *
         * If successful, the new access token is immediately available in-memory and written to disk.
         */
        private suspend fun refresh(clientId: String) {
            token?.refreshToken?.let { refreshToken ->
                val request = Request.Builder()
                    .post(
                        "grant_type=refresh_token&refresh_token=$refreshToken&client_id=$clientId"
                            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
                    )
                    .url("https://accounts.spotify.com/api/token")
                    .build()

                // TODO error handling
                Spotify.configuration.oauthOkHttpClient.newCall(request).await()
                    .use { response -> response.bodyFromJson<AccessToken>() }
                    .also {
                        log("Got refreshed access token")
                        token = it
                        save(it)
                    }
            }
        }

        private fun log(message: String) {
            if (log) {
                println(message)
            }
        }

        object NoAccessTokenError : Throwable()
    }
}
