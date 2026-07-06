package org.blackcandy.shared.media

import org.blackcandy.shared.models.Song

/**
 * A snapshot of a single [PlaybackEngine]'s state, exposed as a [kotlinx.coroutines.flow.StateFlow]
 * so the coordinator and UI can observe it uniformly across local, client-cast, and
 * server-playback engines (spec R7, R10, R14).
 *
 * @property state the playback state (reuses the existing [PlaybackState] enum).
 * @property currentSong the song the engine is currently playing, or null.
 * @property position the current playback position in seconds.
 * @property volume the target volume on a normalized 0.0–1.0 scale (R10.4).
 * @property target where audio is directed; null means local device playback.
 * @property error the most recent failure, or null when none.
 */
data class EngineStatus(
    val state: PlaybackState = PlaybackState.IDLE,
    val currentSong: Song? = null,
    val position: Double = 0.0,
    val volume: Double = 1.0,
    val target: PlaybackTarget? = null,
    val error: PlaybackError? = null,
)
