package com.dzirbel.kotify.network.oauth

import androidx.compose.runtime.mutableStateOf
import com.dzirbel.kotify.Application
import com.dzirbel.kotify.network.Spotify
import com.dzirbel.kotify.network.await
import com.dzirbel.kotify.network.bodyFromJson
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.FormBody
import okhttp3.Request
import java.io.FileNotFoundException
import java.nio.file.Files
import java.time.Instant
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
    val isExpired
        get() = System.currentTimeMillis() >= received + TimeUnit.SECONDS.toMillis(expiresIn)

    /**
     * A parsed list of the scopes granted by this [AccessToken], or null if it was not granted with [scope].
     */
    val scopes: List<String>? by lazy { scope?.split(' ') }

    /**
     * The [Instant] at which this [AccessToken] was received.
     */
    val receivedInstant: Instant by lazy { Instant.ofEpochMilli(received) }

    /**
     * The [Instant] at which this [AccessToken] expires.
     */
    val expiresInstant: Instant by lazy { receivedInstant.plusSeconds(expiresIn) }

    /**
     * Determines whether [scope] is granted by this [AccessToken].
     */
    fun hasScope(scope: String): Boolean = scopes?.any { it.equals(scope, ignoreCase = true) } == true

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
        internal val file = Application.cacheDir.resolve("access_token.json")

        /**
         * Whether to log access token updates to the console; used to disable logging when testing the cache directly.
         */
        internal var log: Boolean = true

        /**
         * Encode defaults in order to include [AccessToken.received].
         */
        private val json = Json {
            encodeDefaults = true
            prettyPrint = true
        }

        private val tokenState = mutableStateOf(load())

        private var refreshJob: Job? = null

        /**
         * The currently cached token, loading it from disk if there is not one in memory.
         *
         * This token is backed by a [androidx.compose.runtime.MutableState], so reads and writes to it will trigger
         * recompositions.
         */
        var token: AccessToken?
            get() = tokenState.value ?: load().also { tokenState.value = it }
            private set(value) {
                tokenState.value = value
            }

        /**
         * Determines if the cache currently has a token (either in memory or on disk, loading it if there is one on
         * disk but not in memory).
         */
        val hasToken: Boolean
            get() = token != null

        /**
         * Requires that the currently cached token has a [AccessToken.refreshToken], i.e. that it came from an
         * authorization code flow. If there is a non-refreshable access token, it is [clear]ed.
         */
        fun requireRefreshable() {
            if (token?.refreshToken == null) {
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
        suspend fun get(clientId: String = OAuth.DEFAULT_CLIENT_ID): AccessToken? {
            val token = token ?: return null

            if (token.isExpired) {
                log("Current access token is expired; refreshing")
                refresh(clientId)
            }

            return this.token
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
         * Writes [token] to disk.
         */
        private fun save(token: AccessToken) {
            file.outputStream().use { outputStream ->
                json.encodeToStream(token, outputStream)
            }
            log("Saved access token to $file")
        }

        /**
         * Reads the token from disk and returns it, or null if there is no token file.
         */
        private fun load(): AccessToken? {
            return try {
                file.inputStream()
                    .use { json.decodeFromStream<AccessToken>(it) }
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
            suspend fun fetchRefresh(refreshToken: String, clientId: String) {
                val body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", clientId)
                    .build()

                val request = Request.Builder()
                    .post(body)
                    .url("https://accounts.spotify.com/api/token")
                    .build()

                val token = try {
                    Spotify.configuration.oauthOkHttpClient.newCall(request).await()
                        .use { response -> response.bodyFromJson<AccessToken>() }
                } catch (_: Throwable) {
                    clear()
                    null
                }

                token?.let {
                    log("Got refreshed access token")
                    this.token = it
                    save(it)
                }
            }

            token?.refreshToken?.let { refreshToken ->
                val job = synchronized(this) {
                    refreshJob ?: GlobalScope.async {
                        fetchRefresh(refreshToken = refreshToken, clientId = clientId)
                        refreshJob = null
                    }.also { refreshJob = it }
                }

                job.join()
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
