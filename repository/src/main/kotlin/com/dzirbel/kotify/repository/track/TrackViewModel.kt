package com.dzirbel.kotify.repository.track

import androidx.compose.runtime.Stable
import com.dzirbel.kotify.db.model.Track
import com.dzirbel.kotify.repository.EntityViewModel
import com.dzirbel.kotify.repository.album.AlbumViewModel
import com.dzirbel.kotify.repository.artist.ArtistViewModel
import com.dzirbel.kotify.repository.util.LazyTransactionStateFlow

@Stable
class TrackViewModel(track: Track) : EntityViewModel(track) {
    val trackNumber: Int = track.trackNumber
    val durationMs: Long = track.durationMs
    val popularity: Int? = track.popularity

    val album = LazyTransactionStateFlow("track $id album") {
        track.album?.let { AlbumViewModel(it) }
    }

    val artists = LazyTransactionStateFlow("track $id artists") { track.artists.map(::ArtistViewModel) }
}
