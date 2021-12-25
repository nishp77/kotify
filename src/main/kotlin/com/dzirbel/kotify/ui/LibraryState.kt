package com.dzirbel.kotify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dzirbel.kotify.cache.LibraryCache
import com.dzirbel.kotify.cache.SpotifyCache
import com.dzirbel.kotify.network.model.FullSpotifyAlbum
import com.dzirbel.kotify.network.model.FullSpotifyArtist
import com.dzirbel.kotify.network.model.FullSpotifyPlaylist
import com.dzirbel.kotify.network.model.FullSpotifyTrack
import com.dzirbel.kotify.network.model.SimplifiedSpotifyAlbum
import com.dzirbel.kotify.network.model.SimplifiedSpotifyArtist
import com.dzirbel.kotify.network.model.SimplifiedSpotifyPlaylist
import com.dzirbel.kotify.network.model.SimplifiedSpotifyTrack
import com.dzirbel.kotify.network.model.SpotifyTrack
import com.dzirbel.kotify.ui.components.HorizontalSpacer
import com.dzirbel.kotify.ui.components.InvalidateButton
import com.dzirbel.kotify.ui.components.PageStack
import com.dzirbel.kotify.ui.components.SimpleTextButton
import com.dzirbel.kotify.ui.components.table.ColumnByNumber
import com.dzirbel.kotify.ui.components.table.ColumnByRelativeDateText
import com.dzirbel.kotify.ui.components.table.ColumnByString
import com.dzirbel.kotify.ui.components.table.Sort
import com.dzirbel.kotify.ui.components.table.SortOrder
import com.dzirbel.kotify.ui.components.table.Table
import com.dzirbel.kotify.ui.theme.Colors
import com.dzirbel.kotify.ui.theme.Dimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow

private val RATINGS_TABLE_WIDTH = 750.dp

private class LibraryStatePresenter(scope: CoroutineScope) :
    Presenter<LibraryStatePresenter.State?, LibraryStatePresenter.Event>(
        scope = scope,
        eventMergeStrategy = EventMergeStrategy.LATEST,
        startingEvents = listOf(Event.Load),
        initialState = null
    ) {

    data class State(
        val artists: List<LibraryCache.CachedArtist>?,
        val artistsUpdated: Long?,

        val albums: List<LibraryCache.CachedAlbum>?,
        val albumsUpdated: Long?,

        val playlists: List<LibraryCache.CachedPlaylist>?,
        val playlistsUpdated: Long?,

        val tracks: List<LibraryCache.CachedTrack>?,
        val tracksUpdated: Long?,

        val ratedTracks: List<SpotifyTrack>,

        val refreshingSavedArtists: Boolean = false,
        val refreshingArtists: Set<String> = emptySet(),

        val refreshingSavedAlbums: Boolean = false,

        val refreshingSavedTracks: Boolean = false,

        val refreshingSavedPlaylists: Boolean = false
    )

    sealed class Event {
        object Load : Event()

        object RefreshSavedArtists : Event()
        object RefreshSavedAlbums : Event()
        object RefreshSavedTracks : Event()
        object RefreshSavedPlaylists : Event()

        object FetchMissingArtists : Event()
        object InvalidateArtists : Event()
        object FetchMissingArtistAlbums : Event()
        object InvalidateArtistAlbums : Event()

        object FetchMissingAlbums : Event()
        object InvalidateAlbums : Event()

        object FetchMissingTracks : Event()
        object InvalidateTracks : Event()

        object ClearAllRatings : Event()

        object FetchMissingPlaylists : Event()
        object InvalidatePlaylists : Event()
        object FetchMissingPlaylistTracks : Event()
        object InvalidatePlaylistTracks : Event()
    }

    override suspend fun reactTo(event: Event) {
        when (event) {
            Event.Load -> {
                val state = State(
                    artists = LibraryCache.cachedArtists,
                    artistsUpdated = LibraryCache.artistsUpdated,
                    albumsUpdated = LibraryCache.albumsUpdated,
                    albums = LibraryCache.cachedAlbums,
                    playlistsUpdated = LibraryCache.playlistsUpdated,
                    playlists = LibraryCache.cachedPlaylists,
                    tracks = LibraryCache.cachedTracks,
                    tracksUpdated = LibraryCache.tracksUpdated,
                    ratedTracks = SpotifyCache.Ratings.ratedTracks().orEmpty().let { trackIds ->
                        SpotifyCache.Tracks.getTracks(ids = trackIds.toList())
                    },
                )

                mutateState { state }
            }

            Event.RefreshSavedArtists -> {
                mutateState { it?.copy(refreshingSavedArtists = true) }

                SpotifyCache.invalidate(SpotifyCache.GlobalObjects.SavedArtists.ID)

                SpotifyCache.Artists.getSavedArtists()

                val artists = LibraryCache.cachedArtists
                val artistsUpdated = LibraryCache.artistsUpdated
                mutateState {
                    it?.copy(
                        artists = artists,
                        artistsUpdated = artistsUpdated,
                        refreshingSavedArtists = false
                    )
                }
            }

            Event.RefreshSavedAlbums -> {
                mutateState { it?.copy(refreshingSavedAlbums = true) }

                SpotifyCache.invalidate(SpotifyCache.GlobalObjects.SavedAlbums.ID)

                SpotifyCache.Albums.getSavedAlbums()

                val albums = LibraryCache.cachedAlbums
                val albumsUpdated = LibraryCache.albumsUpdated
                mutateState {
                    it?.copy(
                        albums = albums,
                        albumsUpdated = albumsUpdated,
                        refreshingSavedAlbums = false
                    )
                }
            }

            Event.RefreshSavedTracks -> {
                mutateState { it?.copy(refreshingSavedTracks = true) }

                SpotifyCache.invalidate(SpotifyCache.GlobalObjects.SavedTracks.ID)

                SpotifyCache.Tracks.getSavedTracks()

                val tracks = LibraryCache.cachedTracks
                val tracksUpdated = LibraryCache.tracksUpdated
                mutateState {
                    it?.copy(
                        tracks = tracks,
                        tracksUpdated = tracksUpdated,
                        refreshingSavedTracks = false
                    )
                }
            }

            Event.RefreshSavedPlaylists -> {
                mutateState { it?.copy(refreshingSavedPlaylists = true) }

                SpotifyCache.invalidate(SpotifyCache.GlobalObjects.SavedPlaylists.ID)

                SpotifyCache.Playlists.getSavedPlaylists()

                val playlists = LibraryCache.cachedPlaylists
                val playlistsUpdated = LibraryCache.playlistsUpdated
                mutateState {
                    it?.copy(
                        playlists = playlists,
                        playlistsUpdated = playlistsUpdated,
                        refreshingSavedPlaylists = false
                    )
                }
            }

            Event.FetchMissingArtists -> {
                val missingIds = requireNotNull(LibraryCache.artists?.filterValues { it !is FullSpotifyArtist })
                SpotifyCache.Artists.getFullArtists(ids = missingIds.keys.toList())

                val artists = LibraryCache.cachedArtists
                mutateState { it?.copy(artists = artists) }
            }

            Event.InvalidateArtists -> {
                val ids = requireNotNull(LibraryCache.artists?.filterValues { it != null })
                SpotifyCache.invalidate(ids.keys.toList())

                val artists = LibraryCache.cachedArtists
                mutateState { it?.copy(artists = artists) }
            }

            Event.FetchMissingArtistAlbums -> {
                val missingIds = requireNotNull(LibraryCache.artistAlbums?.filterValues { it == null })
                missingIds.keys
                    .asFlow()
                    .flatMapMerge { id ->
                        flow<Unit> { SpotifyCache.Artists.getArtistAlbums(artistId = id) }
                    }
                    .collect()

                val artists = LibraryCache.cachedArtists
                mutateState { it?.copy(artists = artists) }
            }

            Event.InvalidateArtistAlbums -> {
                val ids = requireNotNull(LibraryCache.artists?.filterValues { it != null })
                SpotifyCache.invalidate(ids.keys.map { SpotifyCache.GlobalObjects.ArtistAlbums.idFor(artistId = it) })

                val artists = LibraryCache.cachedArtists
                mutateState { it?.copy(artists = artists) }
            }

            Event.FetchMissingAlbums -> {
                val missingIds = requireNotNull(LibraryCache.albums?.filterValues { it !is FullSpotifyAlbum })
                SpotifyCache.Albums.getAlbums(ids = missingIds.keys.toList())

                val albums = LibraryCache.cachedAlbums
                mutateState { it?.copy(albums = albums) }
            }

            Event.InvalidateAlbums -> {
                val ids = requireNotNull(LibraryCache.albums?.filterValues { it != null })
                SpotifyCache.invalidate(ids.keys.toList())

                val albums = LibraryCache.cachedAlbums
                mutateState { it?.copy(albums = albums) }
            }

            Event.FetchMissingTracks -> {
                val missingIds = requireNotNull(LibraryCache.tracks?.filterValues { it !is FullSpotifyTrack })
                SpotifyCache.Tracks.getFullTracks(ids = missingIds.keys.toList())

                val tracks = LibraryCache.cachedTracks
                mutateState { it?.copy(tracks = tracks) }
            }

            Event.InvalidateTracks -> {
                val ids = requireNotNull(LibraryCache.tracks?.filterValues { it != null })
                SpotifyCache.invalidate(ids.keys.toList())

                val tracks = LibraryCache.cachedTracks
                mutateState { it?.copy(tracks = tracks) }
            }

            Event.ClearAllRatings -> {
                SpotifyCache.Ratings.clearAllRatings()
                mutateState { it?.copy(ratedTracks = emptyList()) }
            }

            Event.FetchMissingPlaylists -> {
                val missingIds = requireNotNull(LibraryCache.playlists?.filterValues { it !is FullSpotifyPlaylist })
                missingIds.keys
                    .asFlow()
                    .flatMapMerge { id ->
                        flow<Unit> { SpotifyCache.Playlists.getFullPlaylist(id = id) }
                    }
                    .collect()

                val playlists = LibraryCache.cachedPlaylists
                mutateState { it?.copy(playlists = playlists) }
            }

            Event.InvalidatePlaylists -> {
                val ids = requireNotNull(LibraryCache.playlists?.filterValues { it != null })
                SpotifyCache.invalidate(ids.keys.toList())

                val playlists = LibraryCache.cachedPlaylists
                mutateState { it?.copy(playlists = playlists) }
            }

            Event.FetchMissingPlaylistTracks -> {
                val missingIds = requireNotNull(LibraryCache.playlistTracks?.filterValues { it == null })

                missingIds.keys
                    .asFlow()
                    .flatMapMerge { id ->
                        flow<Unit> { SpotifyCache.Playlists.getPlaylistTracks(playlistId = id) }
                    }
                    .collect()

                val playlists = LibraryCache.cachedPlaylists
                mutateState { it?.copy(playlists = playlists) }
            }

            Event.InvalidatePlaylistTracks -> {
                val ids = requireNotNull(LibraryCache.playlistTracks?.filterValues { it != null })
                SpotifyCache.invalidate(ids.keys.toList())

                val playlists = LibraryCache.cachedPlaylists
                mutateState { it?.copy(playlists = playlists) }
            }
        }
    }
}

// TODO allow refreshing artist/album
private val artistColumns = listOf(
    object : ColumnByString<LibraryCache.CachedArtist>(name = "Name") {
        override fun toString(item: LibraryCache.CachedArtist, index: Int): String = item.artist?.name.orEmpty()
    },

    object : ColumnByString<LibraryCache.CachedArtist>(name = "ID") {
        override fun toString(item: LibraryCache.CachedArtist, index: Int): String = item.id
    },

    object : ColumnByString<LibraryCache.CachedArtist>(name = "Type") {
        override fun toString(item: LibraryCache.CachedArtist, index: Int): String {
            return item.artist?.let { it::class.java.simpleName }.orEmpty()
        }
    },

    object : ColumnByRelativeDateText<LibraryCache.CachedArtist>(name = "Artist updated") {
        override fun timestampFor(item: LibraryCache.CachedArtist, index: Int) = item.updated
    },

    object : ColumnByNumber<LibraryCache.CachedArtist>(name = "Albums") {
        override fun toNumber(item: LibraryCache.CachedArtist, index: Int) = item.albums?.size
    },

    object : ColumnByRelativeDateText<LibraryCache.CachedArtist>(name = "Albums updated") {
        override fun timestampFor(item: LibraryCache.CachedArtist, index: Int) = item.albumsUpdated
    },
)

// TODO allow refreshing album
private val albumColumns = listOf(
    object : ColumnByString<LibraryCache.CachedAlbum>(name = "Name") {
        override fun toString(item: LibraryCache.CachedAlbum, index: Int): String = item.album?.name.orEmpty()
    },

    object : ColumnByString<LibraryCache.CachedAlbum>(name = "Artists") {
        override fun toString(item: LibraryCache.CachedAlbum, index: Int): String {
            return item.album?.artists?.joinToString { it.name }.orEmpty()
        }
    },

    object : ColumnByString<LibraryCache.CachedAlbum>(name = "ID") {
        override fun toString(item: LibraryCache.CachedAlbum, index: Int): String = item.id
    },

    object : ColumnByString<LibraryCache.CachedAlbum>(name = "Type") {
        override fun toString(item: LibraryCache.CachedAlbum, index: Int): String {
            return item.album?.let { it::class.java.simpleName }.orEmpty()
        }
    },

    object : ColumnByRelativeDateText<LibraryCache.CachedAlbum>(name = "Album updated") {
        override fun timestampFor(item: LibraryCache.CachedAlbum, index: Int) = item.updated
    },
)

// TODO allow refreshing playlist/tracks
private val playlistColumns = listOf(
    object : ColumnByString<LibraryCache.CachedPlaylist>(name = "Name") {
        override fun toString(item: LibraryCache.CachedPlaylist, index: Int): String = item.playlist?.name.orEmpty()
    },

    object : ColumnByString<LibraryCache.CachedPlaylist>(name = "ID") {
        override fun toString(item: LibraryCache.CachedPlaylist, index: Int): String = item.id
    },

    object : ColumnByString<LibraryCache.CachedPlaylist>(name = "Type") {
        override fun toString(item: LibraryCache.CachedPlaylist, index: Int): String {
            return item.playlist?.let { it::class.java.simpleName }.orEmpty()
        }
    },

    object : ColumnByRelativeDateText<LibraryCache.CachedPlaylist>(name = "Playlist updated") {
        override fun timestampFor(item: LibraryCache.CachedPlaylist, index: Int) = item.updated
    },

    object : ColumnByNumber<LibraryCache.CachedPlaylist>(name = "Tracks") {
        override fun toNumber(item: LibraryCache.CachedPlaylist, index: Int) = item.tracks?.trackIds?.size
    },

    object : ColumnByRelativeDateText<LibraryCache.CachedPlaylist>(name = "Tracks updated") {
        override fun timestampFor(item: LibraryCache.CachedPlaylist, index: Int) = item.tracksUpdated
    },
)

private val ratedTrackColumns = listOf(
    NameColumn,
    RatingColumn,
)

@Composable
fun BoxScope.LibraryState(pageStack: MutableState<PageStack>) {
    val scope = rememberCoroutineScope { Dispatchers.IO }
    val presenter = remember { LibraryStatePresenter(scope = scope) }

    ScrollingPage(scrollState = pageStack.value.currentScrollState, presenter = presenter) { state ->
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            Text("Library State", fontSize = Dimens.fontTitle)

            Artists(state, presenter)

            Box(Modifier.fillMaxWidth().height(Dimens.divider).background(Colors.current.dividerColor))

            Albums(state, presenter)

            Box(Modifier.fillMaxWidth().height(Dimens.divider).background(Colors.current.dividerColor))

            Tracks(state, presenter)

            Box(Modifier.fillMaxWidth().height(Dimens.divider).background(Colors.current.dividerColor))

            Playlists(state, presenter)

            Box(Modifier.fillMaxWidth().height(Dimens.divider).background(Colors.current.dividerColor))

            Ratings(state, presenter)
        }
    }
}

@Composable
private fun Artists(state: LibraryStatePresenter.State, presenter: LibraryStatePresenter) {
    val artists = state.artists

    if (artists == null) {
        InvalidateButton(
            refreshing = state.refreshingSavedArtists,
            updated = state.artistsUpdated,
            updatedFallback = "Artists never updated",
            iconSize = Dimens.iconTiny
        ) {
            presenter.emitAsync(LibraryStatePresenter.Event.RefreshSavedArtists)
        }

        return
    }

    val artistsExpanded = remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val totalSaved = artists.size
            val totalCached = artists.count { it.artist != null }
            val simplified = artists.count { it.artist is SimplifiedSpotifyArtist }
            val full = artists.count { it.artist is FullSpotifyArtist }
            val albums = artists.count { it.albums != null }

            Text("$totalSaved Saved Artists", modifier = Modifier.padding(end = Dimens.space3))

            InvalidateButton(
                refreshing = state.refreshingSavedArtists,
                updated = state.artistsUpdated,
                iconSize = Dimens.iconTiny
            ) {
                presenter.emitAsync(LibraryStatePresenter.Event.RefreshSavedArtists)
            }

            val inCacheExpanded = remember { mutableStateOf(false) }
            SimpleTextButton(onClick = { inCacheExpanded.value = true }) {
                val allInCache = full == totalSaved
                CachedIcon(
                    name = if (allInCache) "check-circle" else "cancel",
                    size = Dimens.iconSmall,
                    tint = if (allInCache) Color.Green else Color.Red
                )

                HorizontalSpacer(Dimens.space1)

                Text(
                    "$totalCached/$totalSaved in cache" +
                        simplified.takeIf { it > 0 }?.let { " ($it simplified)" }.orEmpty()
                )

                DropdownMenu(
                    expanded = inCacheExpanded.value,
                    onDismissRequest = { inCacheExpanded.value = false }
                ) {
                    DropdownMenuItem(
                        enabled = full < totalSaved,
                        onClick = {
                            presenter.emitAsync(LibraryStatePresenter.Event.FetchMissingArtists)
                            inCacheExpanded.value = false
                        }
                    ) {
                        Text("Fetch missing")
                    }

                    DropdownMenuItem(
                        onClick = {
                            presenter.emitAsync(LibraryStatePresenter.Event.InvalidateArtists)
                            inCacheExpanded.value = false
                        }
                    ) {
                        Text("Invalidate all")
                    }
                }
            }

            val albumMappingsExpanded = remember { mutableStateOf(false) }
            SimpleTextButton(onClick = { albumMappingsExpanded.value = true }) {
                val allInCache = albums == totalSaved
                CachedIcon(
                    name = if (allInCache) "check-circle" else "cancel",
                    size = Dimens.iconSmall,
                    tint = if (allInCache) Color.Green else Color.Red
                )

                HorizontalSpacer(Dimens.space1)

                Text("$albums/$totalSaved album mappings")

                DropdownMenu(
                    expanded = albumMappingsExpanded.value,
                    onDismissRequest = { albumMappingsExpanded.value = false }
                ) {
                    DropdownMenuItem(
                        onClick = {
                            presenter.emitAsync(LibraryStatePresenter.Event.FetchMissingArtistAlbums)
                            albumMappingsExpanded.value = false
                        }
                    ) {
                        Text("Fetch missing")
                    }

                    DropdownMenuItem(
                        onClick = {
                            presenter.emitAsync(LibraryStatePresenter.Event.InvalidateArtistAlbums)
                            albumMappingsExpanded.value = false
                        }
                    ) {
                        Text("Invalidate all")
                    }
                }
            }
        }

        SimpleTextButton(onClick = { artistsExpanded.value = !artistsExpanded.value }) {
            Text(if (artistsExpanded.value) "Collapse" else "Expand")
        }
    }

    if (artistsExpanded.value) {
        Table(columns = artistColumns, items = artists.toList())
    }
}

@Composable
private fun Albums(state: LibraryStatePresenter.State, presenter: LibraryStatePresenter) {
    val albums = state.albums

    if (albums == null) {
        InvalidateButton(
            refreshing = state.refreshingSavedAlbums,
            updated = state.albumsUpdated,
            updatedFallback = "Albums never updated",
            iconSize = Dimens.iconTiny
        ) {
            presenter.emitAsync(LibraryStatePresenter.Event.RefreshSavedAlbums)
        }

        return
    }

    val albumsExpanded = remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val totalSaved = albums.size
            val totalCached = albums.count { it.album != null }
            val simplified = albums.count { it.album is SimplifiedSpotifyAlbum }
            val full = albums.count { it.album is FullSpotifyAlbum }

            Text("$totalSaved Saved Albums", modifier = Modifier.padding(end = Dimens.space3))

            InvalidateButton(
                refreshing = state.refreshingSavedAlbums,
                updated = state.albumsUpdated,
                iconSize = Dimens.iconTiny
            ) {
                presenter.emitAsync(LibraryStatePresenter.Event.RefreshSavedAlbums)
            }

            val inCacheExpanded = remember { mutableStateOf(false) }
            SimpleTextButton(onClick = { inCacheExpanded.value = true }) {
                val allInCache = full == totalSaved
                CachedIcon(
                    name = if (allInCache) "check-circle" else "cancel",
                    size = Dimens.iconSmall,
                    tint = if (allInCache) Color.Green else Color.Red
                )

                HorizontalSpacer(Dimens.space1)

                Text(
                    "$totalCached/$totalSaved in cache" +
                        simplified.takeIf { it > 0 }?.let { " ($it simplified)" }.orEmpty()
                )

                DropdownMenu(
                    expanded = inCacheExpanded.value,
                    onDismissRequest = { inCacheExpanded.value = false }
                ) {
                    DropdownMenuItem(
                        enabled = full < totalSaved,
                        onClick = {
                            presenter.emitAsync(LibraryStatePresenter.Event.FetchMissingAlbums)
                            inCacheExpanded.value = false
                        }
                    ) {
                        Text("Fetch missing")
                    }

                    DropdownMenuItem(
                        onClick = {
                            presenter.emitAsync(LibraryStatePresenter.Event.InvalidateAlbums)
                            inCacheExpanded.value = false
                        }
                    ) {
                        Text("Invalidate all")
                    }
                }
            }
        }

        SimpleTextButton(onClick = { albumsExpanded.value = !albumsExpanded.value }) {
            Text(if (albumsExpanded.value) "Collapse" else "Expand")
        }
    }

    if (albumsExpanded.value) {
        Table(columns = albumColumns, items = albums.toList())
    }
}

@Composable
private fun Tracks(state: LibraryStatePresenter.State, presenter: LibraryStatePresenter) {
    val tracks = state.tracks

    if (tracks == null) {
        InvalidateButton(
            refreshing = state.refreshingSavedTracks,
            updated = state.tracksUpdated,
            updatedFallback = "Tracks never updated",
            iconSize = Dimens.iconTiny
        ) {
            presenter.emitAsync(LibraryStatePresenter.Event.RefreshSavedTracks)
        }

        return
    }

    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val totalSaved = tracks.size
            val totalCached = tracks.count { it.track != null }
            val simplified = tracks.count { it.track is SimplifiedSpotifyTrack }
            val full = tracks.count { it.track is FullSpotifyTrack }

            Text("$totalSaved Saved Tracks", modifier = Modifier.padding(end = Dimens.space3))

            InvalidateButton(
                refreshing = state.refreshingSavedTracks,
                updated = state.tracksUpdated,
                iconSize = Dimens.iconTiny
            ) {
                presenter.emitAsync(LibraryStatePresenter.Event.RefreshSavedTracks)
            }

            val inCacheExpanded = remember { mutableStateOf(false) }
            SimpleTextButton(onClick = { inCacheExpanded.value = true }) {
                val allInCache = full == totalSaved
                CachedIcon(
                    name = if (allInCache) "check-circle" else "cancel",
                    size = Dimens.iconSmall,
                    tint = if (allInCache) Color.Green else Color.Red
                )

                HorizontalSpacer(Dimens.space1)

                Text(
                    "$totalCached/$totalSaved in cache" +
                        simplified.takeIf { it > 0 }?.let { " ($it simplified)" }.orEmpty()
                )

                DropdownMenu(
                    expanded = inCacheExpanded.value,
                    onDismissRequest = { inCacheExpanded.value = false }
                ) {
                    DropdownMenuItem(
                        enabled = full < totalSaved,
                        onClick = {
                            presenter.emitAsync(LibraryStatePresenter.Event.FetchMissingTracks)
                            inCacheExpanded.value = false
                        }
                    ) {
                        Text("Fetch missing")
                    }

                    DropdownMenuItem(
                        onClick = {
                            presenter.emitAsync(LibraryStatePresenter.Event.InvalidateTracks)
                            inCacheExpanded.value = false
                        }
                    ) {
                        Text("Invalidate all")
                    }
                }
            }
        }
    }
}

@Composable
private fun Playlists(state: LibraryStatePresenter.State, presenter: LibraryStatePresenter) {
    val playlists = state.playlists

    if (playlists == null) {
        InvalidateButton(
            refreshing = state.refreshingSavedPlaylists,
            updated = state.playlistsUpdated,
            updatedFallback = "Playlists never updated",
            iconSize = Dimens.iconTiny
        ) {
            presenter.emitAsync(LibraryStatePresenter.Event.RefreshSavedPlaylists)
        }

        return
    }

    val playlistsExpanded = remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val totalSaved = playlists.size
            val totalCached = playlists.count { it.playlist != null }
            val simplified = playlists.count { it.playlist is SimplifiedSpotifyPlaylist }
            val full = playlists.count { it.playlist is FullSpotifyPlaylist }
            val tracks = playlists.count { it.tracks != null }

            Text("$totalSaved Saved Playlists", modifier = Modifier.padding(end = Dimens.space3))

            InvalidateButton(
                refreshing = state.refreshingSavedPlaylists,
                updated = state.playlistsUpdated,
                iconSize = Dimens.iconTiny
            ) {
                presenter.emitAsync(LibraryStatePresenter.Event.RefreshSavedPlaylists)
            }

            val inCacheExpanded = remember { mutableStateOf(false) }
            SimpleTextButton(onClick = { inCacheExpanded.value = true }) {
                val allInCache = full == totalSaved
                CachedIcon(
                    name = if (allInCache) "check-circle" else "cancel",
                    size = Dimens.iconSmall,
                    tint = if (allInCache) Color.Green else Color.Red
                )

                HorizontalSpacer(Dimens.space1)

                Text(
                    "$totalCached/$totalSaved in cache" +
                        simplified.takeIf { it > 0 }?.let { " ($it simplified)" }.orEmpty()
                )

                DropdownMenu(
                    expanded = inCacheExpanded.value,
                    onDismissRequest = { inCacheExpanded.value = false }
                ) {
                    DropdownMenuItem(
                        enabled = full < totalSaved,
                        onClick = {
                            presenter.emitAsync(LibraryStatePresenter.Event.FetchMissingPlaylists)
                            inCacheExpanded.value = false
                        }
                    ) {
                        Text("Fetch missing")
                    }

                    DropdownMenuItem(
                        onClick = {
                            presenter.emitAsync(LibraryStatePresenter.Event.InvalidatePlaylists)
                            inCacheExpanded.value = false
                        }
                    ) {
                        Text("Invalidate all")
                    }
                }
            }

            val trackMappingsExpanded = remember { mutableStateOf(false) }
            SimpleTextButton(onClick = { trackMappingsExpanded.value = true }) {
                val allInCache = tracks == totalSaved
                CachedIcon(
                    name = if (allInCache) "check-circle" else "cancel",
                    size = Dimens.iconSmall,
                    tint = if (allInCache) Color.Green else Color.Red
                )

                HorizontalSpacer(Dimens.space1)

                Text("$tracks/$totalSaved track mappings")

                DropdownMenu(
                    expanded = trackMappingsExpanded.value,
                    onDismissRequest = { trackMappingsExpanded.value = false }
                ) {
                    DropdownMenuItem(
                        onClick = {
                            presenter.emitAsync(LibraryStatePresenter.Event.FetchMissingPlaylistTracks)
                            trackMappingsExpanded.value = false
                        }
                    ) {
                        Text("Fetch missing")
                    }

                    DropdownMenuItem(
                        onClick = {
                            presenter.emitAsync(LibraryStatePresenter.Event.InvalidatePlaylistTracks)
                            trackMappingsExpanded.value = false
                        }
                    ) {
                        Text("Invalidate all")
                    }
                }
            }
        }

        SimpleTextButton(onClick = { playlistsExpanded.value = !playlistsExpanded.value }) {
            Text(if (playlistsExpanded.value) "Collapse" else "Expand")
        }
    }

    if (playlistsExpanded.value) {
        Table(columns = playlistColumns, items = playlists.toList())
    }
}

@Composable
private fun Ratings(state: LibraryStatePresenter.State, presenter: LibraryStatePresenter) {
    val ratedTracks = state.ratedTracks

    val ratingsExpanded = remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${ratedTracks.size} Rated Tracks", modifier = Modifier.padding(end = Dimens.space3))

            SimpleTextButton(onClick = { presenter.emitAsync(LibraryStatePresenter.Event.ClearAllRatings) }) {
                Text("Clear all ratings")
            }
        }

        SimpleTextButton(onClick = { ratingsExpanded.value = !ratingsExpanded.value }) {
            Text(if (ratingsExpanded.value) "Collapse" else "Expand")
        }
    }

    if (ratingsExpanded.value) {
        Table(
            columns = ratedTrackColumns,
            items = ratedTracks,
            modifier = Modifier.widthIn(max = RATINGS_TABLE_WIDTH),
            defaultSortOrder = Sort(RatingColumn, SortOrder.DESCENDING), // sort by rating descending by default
        )
    }
}
