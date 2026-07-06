package org.blackcandy.shared.media

import org.blackcandy.shared.models.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Example-based unit tests for the pure [CastSessionMachine] transitions (spec R10).
 *
 * These pin the specific behaviors of each control and failure event. The universal
 * state invariant and the resume-after-pause invariant (Property 3) are covered
 * separately by the property test in task 15.2.
 */
class CastSessionMachineTest {
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
    fun starts_stopped() {
        val machine = CastSessionMachine()
        assertEquals(CastSessionState.STOPPED, machine.state)
        assertNull(machine.currentSong)
        assertEquals(0.0, machine.position)
        assertEquals(1.0, machine.volume)
        assertNull(machine.error)
    }

    @Test
    fun play_enters_playing_with_song_and_position() {
        val song = sampleSong()
        val machine = CastSessionMachine().play(song, positionSeconds = 12.0)

        assertEquals(CastSessionState.PLAYING, machine.state)
        assertEquals(song, machine.currentSong)
        assertEquals(12.0, machine.position)
        assertNull(machine.error)
    }

    @Test
    fun play_to_unreachable_target_is_rejected_and_stays_stopped() {
        val song = sampleSong()
        val machine = CastSessionMachine().play(song, positionSeconds = 5.0, targetReachable = false)

        assertEquals(CastSessionState.STOPPED, machine.state)
        assertEquals(0.0, machine.position)
        assertEquals(PlaybackError.NotReachable, machine.error)
    }

    @Test
    fun pause_retains_song_and_position() {
        val song = sampleSong()
        val paused = CastSessionMachine().play(song, positionSeconds = 30.0).pause()

        assertEquals(CastSessionState.PAUSED, paused.state)
        assertEquals(song, paused.currentSong)
        assertEquals(30.0, paused.position)
    }

    @Test
    fun resume_after_pause_returns_to_playing_with_retained_song_and_position() {
        val song = sampleSong()
        val paused = CastSessionMachine().play(song, positionSeconds = 45.5).pause()
        val resumed = paused.resume()

        assertEquals(CastSessionState.PLAYING, resumed.state)
        assertEquals(song, resumed.currentSong)
        assertEquals(45.5, resumed.position)
    }

    @Test
    fun pause_from_stopped_is_a_no_op() {
        val machine = CastSessionMachine()
        assertEquals(machine, machine.pause())
    }

    @Test
    fun resume_from_stopped_is_a_no_op() {
        val machine = CastSessionMachine()
        assertEquals(machine, machine.resume())
    }

    @Test
    fun stop_clears_position_and_returns_to_stopped() {
        val song = sampleSong()
        val stopped = CastSessionMachine().play(song, positionSeconds = 60.0).stop()

        assertEquals(CastSessionState.STOPPED, stopped.state)
        assertEquals(0.0, stopped.position)
        assertNull(stopped.error)
    }

    @Test
    fun set_volume_is_clamped_and_does_not_change_state() {
        val song = sampleSong()
        val playing = CastSessionMachine().play(song)

        assertEquals(1.0, playing.setVolume(2.0).volume)
        assertEquals(0.0, playing.setVolume(-1.0).volume)
        assertEquals(0.5, playing.setVolume(0.5).volume)
        assertEquals(CastSessionState.PLAYING, playing.setVolume(0.5).state)
    }

    @Test
    fun disconnect_while_playing_stops_with_device_disconnected() {
        val song = sampleSong()
        val disconnected = CastSessionMachine().play(song, positionSeconds = 10.0).onDisconnect()

        assertEquals(CastSessionState.STOPPED, disconnected.state)
        assertEquals(0.0, disconnected.position)
        assertEquals(PlaybackError.DeviceDisconnected, disconnected.error)
    }

    @Test
    fun disconnect_while_stopped_is_a_no_op() {
        val machine = CastSessionMachine()
        assertEquals(machine, machine.onDisconnect())
    }

    @Test
    fun stalled_timeout_while_playing_stops_with_timeout() {
        val song = sampleSong()
        val timedOut = CastSessionMachine().play(song).onPlaybackStalledTimeout()

        assertEquals(CastSessionState.STOPPED, timedOut.state)
        assertEquals(0.0, timedOut.position)
        assertEquals(PlaybackError.Timeout, timedOut.error)
    }

    @Test
    fun stalled_timeout_while_paused_is_a_no_op() {
        val song = sampleSong()
        val paused = CastSessionMachine().play(song).pause()
        assertEquals(paused, paused.onPlaybackStalledTimeout())
    }

    @Test
    fun engine_status_projection_maps_states() {
        val song = sampleSong()
        val playing = CastSessionMachine().play(song, positionSeconds = 3.0).setVolume(0.4)
        val status = playing.toEngineStatus(PlaybackTarget.LocalDevice)

        assertEquals(PlaybackState.PLAYING, status.state)
        assertEquals(song, status.currentSong)
        assertEquals(3.0, status.position)
        assertEquals(0.4, status.volume)
        assertEquals(PlaybackTarget.LocalDevice, status.target)

        assertEquals(PlaybackState.IDLE, CastSessionMachine().toEngineStatus().state)
        assertEquals(PlaybackState.PAUSED, playing.pause().toEngineStatus().state)
        assertTrue(CastSessionMachine().toEngineStatus().target == null)
    }
}
