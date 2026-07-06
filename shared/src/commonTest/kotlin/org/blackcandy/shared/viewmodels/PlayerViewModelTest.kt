package org.blackcandy.shared.viewmodels

import org.blackcandy.shared.media.MusicState
import org.blackcandy.shared.media.PlaybackMode
import org.blackcandy.shared.media.PlaybackRouting
import org.blackcandy.shared.media.PlaybackState
import org.blackcandy.shared.media.PlaybackStatus
import org.blackcandy.shared.media.PlaybackTarget
import org.blackcandy.shared.models.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the [PlayerViewModel] pure state-projection and selection-guard logic
 * (spec R2.6, R14.3, R16.2, R16.3, R16.4, R16.6).
 *
 * Following the module convention (see `DevicePickerViewModelTest`, `localEngineStatus`), the
 * ViewModel's decision logic is extracted into pure functions so it can be verified without a
 * coroutine scope, a live [org.blackcandy.shared.media.PlaybackCoordinator], or a platform player.
 */
class PlayerViewModelTest {
    private fun song(
        id: Long,
        available: Boolean = true,
    ) = Song(
        id = id,
        name = "Song $id",
        duration = 100.0,
        albumId = 1L,
        artistId = 1L,
        // An empty resolved stream path marks the song unavailable (R1.5); a non-empty path
        // (or, by fallback, a non-empty legacy url) marks it available.
        url = "https://example.com/$id.mp3",
        albumName = "Album",
        artistName = "Artist",
        format = "mp3",
        albumImageUrls = Song.ImageURLs(small = "s", medium = "m", large = "l"),
        isFavorited = false,
        resolvedStreamPath = if (available) "https://example.com/$id.mp3" else "",
    )

    // ---- Explicit selection guard (R2.6, R16.4) -----------------------------------------------

    @Test
    fun selecting_an_available_song_plays_its_index() {
        val playlist = listOf(song(1), song(2), song(3))

        val decision = resolvePlayOn(playlist, songId = 3)

        assertEquals(PlayOnDecision.Play(2), decision)
    }

    @Test
    fun selecting_an_unavailable_song_is_rejected_and_keeps_current_song() {
        val playlist = listOf(song(1), song(2, available = false), song(3))

        val decision = resolvePlayOn(playlist, songId = 2)

        assertEquals(PlayOnDecision.Unavailable, decision)
    }

    @Test
    fun selecting_a_song_not_in_the_queue_is_ignored() {
        val playlist = listOf(song(1), song(2))

        val decision = resolvePlayOn(playlist, songId = 99)

        assertEquals(PlayOnDecision.Ignore, decision)
    }

    // ---- State projection from the coordinator status (R14.3, R16.2, R16.3, R16.5) ------------

    @Test
    fun projection_takes_transport_state_from_coordinator_status() {
        val local = song(1)
        val remote = song(2)
        val musicState =
            MusicState(
                playlist = listOf(local, remote),
                playbackState = PlaybackState.PAUSED,
                currentSong = local,
                playbackMode = PlaybackMode.REPEAT,
            )
        // The coordinator reports server_playback of the second song, playing, at 42s.
        val status =
            PlaybackStatus(
                routing = PlaybackRouting.SERVER_PLAYBACK,
                target = PlaybackTarget.LocalDevice,
                state = PlaybackState.PLAYING,
                currentSong = remote,
                position = 42.0,
            )

        val result = playerUiState(PlayerUiState(), musicState, status)

        // Current song, playback state, position, routing, target follow the coordinator.
        assertEquals(remote, result.musicState.currentSong)
        assertEquals(PlaybackState.PLAYING, result.musicState.playbackState)
        assertEquals(42.0, result.currentPosition)
        assertEquals(PlaybackRouting.SERVER_PLAYBACK, result.routing)
        assertEquals(PlaybackTarget.LocalDevice, result.target)
    }

    @Test
    fun projection_keeps_queue_and_repeat_mode_from_local_music_state() {
        val playlist = listOf(song(1), song(2, available = false), song(3))
        val musicState =
            MusicState(
                playlist = playlist,
                playbackMode = PlaybackMode.REPEAT_ONE,
            )
        val status = PlaybackStatus(routing = PlaybackRouting.LOCAL)

        val result = playerUiState(PlayerUiState(), musicState, status)

        // Queue order/membership and the repeat/shuffle mode are untouched by the projection
        // (R2.3, R16.6), and per-song availability is preserved for the UI (R16.4).
        assertEquals(playlist, result.musicState.playlist)
        assertEquals(PlaybackMode.REPEAT_ONE, result.musicState.playbackMode)
        assertTrue(result.musicState.playlist[0].isAvailable)
        assertTrue(!result.musicState.playlist[1].isAvailable)
    }

    @Test
    fun projection_preserves_existing_alert_message() {
        val base = PlayerUiState(alertMessage = org.blackcandy.shared.models.AlertMessage.String("boom"))

        val result = playerUiState(base, MusicState(), PlaybackStatus())

        assertEquals(base.alertMessage, result.alertMessage)
    }
}
