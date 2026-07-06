package org.blackcandy.shared.media

import kotlinx.coroutines.flow.StateFlow
import org.blackcandy.shared.models.Song

/**
 * The [PlaybackEngine] for Chromecast `client_cast` routing (spec R9, R10, R11).
 *
 * A Chromecast receiver fetches and decodes the media URL itself, so the app is a **controller
 * only** and never sends the audio bytes (R9.4). The engine hands the receiver a Cast_Stream_Url
 * (via [CastStreamUrlResolver] / [Song.castUrlOrNull]) that is independently authenticated and
 * reachable (R9.2, R9.3, R11.3); when no such URL is obtainable the Song is treated as not castable
 * (R9.6, R11.4). All observable state is driven through the pure [CastSessionMachine] so cast
 * behavior is identical and testable independent of the Cast SDK (R9.5, R10 — Property 3).
 *
 * This is an `expect class` so each platform can talk to its own Cast SDK while sharing the pure
 * state machine:
 * - **Android `actual`**: Media3 `androidx.media3.cast.CastPlayer` fed a `MediaItem` built from the
 *   resolved Cast_Stream_Url; `CastPlayer` state is adapted onto [CastSessionMachine] (R9.5).
 * - **iOS `actual`**: `GCKSessionManager` + `GCKRemoteMediaClient` loading a `GCKMediaInformation`.
 *   The GoogleCast SDK is not yet available as a Kotlin/Native cinterop dependency, so the iOS
 *   actual is a compiling, correct-shape stub that drives [CastSessionMachine] and reports the Song
 *   as not castable until the cinterop is configured (see the iOS actual's TODO).
 *
 * Platform dependencies (Cast context, resolver, scope) are supplied through each `actual`
 * constructor and the engine is constructed in the platform Koin module, keeping this shared
 * declaration platform-agnostic.
 */
expect class ChromecastEngine : PlaybackEngine {
    override val status: StateFlow<EngineStatus>

    override fun setQueue(
        songs: List<Song>,
        startIndex: Int,
    )

    override fun play()

    override fun pause()

    override fun stop()

    override fun next()

    override fun previous()

    override fun seekTo(seconds: Double)

    override fun setVolume(level: Double)

    override suspend fun activate(target: PlaybackTarget?)

    override suspend fun deactivate(retainPosition: Boolean)
}
