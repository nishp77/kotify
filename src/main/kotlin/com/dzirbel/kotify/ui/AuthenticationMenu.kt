package com.dzirbel.kotify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dzirbel.kotify.cache.LibraryCache
import com.dzirbel.kotify.network.model.PrivateSpotifyUser
import com.dzirbel.kotify.network.oauth.AccessToken
import com.dzirbel.kotify.ui.components.VerticalSpacer
import com.dzirbel.kotify.ui.components.liveRelativeDateText
import com.dzirbel.kotify.ui.theme.Dimens

private val MAX_WIDTH = 500.dp

@Composable
fun AuthenticationMenu(user: PrivateSpotifyUser?) {
    Column(
        modifier = Modifier.padding(Dimens.space3).widthIn(max = MAX_WIDTH),
        verticalArrangement = Arrangement.spacedBy(Dimens.space2)
    ) {
        if (user == null) {
            Text("Not authenticated")
        } else {
            Text("Authenticated as ${user.displayName} [${user.id}]")
        }

        AccessToken.Cache.token?.let { token ->
            Text("Access token: ${token.accessToken}")

            Text("Refreshable: ${token.refreshToken != null}")

            Text("${token.scopes?.size} scopes: ${token.scope}")

            val receivedRelative = liveRelativeDateText(timestamp = token.receivedInstant.toEpochMilli())
            val expiresRelative = liveRelativeDateText(timestamp = token.expiresInstant.toEpochMilli())
            Text(
                "Received at ${token.receivedInstant} ($receivedRelative); " +
                    "expires at ${token.expiresInstant} ($expiresRelative)"
            )
        }

        VerticalSpacer(Dimens.space2)

        Button(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            onClick = {
                LibraryCache.clear()
                AccessToken.Cache.clear()
            },
        ) {
            Text("Sign out")
        }
    }
}
