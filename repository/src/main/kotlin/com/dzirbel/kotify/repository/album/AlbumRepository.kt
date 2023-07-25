package com.dzirbel.kotify.repository.album

import com.dzirbel.kotify.db.model.Album
import com.dzirbel.kotify.db.model.AlbumType
import com.dzirbel.kotify.db.model.ArtistAlbum
import com.dzirbel.kotify.db.model.Genre
import com.dzirbel.kotify.db.model.Image
import com.dzirbel.kotify.network.Spotify
import com.dzirbel.kotify.network.model.FullSpotifyAlbum
import com.dzirbel.kotify.network.model.SpotifyAlbum
import com.dzirbel.kotify.repository.DatabaseEntityRepository
import com.dzirbel.kotify.repository.Repository
import com.dzirbel.kotify.repository.artist.ArtistRepository
import com.dzirbel.kotify.repository.track.TrackRepository
import com.dzirbel.kotify.repository.util.updateOrInsert
import com.dzirbel.kotify.util.flatMapParallel
import kotlinx.coroutines.CoroutineScope
import java.time.Instant

// most batched calls have a maximum of 50; for albums the maximum is 20
private const val MAX_ALBUM_IDS_LOOKUP = 20

fun SpotifyAlbum.Type.toAlbumType(): AlbumType {
    return when (this) {
        SpotifyAlbum.Type.ALBUM -> AlbumType.ALBUM
        SpotifyAlbum.Type.SINGLE -> AlbumType.SINGLE
        SpotifyAlbum.Type.COMPILATION -> AlbumType.COMPILATION
        SpotifyAlbum.Type.APPEARS_ON -> AlbumType.APPEARS_ON
    }
}

open class AlbumRepository internal constructor(scope: CoroutineScope) :
    DatabaseEntityRepository<Album, SpotifyAlbum>(entityClass = Album, scope = scope) {

    override suspend fun fetchFromRemote(id: String) = Spotify.Albums.getAlbum(id = id)
    override suspend fun fetchFromRemote(ids: List<String>): List<SpotifyAlbum?> {
        return ids.chunked(size = MAX_ALBUM_IDS_LOOKUP)
            .flatMapParallel { idsChunk -> Spotify.Albums.getAlbums(ids = idsChunk) }
    }

    override fun convert(id: String, networkModel: SpotifyAlbum): Album {
        return Album.updateOrInsert(id = id, networkModel = networkModel) {
            albumType = networkModel.albumType?.toAlbumType()
            releaseDate = networkModel.releaseDate
            releaseDatePrecision = networkModel.releaseDatePrecision
            networkModel.totalTracks?.let {
                totalTracks = it
            }

            // attempt to link artists from network model; do not set albumGroup since it is unavailable from an album
            // context
            networkModel.artists.forEach { artistModel ->
                ArtistRepository.convert(artistModel)?.let { artist ->
                    ArtistAlbum.findOrCreate(artistId = artist.id.value, albumId = id, albumGroup = null)
                }
            }

            images.set(
                networkModel.images.map { Image.findOrCreate(url = it.url, width = it.width, height = it.height) },
            )

            if (networkModel is FullSpotifyAlbum) {
                fullUpdatedTime = Instant.now()

                label = networkModel.label
                popularity = networkModel.popularity
                totalTracks = networkModel.tracks.total

                genres.set(networkModel.genres.map { Genre.findOrCreate(it) })
                tracks.set(networkModel.tracks.items.mapNotNull { TrackRepository.convert(it) })
            }
        }
    }

    companion object : AlbumRepository(scope = Repository.applicationScope)
}
