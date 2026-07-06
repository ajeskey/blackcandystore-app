package org.blackcandy.shared.media

import org.blackcandy.shared.models.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the [LocalPlaybackEngine] state projection (spec R7.1, R7.7, R18.1).
 *
 * The engine itself is a thin adapter over the platform [MusicServiceController]; its only
 * pure logic is [localEngineStatus], which projects the controller's [MusicState] and the
 * engine's position/target/volume into the engine-agnostic [EngineStatus] the coordinator
 * observes. These tests pin that mapping so local playback surfaces unchanged behavior.
 */
class LocalPlaybackEngineTest {
    private fun sampleSong(id: Long = 1L) =
        Song(
            id = id,
            name = "Song $id",
            duration = 123.0,
            albumId = 1,
            artistId = 1,
            url = "https://server.example/stream/$id",
            albumName = "Album",
            artistName = "Artist",
            format = "mp3",
            albumImageUrls =
                Song.ImageURLs(
                    small = "https://cdn.example/s.jpg",
                    medium = "https://cdn.example/m.jpg",
                    large = "https://cdn.example/l.jpg",
                ),
            isFavorited = false,
        )

    @Test
    fun projects_music_state_fields_into_engine_status() {
        val song = sampleSong()
        val musicState =
            MusicState(
                playlist = listOf(song),
                playbackState = PlaybackState.PLAYING,
                currentSong = song,
                playbackMode = PlaybackMode.NO_REPEAT,
            )

        val status = localEngineStatus(musicState, position = 42.5, target = PlaybackTarget.LocalDevice, volume = 0.7)

        assertEquals(PlaybackState.PLAYING, status.state)
        assertEquals(song, status.currentSong)
        assertEquals(42.5, status.position)
        assertEquals(0.7, status.volume)
        assertEquals(PlaybackTarget.LocalDevice, status.target)
        assertNull(status.error)
    }

    @Test
    fun defaults_to_local_playback_with_no_target_and_no_error() {
        val status = localEngineStatus(MusicState(), position = 0.0, target = null, volume = 1.0)

        assertEquals(PlaybackState.IDLE, status.state)
        assertNull(status.currentSong)
        assertEquals(0.0, status.position)
        assertEquals(1.0, status.volume)
        assertNull(status.target)
        assertNull(status.error)
    }
}
