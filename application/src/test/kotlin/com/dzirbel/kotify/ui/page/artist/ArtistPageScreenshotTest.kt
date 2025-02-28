package com.dzirbel.kotify.ui.page.artist

import com.dzirbel.kotify.db.model.AlbumType
import com.dzirbel.kotify.repository.FakeArtistAlbumsRepository
import com.dzirbel.kotify.repository.FakeArtistRepository
import com.dzirbel.kotify.repository.album.AlbumViewModel
import com.dzirbel.kotify.repository.artist.ArtistAlbumViewModel
import com.dzirbel.kotify.repository.artist.ArtistViewModel
import com.dzirbel.kotify.ui.ProvideFakeRepositories
import com.dzirbel.kotify.ui.page.FakeImageViewModel
import com.dzirbel.kotify.ui.page.render
import com.dzirbel.kotify.ui.themedScreenshotTest
import com.dzirbel.kotify.util.MockedTimeExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockedTimeExtension::class)
internal class ArtistPageScreenshotTest {
    @Test
    fun empty() {
        themedScreenshotTest(filename = "empty") {
            ProvideFakeRepositories {
                ArtistPage(artistId = "artistId").render()
            }
        }
    }

    @Test
    fun full() {
        val artist = ArtistViewModel(id = "id", name = "Artist")
        val artistAlbums = List(20) {
            ArtistAlbumViewModel(
                artist = artist,
                album = AlbumViewModel(
                    id = "album-$it",
                    name = "Album $it",
                    albumType = AlbumType.ALBUM,
                    totalTracks = 10,
                    uri = "album-$it",
                    images = FakeImageViewModel(),
                ),
            )
        }

        themedScreenshotTest(filename = "full", windowWidth = 1500) {
            ProvideFakeRepositories(
                artistRepository = FakeArtistRepository(listOf(artist)),
                artistAlbumsRepository = FakeArtistAlbumsRepository(mapOf(artist.id to artistAlbums)),
            ) {
                ArtistPage(artistId = artist.id).render()
            }
        }
    }
}
