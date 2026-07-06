package org.blackcandy.shared.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoMediaTypeAudio
import platform.MediaPlayer.MPNowPlayingInfoPropertyDefaultPlaybackRate
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyIsLiveStream
import platform.MediaPlayer.MPNowPlayingInfoPropertyMediaType
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage

/**
 * Points the iOS system now-playing integration — the `MPRemoteCommandCenter` (lock screen /
 * Control Center transport buttons) and `MPNowPlayingInfoCenter` (lock-screen metadata) — at the
 * [PlaybackCoordinator]'s active engine rather than directly at the local `AVPlayer` (spec R16.7).
 *
 * ## Why this replaces the old direct wiring
 * Previously the remote commands were bound straight to [MusicServiceController] and the
 * now-playing info was published from inside it. That meant lock-screen controls always drove the
 * local player, even when audio was actually being cast (AirPlay/Chromecast) or driven by the
 * Server (`server_playback`) — the coordinator's active engine. By routing both the commands and
 * the info through the [PlaybackCoordinator], the lock screen always reflects and controls the
 * currently active Cast_Session / Playback_Session (R16.7). Under the default LOCAL routing the
 * coordinator forwards to the local engine, so behavior is unchanged from before; under the
 * AirPlay engine the coordinator's status already mirrors the local `AVPlayer` (the phone stays
 * the source), so the lock screen keeps working there too.
 *
 * ## Breaking the DI cycle
 * The coordinator depends (transitively, via `LocalPlaybackEngine`) on [MusicServiceController],
 * and this controller is created during [MusicServiceController.initMediaController] — so injecting
 * the coordinator eagerly here would risk a construction cycle. Instead this class is a
 * [KoinComponent] and resolves the coordinator (and the shared app scope) **lazily** via
 * `by inject()`; the coordinator singleton is only touched when [start] runs and when a command
 * fires, long after the graph is built.
 */
@OptIn(ExperimentalForeignApi::class)
class NowPlayingController : KoinComponent {
    private val coordinator: PlaybackCoordinator by inject()
    private val scope: CoroutineScope by inject()

    /** The id of the song last published so we only rebuild metadata/artwork when it changes. */
    private var lastSongId: Long? = null

    /** Tracks the in-flight artwork fetch so a song change can cancel a stale load. */
    private var artworkJob: Job? = null

    /** Wire the remote-command handlers and start mirroring the coordinator status. */
    fun start() {
        setupRemoteCommands()
        observeStatus()
    }

    // ---- Remote-command routing → coordinator (R16.7) -----------------------------------------

    private fun setupRemoteCommands() {
        val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()

        commandCenter.playCommand.addTargetWithHandler {
            coordinator.play()
            MPRemoteCommandHandlerStatusSuccess
        }

        commandCenter.pauseCommand.addTargetWithHandler {
            coordinator.pause()
            MPRemoteCommandHandlerStatusSuccess
        }

        commandCenter.stopCommand.addTargetWithHandler {
            coordinator.stop()
            MPRemoteCommandHandlerStatusSuccess
        }

        commandCenter.togglePlayPauseCommand.addTargetWithHandler {
            if (coordinator.status.value.state == PlaybackState.PLAYING) {
                coordinator.pause()
            } else {
                coordinator.play()
            }
            MPRemoteCommandHandlerStatusSuccess
        }

        commandCenter.nextTrackCommand.addTargetWithHandler {
            coordinator.next()
            MPRemoteCommandHandlerStatusSuccess
        }

        commandCenter.previousTrackCommand.addTargetWithHandler {
            coordinator.previous()
            MPRemoteCommandHandlerStatusSuccess
        }

        commandCenter.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val positionEvent =
                event as? MPChangePlaybackPositionCommandEvent
                    ?: return@addTargetWithHandler MPRemoteCommandHandlerStatusCommandFailed

            coordinator.seekTo(positionEvent.positionTime)
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    // ---- Now-playing info ← coordinator.status (R16.7) ----------------------------------------

    private fun observeStatus() {
        scope.launch {
            coordinator.status.collect { status ->
                updateNowPlayingInfo(status)
            }
        }
    }

    private fun updateNowPlayingInfo(status: PlaybackStatus) {
        val song = status.currentSong

        if (song == null) {
            artworkJob?.cancel()
            lastSongId = null
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
            return
        }

        val rate = if (status.state == PlaybackState.PLAYING) 1.0 else 0.0

        if (song.id != lastSongId) {
            // Song changed: rebuild the full metadata block and (re)load artwork.
            lastSongId = song.id

            val nowPlayingInfo = mutableMapOf<Any?, Any?>()
            nowPlayingInfo[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaTypeAudio
            nowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = false
            nowPlayingInfo[MPMediaItemPropertyTitle] = song.name
            nowPlayingInfo[MPMediaItemPropertyArtist] = song.artistName
            nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = song.albumName
            nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = song.duration
            nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = status.position
            nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = rate
            nowPlayingInfo[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0

            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = nowPlayingInfo

            // Resolved artwork path, falling back to the legacy large image (R17).
            updateAlbumArtwork(song.artworkUrl)
        } else {
            // Same song: only refresh the elapsed time + rate so scrubbing/pausing stays accurate.
            val nowPlayingInfo =
                MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo?.toMutableMap()
                    ?: mutableMapOf()
            nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = status.position
            nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = rate
            nowPlayingInfo[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = nowPlayingInfo
        }
    }

    private fun updateAlbumArtwork(urlString: String) {
        artworkJob?.cancel()

        val url = NSURL(string = urlString)

        artworkJob =
            scope.launch(Dispatchers.IO) {
                val data = NSData.dataWithContentsOfURL(url) ?: return@launch
                val image = UIImage(data = data)

                val artwork = MPMediaItemArtwork(boundsSize = image.size) { _ -> image }

                val nowPlayingInfo =
                    MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo?.toMutableMap()
                        ?: mutableMapOf()
                nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
                MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = nowPlayingInfo
            }
    }
}
