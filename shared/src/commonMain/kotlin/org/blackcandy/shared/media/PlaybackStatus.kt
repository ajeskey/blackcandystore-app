package org.blackcandy.shared.media

import org.blackcandy.shared.models.Song

/**
 * The unified, engine-agnostic playback snapshot the [PlaybackCoordinator] exposes to the
 * app and UI (spec R7, R16). It layers the active [PlaybackRouting] and the selected
 * [PlaybackTarget] on top of the active engine's [EngineStatus] so ViewModels never have to
 * know which concrete engine is currently active.
 *
 * The transport fields ([state], [currentSong], [position], [volume], [error]) mirror the
 * active engine's [EngineStatus]; [routing] and [target] describe where that activity is
 * being directed.
 *
 * @property routing which of the three routings (local / client-cast / server-playback) is active.
 * @property target where playback is directed; null means the default local device (R7.7).
 * @property state the active engine's playback state.
 * @property currentSong the song currently loaded on the active engine, or null.
 * @property position the current playback position in seconds.
 * @property volume the target volume on a normalized 0.0–1.0 scale (R10.4).
 * @property error the most recent failure surfaced by the active engine or the switch, or null.
 */
data class PlaybackStatus(
    val routing: PlaybackRouting = PlaybackRouting.LOCAL,
    val target: PlaybackTarget? = null,
    val state: PlaybackState = PlaybackState.IDLE,
    val currentSong: Song? = null,
    val position: Double = 0.0,
    val volume: Double = 1.0,
    val error: PlaybackError? = null,
) {
    companion object {
        /**
         * Compose a [PlaybackStatus] from the current [routing]/[target] and the active engine's
         * [EngineStatus]. The engine's own [EngineStatus.target] is authoritative for the target
         * when present; the coordinator's selected [target] is used as a fallback so the value is
         * stable across the brief window while an engine activates.
         */
        fun from(
            routing: PlaybackRouting,
            target: PlaybackTarget?,
            engineStatus: EngineStatus,
        ): PlaybackStatus =
            PlaybackStatus(
                routing = routing,
                target = engineStatus.target ?: target,
                state = engineStatus.state,
                currentSong = engineStatus.currentSong,
                position = engineStatus.position,
                volume = engineStatus.volume,
                error = engineStatus.error,
            )
    }
}
