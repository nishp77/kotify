package com.dominiczirbel.network.oauth

import com.dominiczirbel.network.Spotify
import com.dominiczirbel.network.await
import com.dominiczirbel.network.bodyFromJson
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.Desktop
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * https://developer.spotify.com/documentation/general/guides/authorization-guide/
 */
class OAuth private constructor(
    private val clientId: String,
    private val codeVerifier: String,
    private val state: String,
    private val redirectUri: String,
    val authorizationUrl: HttpUrl
) {
    private var consumed: Boolean = false

    /**
     * Invoked when the user grants permissions to an OAuth flow started by [start].
     *
     * In order to continue the flow (i.e. find the authorization code), we need the [redirectedUri] which is the URI
     * generated by the Spotify API, based on the original [redirectUri], with query parameters to indicate the result
     * of the permissions grant (the flow [state] and the authorization code or an error if the user denied the
     * request).
     *
     * Returns true if the authorization was completed successfully or false if the user denied it. Throws an
     * [IllegalArgumentException] if the [redirectedUri] does not meet the expected specifications, e.g. does not have
     * the same domain as the [redirectUri] or the same [state].
     *
     * This method should only be called once for each [OAuth] object (since the authorization code can only be used
     * once), subsequent calls after the first will immediately throw an [IllegalStateException].
     */
    suspend fun onRedirect(redirectedUri: String): Boolean {
        synchronized(this) {
            check(!consumed) { "OAuth flow was already finished" }
            consumed = true
        }

        val redirectedUrl = redirectedUri.toHttpUrl()
        require(redirectedUrl.topPrivateDomain() == redirectUri.toHttpUrl().topPrivateDomain()) {
            "redirected URL has a different top private domain; expected " +
                "${redirectUri.toHttpUrl().topPrivateDomain()} but was ${redirectedUrl.topPrivateDomain()}"
        }

        val urlState = redirectedUrl.queryParameterValues("state").firstOrNull()
        require(urlState == state) { "unexpected state; wanted $state but got $urlState" }

        val error = redirectedUrl.queryParameterValues("error").firstOrNull()
        if (error != null) {
            return false
        }

        val code = redirectedUrl.queryParameterValues("code").firstOrNull()
        requireNotNull(code) { "no code in redirect uri" }

        @Suppress("BlockingMethodInNonBlockingContext")
        val body = "client_id=$clientId&" +
            "grant_type=authorization_code&" +
            "code=$code&" +
            "redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}&" + // TODO don't use URLEncoder
            "code_verifier=$codeVerifier"

        val request = Request.Builder()
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .url("https://accounts.spotify.com/api/token")
            .build()

        val accessToken = Spotify.configuration.oauthOkHttpClient.newCall(request).await()
            .use { response -> response.bodyFromJson<AccessToken>() }

        AccessToken.Cache.put(accessToken)

        return true
    }

    companion object {
        /**
         * The default client ID, for my personal client.
         */
        const val DEFAULT_CLIENT_ID = "0c303117a0624fb0adc4832dd286cf39"

        /**
         * The default authorization scopes to request.
         *
         * See https://developer.spotify.com/documentation/general/guides/scopes/
         */
        private val DEFAULT_SCOPES = listOf(
            "playlist-modify-private",
            "playlist-modify-public",
            "playlist-read-collaborative",
            "playlist-read-private",
            "streaming",
            "ugc-image-upload",
            "user-follow-modify",
            "user-follow-read",
            "user-library-modify",
            "user-library-read",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "user-read-email",
            "user-read-playback-position",
            "user-read-playback-state",
            "user-read-private",
            "user-read-recently-played",
            "user-top-read",
        )

        /**
         * The default redirect URI when [start]ing a flow.
         *
         * Currently localhost; the user will then copy the redirected URL back into the application, which will parse
         * it for the authorization code.
         */
        private const val REDIRECT_URI_BASE = "http://localhost/"

        // number of bytes in the state buffer; 16 bytes -> 22 characters
        private const val STATE_BUFFER_SIZE = 16
        private val stateEncoder = Base64.getUrlEncoder().withoutPadding()

        /**
         * Starts a new OAuth flow for Authorization with Proof Key for Code Exchange (PKCE).
         *
         * This generates a [CodeChallenge] and state, which are used to create a [authorizationUrl], which is opened in
         * the user's web browser (failing silently if that operation is not supported). The returned [OAuth] object
         * contains information about the authorization request flow, in particular to finish the authorization grant
         * via [onRedirect] when the user completes the flow and accepts/denies the permission request.
         */
        fun start(
            clientId: String = DEFAULT_CLIENT_ID,
            scopes: List<String> = DEFAULT_SCOPES,
            redirectUri: String = REDIRECT_URI_BASE
        ): OAuth {
            val state = generateState()
            val codeChallenge = CodeChallenge.generate()
            val authorizationUrl = authorizationUrl(
                clientId = clientId,
                scopes = scopes,
                redirectUri = redirectUri,
                codeChallenge = codeChallenge,
                state = state
            )

            redirectTo(authorizationUrl)

            return OAuth(
                state = state,
                codeVerifier = codeChallenge.verifier,
                clientId = clientId,
                redirectUri = redirectUri,
                authorizationUrl = authorizationUrl
            )
        }

        /**
         * Generates a new random string which can serve as state for the current OAuth flow, to prevent CSRF attacks.
         *
         * Only visible so it can be unit tested; do not reference directly.
         */
        internal fun generateState(random: SecureRandom = SecureRandom()): String {
            val buffer = ByteArray(STATE_BUFFER_SIZE)
            random.nextBytes(buffer)
            return stateEncoder.encodeToString(buffer)
        }

        /**
         * Returns the authorization URI for the Spotify API, which displays a permissions dialog to the user and then
         * redirects to [redirectUri] (with a authorization code if the user accepts or an error if the user declines as
         * query parameters).
         *
         * Only visible so it can be unit tested; do not reference directly.
         */
        internal fun authorizationUrl(
            clientId: String,
            scopes: List<String>,
            redirectUri: String,
            codeChallenge: CodeChallenge,
            state: String
        ): HttpUrl {
            return "https://accounts.spotify.com/authorize".toHttpUrl()
                .newBuilder()
                .addQueryParameter("client_id", clientId)
                .addEncodedQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", redirectUri)
                .addEncodedQueryParameter("code_challenge_method", "S256")
                .addEncodedQueryParameter("code_challenge", codeChallenge.challenge)
                .addQueryParameter("state", state)
                .addQueryParameter("scope", scopes.joinToString(separator = " "))
                .build()
        }

        /**
         * Attempts to open the given [url] in the user's browser.
         */
        private fun redirectTo(url: HttpUrl) {
            // TODO improve error handling, try other ways, etc
            val result = runCatching { Desktop.getDesktop().browse(url.toUri()) }
            if (result.isFailure) {
                println("Failed to open $url")
            }
        }
    }
}
