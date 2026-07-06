package org.blackcandy.shared.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.blackcandy.shared.models.Song

/**
 * The default [PlaybackEngine] for local device playback (spec R7.1, R7.7).
 *
 * It is a thin adapter over the existing [MusicServiceController] (ExoPlayer on Android,
 * `AVPlayer` on iOS): every transport operation forwards directly to the controller so
 * local playback behaves exactly as it does today for single-library servers (R18.1).
 * The only added responsibility is projecting the controller's [MusicState] and position
 * into the engine-agnostic [EngineStatus] the [PlaybackCoordinator] and UI observe (R7.1).
 *
 * @param controller the platform audio player this engine delegates to.
 * @param scope the coroutine scope the exposed [status] flow is shared in (owned by the
 *   coordinator/DI graph, cancelled when the graph is torn down).
 */
class LocalPlaybackEngine(
    private val controller: MusicServiceController,
    scope: CoroutineScope,
) : PlaybackEngine {
    // Local playback is always the device itself. The target is recorded on activate/deactivate
    // so the coordinator can tell which engine is live; it does not change how audio is produced.
    private val activeTarget = MutableStateFlow<PlaybackTarget?>(null)

    // Local device volume is controlled by the OS/hardware, not the app. We keep the last
    // requested normalized level for status parity with cast/server engines (R10.4) without
    // altering the controller, which exposes no volume control.
    private val requestedVolume = MutableStateFlow(1.0)

    override val status: StateFlow<EngineStatus> =
        combine(
            controller.musicState,
            controller.currentPosition,
            activeTarget,
            requestedVolume,
        ) { musicState, position, target, volume ->
            localEngineStatus(musicState, position, target, volume)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = localEngineStatus(controller.musicState.value, 0.0, activeTarget.value, requestedVolume.value),
        )

    override fun setQueue(
        songs: List<Song>,
        startIndex: Int,
    ) {
        // Establish the queue, then select the starting song via the controller's existing
        // playOn primitive (the same path today's playlist playback uses). Guard the index so
        // an out-of-range start leaves the queue set without touching the current song.
        controller.updateSongs(songs)

        if (startIndex in songs.indices) {
            controller.playOn(startIndex)
        }
    }

    override fun play() {
        controller.play()
    }

    override fun pause() {
        controller.pause()
    }

    override fun stop() {
        // MusicServiceController exposes no cross-platform stop; a stop is a pause with the
        // position cleared to the start, matching "stop playback and clear the position".
        controller.pause()
        controller.seekTo(0.0)
    }

    override fun next() {
        controller.next()
    }

    override fun previous() {
        controller.previous()
    }

    override fun seekTo(seconds: Double) {
        controller.seekTo(seconds)
    }

    override fun setVolume(level: Double) {
        // No-op on the device audio path (local volume is hardware/OS controlled); we only
        // retain the normalized value for status parity across engines.
        requestedVolume.value = level.coerceIn(0.0, 1.0)
    }

    override suspend fun activate(target: PlaybackTarget?) {
        // The local player is always attached; activating simply records that local playback
        // is the live routing. A null target means default local playback (R7.7).
        activeTarget.value = target
    }

    override suspend fun deactivate(retainPosition: Boolean) {
        // Detaching local playback means silencing the device so no local audio is produced
        // while another engine is active (R7.5, R7.6, R15.5).
        if (retainPosition) {
            // Pause in place; the retained position stays observable on status.position (R15.4).
            controller.pause()
        } else {
            stop()
        }
        activeTarget.value = null
    }
}

/**
 * Pure projection of the controller's [MusicState] (plus position/target/volume) into the
 * engine-agnostic [EngineStatus]. Extracted so the mapping is unit-testable without a
 * platform [MusicServiceController] instance.
 */
internal fun localEngineStatus(
    musicState: MusicState,
    position: Double,
    target: PlaybackTarget?,
    volume: Double,
): EngineStatus =
    EngineStatus(
        state = musicState.playbackState,
        currentSong = musicState.currentSong,
        position = position,
        volume = volume,
        target = target,
        error = null,
    )
