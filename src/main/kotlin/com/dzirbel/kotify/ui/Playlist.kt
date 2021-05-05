package com.dzirbel.kotify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dzirbel.kotify.cache.LibraryCache
import com.dzirbel.kotify.cache.SpotifyCache
import com.dzirbel.kotify.network.model.FullPlaylist
import com.dzirbel.kotify.network.model.PlaylistTrack
import com.dzirbel.kotify.ui.common.ColumnByString
import com.dzirbel.kotify.ui.common.ColumnWidth
import com.dzirbel.kotify.ui.common.IndexColumn
import com.dzirbel.kotify.ui.common.InvalidateButton
import com.dzirbel.kotify.ui.common.LoadedImage
import com.dzirbel.kotify.ui.common.PageStack
import com.dzirbel.kotify.ui.common.Table
import com.dzirbel.kotify.ui.theme.Dimens
import com.dzirbel.kotify.ui.util.mutate
import com.dzirbel.kotify.util.formatDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.util.concurrent.TimeUnit

private class PlaylistPresenter(
    private val page: PlaylistPage,
    private val pageStack: MutableState<PageStack>,
    scope: CoroutineScope
) : Presenter<PlaylistPresenter.State?, PlaylistPresenter.Event>(
    scope = scope,
    key = page.playlistId,
    eventMergeStrategy = EventMergeStrategy.LATEST,
    startingEvents = listOf(Event.Load(invalidate = false)),
    initialState = null
) {

    data class State(
        val refreshing: Boolean,
        val playlist: FullPlaylist,
        val tracks: List<PlaylistTrack>?,
        val isSaved: Boolean?,
        val playlistUpdated: Long?
    )

    sealed class Event {
        data class Load(val invalidate: Boolean) : Event()
        data class ToggleSave(val save: Boolean) : Event()
    }

    override suspend fun reactTo(event: Event) {
        when (event) {
            is Event.Load -> {
                mutateState { it?.copy(refreshing = true) }

                if (event.invalidate) {
                    SpotifyCache.invalidate(id = page.playlistId)
                }

                val playlist = SpotifyCache.Playlists.getFullPlaylist(id = page.playlistId)
                pageStack.mutate { withPageTitle(title = page.titleFor(playlist)) }

                val isSaved = LibraryCache.savedPlaylists?.contains(playlist.id)

                mutateState {
                    State(
                        refreshing = false,
                        playlist = playlist,
                        playlistUpdated = SpotifyCache.lastUpdated(id = page.playlistId),
                        isSaved = isSaved,
                        tracks = null
                    )
                }

                val tracks = SpotifyCache.Playlists.getPlaylistTracks(
                    playlistId = page.playlistId,
                    paging = playlist.tracks
                )

                mutateState { it?.copy(tracks = tracks) }
            }

            is Event.ToggleSave -> {
                val savedPlaylists = if (event.save) {
                    SpotifyCache.Playlists.savePlaylist(id = page.playlistId)
                } else {
                    SpotifyCache.Playlists.unsavePlaylist(id = page.playlistId)
                }

                val isSaved = savedPlaylists?.contains(page.playlistId)
                mutateState { it?.copy(isSaved = isSaved) }
            }
        }
    }
}

private object AddedAtColumn : ColumnByString<PlaylistTrack>(header = "Added", width = ColumnWidth.Fill()) {
    private val PlaylistTrack.addedAtTimestamp
        get() = Instant.parse(addedAt.orEmpty()).toEpochMilli()

    override fun toString(item: PlaylistTrack, index: Int): String {
        return formatDateTime(timestamp = item.addedAtTimestamp, includeTime = false)
    }

    override fun compare(first: PlaylistTrack, firstIndex: Int, second: PlaylistTrack, secondIndex: Int): Int {
        return first.addedAtTimestamp.compareTo(second.addedAtTimestamp)
    }
}

@Composable
fun BoxScope.Playlist(pageStack: MutableState<PageStack>, page: PlaylistPage) {
    val scope = rememberCoroutineScope { Dispatchers.IO }
    val presenter = remember(page) { PlaylistPresenter(page = page, pageStack = pageStack, scope = scope) }

    ScrollingPage(scrollState = pageStack.value.currentScrollState, presenter = presenter) { state ->
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LoadedImage(url = state.playlist.images.firstOrNull()?.url)

                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
                        Text(state.playlist.name, fontSize = Dimens.fontTitle)

                        state.playlist.description
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { Text(it) }

                        Text(
                            "Created by ${state.playlist.owner.displayName}; " +
                                "${state.playlist.followers.total} followers"
                        )

                        val totalDurationMins = remember(state.tracks) {
                            state.tracks?.let { tracks ->
                                TimeUnit.MILLISECONDS.toMinutes(tracks.sumBy { it.track.durationMs.toInt() }.toLong())
                            }
                        }

                        Text("${state.playlist.tracks.total} songs, ${totalDurationMins ?: "<loading>"} min")

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ToggleSaveButton(isSaved = state.isSaved, size = Dimens.iconMedium) {
                                presenter.emitAsync(PlaylistPresenter.Event.ToggleSave(save = it))
                            }

                            PlayButton(contextUri = state.playlist.uri)
                        }
                    }
                }

                InvalidateButton(
                    refreshing = state.refreshing,
                    updated = state.playlistUpdated,
                    updatedFormat = { "Playlist last updated $it" },
                    updatedFallback = "Playlist never updated",
                    onClick = { presenter.emitAsync(PlaylistPresenter.Event.Load(invalidate = true)) }
                )
            }

            Spacer(Modifier.height(Dimens.space3))

            val tracks = state.tracks
            if (tracks == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                val columns = remember(pageStack) {
                    trackColumns(pageStack, includeTrackNumber = false)
                        .map { column -> column.mapped<PlaylistTrack> { it.track } }
                        .toMutableList()
                        .apply {
                            add(1, IndexColumn)

                            @Suppress("MagicNumber")
                            add(6, AddedAtColumn)
                        }
                }

                Table(columns = columns, items = tracks)
            }
        }
    }
}
