package com.dzirbel.kotify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.ContentAlpha
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.dzirbel.kotify.cache.LibraryCache
import com.dzirbel.kotify.cache.SpotifyCache
import com.dzirbel.kotify.network.model.FullTrack
import com.dzirbel.kotify.network.model.SimplifiedTrack
import com.dzirbel.kotify.network.model.Track
import com.dzirbel.kotify.ui.components.LinkedText
import com.dzirbel.kotify.ui.components.PageStack
import com.dzirbel.kotify.ui.components.hoverState
import com.dzirbel.kotify.ui.components.table.Column
import com.dzirbel.kotify.ui.components.table.ColumnByNumber
import com.dzirbel.kotify.ui.components.table.ColumnByString
import com.dzirbel.kotify.ui.components.table.ColumnWidth
import com.dzirbel.kotify.ui.components.table.Sort
import com.dzirbel.kotify.ui.theme.Colors
import com.dzirbel.kotify.ui.theme.Dimens
import com.dzirbel.kotify.ui.util.mutate
import com.dzirbel.kotify.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun trackColumns(
    pageStack: MutableState<PageStack>,
    savedTracks: Set<String>? = LibraryCache.savedTracks,
    includeTrackNumber: Boolean = true,
    includeAlbum: Boolean = true,
    playContextFromIndex: ((Int) -> Player.PlayContext?)?
): List<Column<Track>> {
    return listOfNotNull(
        playContextFromIndex?.let { PlayingColumn(playContextFromIndex = it) },
        TrackNumberColumn.takeIf { includeTrackNumber },
        SavedColumn(savedTracks = savedTracks),
        NameColumn,
        ArtistColumn(pageStack),
        AlbumColumn(pageStack).takeIf { includeAlbum },
        DurationColumn,
        PopularityColumn
    )
}

/**
 * A [Column] which displays the current play state of a [Track] with an icon, and allows playing a [Track] via the
 * [playContext].
 */
class PlayingColumn(
    /**
     * Returns a [Player.PlayContext] to play when the user selects the given [track] at the given [index] in the
     * column.
     */
    private val playContextFromIndex: (Int) -> Player.PlayContext?
) : Column<Track>() {
    override val width = ColumnWidth.Fill()
    override val cellAlignment = Alignment.TopCenter

    @Composable
    override fun header(sort: MutableState<Sort?>) {
        Box(Modifier)
    }

    @Composable
    override fun item(item: Track, index: Int) {
        val hoverState = remember { mutableStateOf(false) }
        val baseModifier = Modifier
            .hoverState(hoverState)
            .padding(horizontal = Dimens.space2)

        val fontSizeDp = with(LocalDensity.current) { Dimens.fontBody.toDp() }
        if (Player.currentTrack.value?.id == item.id) {
            CachedIcon(
                name = "volume-up",
                size = fontSizeDp,
                contentDescription = "Playing",
                modifier = baseModifier,
                tint = Colors.current.primary
            )
        } else {
            val context = playContextFromIndex(index)
            // TODO refactor to use full size for hover and not render transparent icon button when not hovering
            IconButton(
                modifier = baseModifier.size(fontSizeDp),
                onClick = {
                    Player.play(context = context)
                },
                enabled = context != null
            ) {
                CachedIcon(
                    name = "play-circle-outline",
                    size = fontSizeDp,
                    contentDescription = "Playing",
                    tint = if (hoverState.value) Colors.current.primary else Color.Companion.Transparent
                )
            }
        }
    }
}

class SavedColumn(savedTracks: Set<String>?) : Column<Track>() {
    private val savedTracks = mutableStateOf(savedTracks)

    override val width = ColumnWidth.Fill()

    @Composable
    override fun header(sort: MutableState<Sort?>) {
        Box(Modifier)
    }

    @Composable
    override fun item(item: Track, index: Int) {
        item.id?.let { trackId ->
            val scope = rememberCoroutineScope { Dispatchers.IO }
            ToggleSaveButton(
                modifier = Modifier.padding(Dimens.space2),
                isSaved = savedTracks.value?.contains(item.id)
            ) { save ->
                scope.launch {
                    if (save) {
                        SpotifyCache.Tracks.saveTrack(id = trackId)
                        savedTracks.mutate { this?.plus(trackId) }
                    } else {
                        SpotifyCache.Tracks.unsaveTrack(id = trackId)
                        savedTracks.mutate { this?.minus(trackId) }
                    }
                }
            }
        } ?: Box(Modifier)
    }
}

object NameColumn : ColumnByString<Track>(header = "Title") {
    override val width = ColumnWidth.Weighted(weight = 1f)

    override fun toString(item: Track, index: Int) = item.name
}

class ArtistColumn(private val pageStack: MutableState<PageStack>) : Column<Track>() {
    override val width = ColumnWidth.Weighted(weight = 1f)

    override fun compare(first: Track, firstIndex: Int, second: Track, secondIndex: Int): Int {
        return first.artists.joinToString { it.name }
            .compareTo(second.artists.joinToString { it.name }, ignoreCase = true)
    }

    @Composable
    override fun header(sort: MutableState<Sort?>) {
        standardHeader(sort = sort, header = "Artist")
    }

    @Composable
    override fun item(item: Track, index: Int) {
        LinkedText(
            modifier = Modifier.padding(Dimens.space3),
            key = item,
            onClickLink = { artistId ->
                pageStack.mutate { to(ArtistPage(artistId = artistId)) }
            }
        ) {
            list(item.artists) { artist ->
                link(text = artist.name, link = artist.id)
            }
        }
    }
}

class AlbumColumn(private val pageStack: MutableState<PageStack>) : Column<Track>() {
    override val width = ColumnWidth.Weighted(weight = 1f)

    override fun compare(first: Track, firstIndex: Int, second: Track, secondIndex: Int): Int {
        return first.album?.name.orEmpty().compareTo(second.album?.name.orEmpty(), ignoreCase = true)
    }

    @Composable
    override fun header(sort: MutableState<Sort?>) {
        standardHeader(sort = sort, header = "Album")
    }

    @Composable
    override fun item(item: Track, index: Int) {
        LinkedText(
            modifier = Modifier.padding(Dimens.space3),
            key = item,
            onClickLink = { albumId ->
                pageStack.mutate { to(AlbumPage(albumId = albumId)) }
            }
        ) {
            item.album?.let { album ->
                link(text = album.name, link = album.id)
            }
        }
    }
}

object DurationColumn : ColumnByString<Track>(header = "Duration") {
    override val cellAlignment = Alignment.TopEnd

    override fun toString(item: Track, index: Int) = formatDuration(item.durationMs)

    override fun compare(first: Track, firstIndex: Int, second: Track, secondIndex: Int): Int {
        return first.durationMs.compareTo(second.durationMs)
    }
}

object TrackNumberColumn : ColumnByNumber<Track>(header = "#") {
    override fun toNumber(item: Track, index: Int) = item.trackNumber
}

object PopularityColumn : Column<Track>() {
    override val width: ColumnWidth = ColumnWidth.MatchHeader
    override val cellAlignment = Alignment.TopEnd

    private val Track.popularity: Int?
        get() = (this as? FullTrack)?.popularity ?: (this as? SimplifiedTrack)?.popularity

    @Composable
    override fun header(sort: MutableState<Sort?>) {
        standardHeader(sort = sort, header = "Popularity")
    }

    @Composable
    override fun item(item: Track, index: Int) {
        val popularity = item.popularity ?: 0
        val height = with(LocalDensity.current) { Dimens.fontBody.toDp() }
        val color = Colors.current.text.copy(alpha = ContentAlpha.disabled)

        Box(
            Modifier
                .padding(Dimens.space3)
                .background(Colors.current.surface2)
                .height(height)
                .fillMaxWidth()
                .border(width = Dimens.divider, color = color)
        ) {
            Box(
                @Suppress("MagicNumber")
                Modifier
                    .background(color)
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = popularity / 100f)
            )
        }
    }

    override fun compare(first: Track, firstIndex: Int, second: Track, secondIndex: Int): Int {
        val firstPopularity = first.popularity
        val secondPopularity = second.popularity

        return when {
            firstPopularity != null && secondPopularity != null -> firstPopularity.compareTo(secondPopularity)
            firstPopularity != null -> -1 // second is null -> first before second
            secondPopularity != null -> 1 // first is null -> second before first
            else -> 0
        }
    }
}
