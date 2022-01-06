package com.dzirbel.kotify.cache

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzirbel.kotify.Application
import com.dzirbel.kotify.Logger
import com.dzirbel.kotify.network.Spotify
import com.dzirbel.kotify.network.model.FullSpotifyAlbum
import com.dzirbel.kotify.network.model.FullSpotifyArtist
import com.dzirbel.kotify.network.model.FullSpotifyEpisode
import com.dzirbel.kotify.network.model.FullSpotifyPlaylist
import com.dzirbel.kotify.network.model.FullSpotifyShow
import com.dzirbel.kotify.network.model.FullSpotifyTrack
import com.dzirbel.kotify.network.model.Paging
import com.dzirbel.kotify.network.model.PrivateSpotifyUser
import com.dzirbel.kotify.network.model.PublicSpotifyUser
import com.dzirbel.kotify.network.model.SimplifiedSpotifyAlbum
import com.dzirbel.kotify.network.model.SimplifiedSpotifyArtist
import com.dzirbel.kotify.network.model.SimplifiedSpotifyEpisode
import com.dzirbel.kotify.network.model.SimplifiedSpotifyPlaylist
import com.dzirbel.kotify.network.model.SimplifiedSpotifyShow
import com.dzirbel.kotify.network.model.SimplifiedSpotifyTrack
import com.dzirbel.kotify.network.model.SpotifyAlbum
import com.dzirbel.kotify.network.model.SpotifyArtist
import com.dzirbel.kotify.network.model.SpotifyEpisode
import com.dzirbel.kotify.network.model.SpotifyPlaylist
import com.dzirbel.kotify.network.model.SpotifyPlaylistTrack
import com.dzirbel.kotify.network.model.SpotifyShow
import com.dzirbel.kotify.network.model.SpotifyTrack
import com.dzirbel.kotify.network.model.SpotifyUser
import com.dzirbel.kotify.util.takeIfAllNonNull
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

object SpotifyCache {
    object GlobalObjects {
        @Serializable
        data class SavedPlaylists(val ids: Set<String>) : CacheableObject {
            override val id = ID

            companion object {
                const val ID = "saved-playlists"
            }
        }

        @Serializable
        data class PlaylistTracks(
            val playlistId: String,
            val playlistTrackIds: List<String>,
            val trackIds: List<String>
        ) : CacheableObject {
            override val id
                get() = idFor(playlistId)

            companion object {
                fun idFor(playlistId: String) = "playlist-tracks-$playlistId"
            }
        }

        @Serializable
        data class TrackRating(
            val trackId: String,
            val rating: Int,
            val maxRating: Int,
        ) : CacheableObject {
            init {
                require(rating in 1..maxRating)
            }

            override val id: String
                get() = idFor(trackId)

            companion object {
                fun idFor(trackId: String) = "track-rating-$trackId"
            }
        }

        @Serializable
        data class RatedTracks(
            val trackIds: Set<String>
        ) : CacheableObject {
            override val id = ID

            companion object {
                const val ID = "rated-tracks"
            }
        }
    }

    private val cacheFile = Application.cacheDir.resolve("cache.json.gzip")

    private val cache = Cache(
        file = cacheFile,

        saveOnChange = true,

        ttlStrategy = CacheTTLStrategy.AlwaysValid,

        eventHandler = { events ->
            if (events.any { it is CacheEvent.Save }) {
                onSave()
            }

            Logger.Cache.handleCacheEvents(events)
        },

        // TODO handle case where simplified object has been updated, but full is now out of date
        replacementStrategy = object : CacheReplacementStrategy {
            /**
             * Checks that an inferior [Simplified] type will not replace a superior [Full] type, returning a non-null
             * value when both [current] and [new] are [Base].
             *
             * This is a convenience function to check the common case where the cache may include both a simplified
             * version of a model and a full version; in this case we need to ensure the full version is never replaced
             * by a simplified version. For example, [SpotifyAlbum] objects come in both a [FullSpotifyAlbum] and
             * [SimplifiedSpotifyAlbum] variant
             *
             * This also checks that [current] is a [Base] object XOR [new] is a [Base] object; this is a sanity check
             * that new objects are not replacing entirely different types of objects in the cache.
             */
            private inline fun <
                reified Base : Any,
                reified Simplified : Base,
                reified Full : Base
                > checkReplacement(current: Any, new: Any): Boolean? {
                require(current is Base == new is Base) {
                    "attempted to replace an object of type ${current::class.qualifiedName} with an object of " +
                        "type ${new::class.qualifiedName}"
                }
                if (current is Base) {
                    return !(new is Simplified && current is Full)
                }
                return null
            }

            @Suppress("ReturnCount")
            override fun replace(current: Any, new: Any): Boolean {
                checkReplacement<SpotifyAlbum, SimplifiedSpotifyAlbum, FullSpotifyAlbum>(current, new)
                    ?.let { return it }
                checkReplacement<SpotifyArtist, SimplifiedSpotifyArtist, FullSpotifyArtist>(current, new)
                    ?.let { return it }
                checkReplacement<SpotifyEpisode, SimplifiedSpotifyEpisode, FullSpotifyEpisode>(current, new)
                    ?.let { return it }
                checkReplacement<SpotifyPlaylist, SimplifiedSpotifyPlaylist, FullSpotifyPlaylist>(current, new)
                    ?.let { return it }
                checkReplacement<SpotifyShow, SimplifiedSpotifyShow, FullSpotifyShow>(current, new)
                    ?.let { return it }
                checkReplacement<SpotifyTrack, SimplifiedSpotifyTrack, FullSpotifyTrack>(current, new)
                    ?.let { return it }
                checkReplacement<SpotifyUser, PublicSpotifyUser, PrivateSpotifyUser>(current, new)
                    ?.let { return it }

                return true
            }
        }
    )

    val size: Int
        get() = cache.size

    var sizeOnDisk by mutableStateOf(0L)
        private set

    init {
        // trigger initializing sizeOnDisk in the background
        onSave()
    }

    private fun onSave() {
        GlobalScope.launch {
            sizeOnDisk = cacheFile.length()
        }
    }

    /**
     * Loads the cache from disk, overwriting any values currently in memory.
     */
    fun load() {
        cache.load()
    }

    /**
     * Clears the cache, both in-memory and on disk.
     */
    fun clear() {
        cache.clear()
    }

    fun invalidate(id: String) {
        cache.invalidate(id)
    }

    fun invalidate(ids: List<String>) {
        cache.invalidate(ids = ids)
    }

    fun lastUpdated(id: String): Long? {
        return cache.getCached(id)?.cacheTime
    }

    fun lastUpdated(ids: List<String>): List<Long?> {
        return cache.getCached(ids = ids).map { it?.cacheTime }
    }

    fun put(obj: CacheableObject) {
        cache.put(obj)
    }

    fun getCacheObject(id: String): CacheObject? = cache.getCached(id)
    fun getCacheObjects(ids: Collection<String>): List<CacheObject?> = cache.getCached(ids)

    inline fun <reified T> getCached(id: String): T? = getCacheObject(id)?.obj as? T
    inline fun <reified T> getCached(ids: Collection<String>): List<T?> = getCacheObjects(ids).map { it?.obj as? T }

    object Playlists {
        suspend fun getPlaylist(id: String): SpotifyPlaylist {
            return cache.get<SpotifyPlaylist>(id) { Spotify.Playlists.getPlaylist(id) }
        }

        suspend fun getFullPlaylist(id: String): FullSpotifyPlaylist {
            return cache.get(id) { Spotify.Playlists.getPlaylist(id) }
        }

        suspend fun savePlaylist(id: String): Set<String>? {
            Spotify.Follow.followPlaylist(playlistId = id)

            return cache.getCached(GlobalObjects.SavedPlaylists.ID)?.let { playlists ->
                val savedPlaylists = playlists.obj as GlobalObjects.SavedPlaylists

                savedPlaylists.ids.plus(id).also { savedIds ->
                    // don't update the cache time since we haven't actually refreshed the value from the remote
                    cache.put(
                        value = savedPlaylists.copy(ids = savedIds),
                        cacheTime = playlists.cacheTime
                    )
                }
            }
        }

        suspend fun unsavePlaylist(id: String): Set<String>? {
            Spotify.Follow.unfollowPlaylist(playlistId = id)

            return cache.getCached(GlobalObjects.SavedPlaylists.ID)?.let { playlists ->
                val savedPlaylists = playlists.obj as GlobalObjects.SavedPlaylists

                savedPlaylists.ids.minus(id).also { savedIds ->
                    // don't update the cache time since we haven't actually refreshed the value from the remote
                    cache.put(
                        value = savedPlaylists.copy(ids = savedIds),
                        cacheTime = playlists.cacheTime
                    )
                }
            }
        }

        suspend fun getSavedPlaylists(): Set<String> {
            cache.getCachedValue<GlobalObjects.SavedPlaylists>(GlobalObjects.SavedPlaylists.ID)
                ?.let { return it.ids }

            val playlists = Spotify.Playlists.getPlaylists(limit = Spotify.MAX_LIMIT)
                .fetchAll<SimplifiedSpotifyPlaylist>()
            val savedPlaylists = GlobalObjects.SavedPlaylists(ids = playlists.mapTo(mutableSetOf()) { it.id })
            cache.putAll(playlists.plus(savedPlaylists))

            return savedPlaylists.ids
        }

        suspend fun getPlaylistTracks(
            playlistId: String,
            paging: Paging<SpotifyPlaylistTrack>? = null,
        ): List<SpotifyPlaylistTrack> {
            cache.getCachedValue<GlobalObjects.PlaylistTracks>(
                id = GlobalObjects.PlaylistTracks.idFor(playlistId = playlistId)
            )?.let { playlistTracks ->
                cache.getCached(ids = playlistTracks.playlistTrackIds)
                    .map { it?.obj as? SpotifyPlaylistTrack }
                    .takeIfAllNonNull()
                    ?.let { return it }
            }

            return (paging ?: Spotify.Playlists.getPlaylistTracks(playlistId = playlistId))
                .fetchAll<SpotifyPlaylistTrack>()
                .also { tracks ->
                    val trackIds = tracks.map { it.track.id }.takeIfAllNonNull()

                    // TODO prevents caching playlist tracks for playlists with local tracks
                    if (trackIds == null) {
                        cache.putAll(tracks)
                    } else {
                        val playlistTracks = GlobalObjects.PlaylistTracks(
                            playlistId = playlistId,
                            playlistTrackIds = trackIds.map { SpotifyPlaylistTrack.idFor(trackId = it) },
                            trackIds = trackIds
                        )
                        cache.putAll(tracks.plus(playlistTracks))
                    }
                }
        }
    }

    object Ratings {
        fun ratingState(trackId: String): State<CacheObject?> = cache.stateOf(GlobalObjects.TrackRating.idFor(trackId))

        fun getRating(trackId: String): GlobalObjects.TrackRating? {
            return cache.getCachedValue<GlobalObjects.TrackRating>(GlobalObjects.TrackRating.idFor(trackId))
        }

        fun setRating(trackId: String, rating: GlobalObjects.TrackRating) {
            cache.putAll(
                mapOf(
                    GlobalObjects.TrackRating.idFor(trackId) to rating,
                    GlobalObjects.RatedTracks.ID to GlobalObjects.RatedTracks(ratedTracks().orEmpty().plus(trackId)),
                )
            )
        }

        fun clearRating(trackId: String) {
            cache.invalidate(GlobalObjects.TrackRating.idFor(trackId))
            cache.put(GlobalObjects.RatedTracks.ID, GlobalObjects.RatedTracks(ratedTracks().orEmpty().minus(trackId)))
        }

        fun ratedTracks(): Set<String>? {
            return cache.getCachedValue<GlobalObjects.RatedTracks>(GlobalObjects.RatedTracks.ID)?.trackIds
        }

        fun clearAllRatings() {
            ratedTracks()?.let { trackIds ->
                cache.invalidate(
                    trackIds.map { GlobalObjects.TrackRating.idFor(it) }.plus(GlobalObjects.RatedTracks.ID)
                )
            }
        }
    }
}
