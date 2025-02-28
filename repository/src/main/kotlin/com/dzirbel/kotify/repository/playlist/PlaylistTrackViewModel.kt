package com.dzirbel.kotify.repository.playlist

import com.dzirbel.kotify.db.model.PlaylistTrack
import com.dzirbel.kotify.repository.episode.EpisodeViewModel
import com.dzirbel.kotify.repository.track.TrackViewModel
import java.time.Instant

data class PlaylistTrackViewModel(
    val track: TrackViewModel? = null,
    val episode: EpisodeViewModel? = null,
    val addedAt: String? = null,
    val isLocal: Boolean = false,
    val indexOnPlaylist: Int,
) {
    val addedAtInstant: Instant? by lazy {
        addedAt?.let { Instant.parse(it) }
    }

    constructor(
        playlistTrack: PlaylistTrack,
        track: TrackViewModel? = playlistTrack.track?.let(::TrackViewModel),
        episode: EpisodeViewModel? = playlistTrack.episode?.let(::EpisodeViewModel),
    ) : this(
        track = track,
        episode = episode,
        addedAt = playlistTrack.addedAt,
        isLocal = playlistTrack.isLocal,
        indexOnPlaylist = playlistTrack.indexOnPlaylist,
    )
}
