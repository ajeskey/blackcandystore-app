package org.blackcandy.shared.media

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC
import androidx.media3.common.C.USAGE_MEDIA
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject

class MusicService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    // Lazy so the engine graph is not built while the service is starting; only touched when a
    // system transport command actually fires (R16.7).
    private val playbackCoordinator: PlaybackCoordinator by inject()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val dataSourceFactory: DataSource.Factory = get()

        val audioAttributes =
            AudioAttributes
                .Builder()
                .setContentType(AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(USAGE_MEDIA)
                .build()

        val player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory),
                ).setTrackSelector(
                    DefaultTrackSelector(this).apply {
                        setParameters(
                            buildUponParameters().apply {
                                setAudioOffloadPreferences(
                                    TrackSelectionParameters.AudioOffloadPreferences.DEFAULT
                                        .buildUpon()
                                        .apply {
                                            setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                                        }.build(),
                                )
                            },
                        )
                    },
                ).setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .build()

        // Route system now-playing transport commands (lock screen / notification) to the
        // PlaybackCoordinator's active engine when playback is not local, so they drive the active
        // Cast_Session / Playback_Session rather than silent local playback (R16.7). Under LOCAL
        // routing the wrapped ExoPlayer handles everything exactly as before.
        val sessionPlayer =
            CoordinatorForwardingPlayer(player) { playbackCoordinator }

        mediaSession = MediaSession.Builder(this, sessionPlayer).build()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player!!
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
}
