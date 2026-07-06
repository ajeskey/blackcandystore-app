package org.blackcandy.shared.media

import kotlinx.coroutines.flow.StateFlow
import org.blackcandy.shared.models.Song

/**
 * A single abstraction over the three playback routings (local, client-cast,
 * server-playback) so the [PlaybackCoordinator] and UI are engine-agnostic (spec R7).
 *
 * Each concrete engine (`LocalPlaybackEngine`, `ChromecastEngine`, `AirPlayEngine`,
 * `ServerPlaybackEngine`) drives its own [status] and produces or controls audio
 * for exactly one routing. The coordinator keeps at most one engine active at a
 * time and switches engines via [activate] / [deactivate] (R7.4, R15).
 */
interface PlaybackEngine {
    /** The engine's observable state (state, current song, position, volume, target, error). */
    val status: StateFlow<EngineStatus>

    /** Set the playing queue and the index to start from. */
    fun setQueue(
        songs: List<Song>,
        startIndex: Int,
    )

    /** Start or resume playback. */
    fun play()

    /** Pause playback, retaining the current song and position. */
    fun pause()

    /** Stop playback and clear the position. */
    fun stop()

    /** Advance to the next song. */
    fun next()

    /** Return to the previous song. */
    fun previous()

    /** Seek to the given position in seconds. */
    fun seekTo(seconds: Double)

    /** Set the target volume on a normalized 0.0–1.0 scale (R10.4). */
    fun setVolume(level: Double)

    /**
     * Attach this engine to a target (device or server session), making it the active
     * audio source/controller. A `null` target activates default local playback (R7.7).
     */
    suspend fun activate(target: PlaybackTarget?)

    /**
     * Detach this engine. When [retainPosition] is true the engine reports the position
     * it stopped at so the coordinator can resume elsewhere from that point (R15.4).
     */
    suspend fun deactivate(retainPosition: Boolean)
}
