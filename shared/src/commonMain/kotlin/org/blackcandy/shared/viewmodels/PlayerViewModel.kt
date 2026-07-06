package org.blackcandy.shared.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.blackcandy.shared.data.CurrentPlaylistRepository
import org.blackcandy.shared.data.FavoritePlaylistRepository
import org.blackcandy.shared.media.MusicServiceController
import org.blackcandy.shared.media.MusicState
import org.blackcandy.shared.media.PlaybackCoordinator
import org.blackcandy.shared.media.PlaybackRouting
import org.blackcandy.shared.media.PlaybackStatus
import org.blackcandy.shared.media.PlaybackTarget
import org.blackcandy.shared.models.AlertMessage
import org.blackcandy.shared.models.Song
import org.blackcandy.shared.utils.TaskResult

/**
 * UI state for the player.
 *
 * Transport-facing fields ([musicState].currentSong / playbackState, [currentPosition],
 * [routing], [target]) are projected from [PlaybackCoordinator.status] so the UI reflects the
 * active routing (local / client-cast / server-playback) rather than the local player directly
 * (spec R14.3, R16.2, R16.3, R16.5). The queue ([musicState].playlist) and the repeat/shuffle
 * [musicState].playbackMode remain owned by the local [MusicServiceController]; each queued
 * [Song] carries its own [Song.isAvailable] so playlist UIs can disable-but-list unavailable
 * songs (R16.4, R16.6).
 */
data class PlayerUiState(
    val musicState: MusicState = MusicState(),
    val currentPosition: Double = 0.0,
    val routing: PlaybackRouting = PlaybackRouting.LOCAL,
    val target: PlaybackTarget? = null,
    val alertMessage: AlertMessage? = null,
)

class PlayerViewModel(
    private val playbackCoordinator: PlaybackCoordinator,
    private val musicServiceController: MusicServiceController,
    private val favoritePlaylistRepository: FavoritePlaylistRepository,
    private val currentPlaylistRepository: CurrentPlaylistRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())

    val uiState =
        combine(
            _uiState,
            musicServiceController.musicState,
            playbackCoordinator.status,
        ) { state, musicState, status ->
            playerUiState(state, musicState, status)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlayerUiState(),
        )

    // ---- Transport actions route through the coordinator (R14.3, R16.3) -----------------------
    // Under LOCAL routing the coordinator forwards to the local engine (the existing
    // MusicServiceController), so device playback behaves exactly as before (R16.3). Under
    // server_playback routing these drive the Server session instead of producing local audio
    // (R14.3).

    fun previous() {
        playbackCoordinator.previous()
    }

    fun next() {
        playbackCoordinator.next()
    }

    fun play() {
        playbackCoordinator.play()
    }

    fun pause() {
        playbackCoordinator.pause()
    }

    fun seekTo(seconds: Double) {
        playbackCoordinator.seekTo(seconds)
    }

    fun seekToRatio(ratio: Double) {
        val duration =
            uiState.value.musicState.currentSong
                ?.duration ?: return
        seekTo(duration * ratio)
    }

    fun playOn(songId: Long) {
        when (val decision = resolvePlayOn(uiState.value.musicState.playlist, songId)) {
            is PlayOnDecision.Play -> musicServiceController.playOn(decision.index)

            // Reject explicit selection of an unavailable song: notify and keep the current song
            // unchanged (R2.6, R16.4).
            PlayOnDecision.Unavailable ->
                _uiState.update {
                    it.copy(alertMessage = AlertMessage.LocalizedString(AlertMessage.DefinedMessages.SONG_UNAVAILABLE))
                }

            PlayOnDecision.Ignore -> Unit
        }
    }

    fun clearPlaylist() {
        musicServiceController.clearPlaylist()

        viewModelScope.launch {
            when (val result = currentPlaylistRepository.removeAllSongs()) {
                is TaskResult.Success -> {
                    Unit
                }

                is TaskResult.Failure -> {
                    _uiState.update { it.copy(alertMessage = AlertMessage.String(result.message)) }
                }
            }
        }
    }

    fun removeSongFromPlaylist(songId: Long) {
        val song =
            uiState.value.musicState.playlist
                .firstOrNull { it.id == songId } ?: return

        musicServiceController.deleteSongFromPlaylist(song)

        viewModelScope.launch {
            when (val result = currentPlaylistRepository.removeSong(song.id)) {
                is TaskResult.Success -> {
                    Unit
                }

                is TaskResult.Failure -> {
                    _uiState.update { it.copy(alertMessage = AlertMessage.String(result.message)) }
                }
            }
        }
    }

    fun moveSongInPlaylist(
        from: Int,
        to: Int,
    ) {
        val playlist = uiState.value.musicState.playlist
        val songId = playlist[from].id
        val destinationSongId = playlist[to].id

        musicServiceController.moveSongInPlaylist(from, to)

        viewModelScope.launch {
            when (val result = currentPlaylistRepository.moveSong(songId, destinationSongId)) {
                is TaskResult.Success -> {
                    Unit
                }

                is TaskResult.Failure -> {
                    _uiState.update { it.copy(alertMessage = AlertMessage.String(result.message)) }
                }
            }
        }
    }

    fun nextMode() {
        musicServiceController.setPlaybackMode(uiState.value.musicState.playbackMode.next)
    }

    fun toggleFavorite() {
        val currentSong = uiState.value.musicState.currentSong ?: return

        musicServiceController.updateSongInPlaylist(currentSong.copy(isFavorited = !currentSong.isFavorited))

        viewModelScope.launch {
            when (val result = favoritePlaylistRepository.toggleSong(currentSong)) {
                is TaskResult.Success -> {
                    Unit
                }

                is TaskResult.Failure -> {
                    // Rollback favorite state in previous operation
                    musicServiceController.updateSongInPlaylist(currentSong)

                    _uiState.update { it.copy(alertMessage = AlertMessage.String(result.message)) }
                }
            }
        }
    }

    fun alertMessageShown() {
        _uiState.update { it.copy(alertMessage = null) }
    }
}

/**
 * The outcome of an explicit playlist song selection.
 *
 * Extracted as a pure decision so the unavailable-song guard (R2.6, R16.4) is unit-testable
 * without a coroutine scope or a platform player, following the module convention (see
 * `DevicePickerViewModelTest`, `localEngineStatus`).
 */
internal sealed interface PlayOnDecision {
    /** The selected song is available; play the queue entry at [index]. */
    data class Play(val index: Int) : PlayOnDecision

    /** The selected song is present but unavailable; reject and keep the current song (R2.6). */
    data object Unavailable : PlayOnDecision

    /** No queue entry matches the requested song id; do nothing. */
    data object Ignore : PlayOnDecision
}

/** Resolve what should happen when the user explicitly selects [songId] from [playlist]. */
internal fun resolvePlayOn(
    playlist: List<Song>,
    songId: Long,
): PlayOnDecision {
    val index = playlist.indexOfFirst { it.id == songId }
    return when {
        index == -1 -> PlayOnDecision.Ignore
        !playlist[index].isAvailable -> PlayOnDecision.Unavailable
        else -> PlayOnDecision.Play(index)
    }
}

/**
 * Pure projection of the local [musicState] and the coordinator's unified [status] into the
 * player [PlayerUiState].
 *
 * The current song, playback state, and position come from [status] so they follow the active
 * routing (local / client-cast / server-playback), while the queue and repeat/shuffle mode stay
 * sourced from the local [musicState] (R14.3, R16.2, R16.3, R16.6). Extracted so the projection
 * is unit-testable without a live [PlaybackCoordinator] or platform [MusicServiceController].
 */
internal fun playerUiState(
    base: PlayerUiState,
    musicState: MusicState,
    status: PlaybackStatus,
): PlayerUiState =
    base.copy(
        musicState =
            musicState.copy(
                currentSong = status.currentSong,
                playbackState = status.state,
            ),
        currentPosition = status.position,
        routing = status.routing,
        target = status.target,
    )
