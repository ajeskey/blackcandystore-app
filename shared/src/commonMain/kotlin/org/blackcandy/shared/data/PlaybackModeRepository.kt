package org.blackcandy.shared.data

import org.blackcandy.shared.api.BlackCandyService
import org.blackcandy.shared.media.PlaybackModeReporter
import org.blackcandy.shared.media.PlaybackRouting

/**
 * Concrete [PlaybackModeReporter] backed by [BlackCandyService.setPlaybackMode] (spec R7.3).
 *
 * The [org.blackcandy.shared.media.PlaybackCoordinator] calls [reportRouting] whenever the
 * active routing changes, so the Server can record the app's selected Playback_Mode for parity.
 *
 * Per R7.3 the report is strictly best-effort: WHERE the Server supports recording the selected
 * Playback_Mode the app reports it, but IF that report fails or the Server rejects the value the
 * app still honors the user's local routing selection and never blocks casting or remote control
 * on the outcome. This is upheld in two ways:
 *  - [BlackCandyService.setPlaybackMode] returns an [org.blackcandy.shared.api.ApiResponse] and
 *    does not throw for API/HTTP failures; this repository simply ignores the result.
 *  - The wire routing string is resolved locally, so no server round-trip is required to decide
 *    routing on the device.
 *
 * The wire routing values mirror the server's `Playback_Mode` contract (requirements glossary):
 * `client_cast`, `server_playback`, plus the default `local` App_Player playback.
 */
class PlaybackModeRepository(
    private val service: BlackCandyService,
) : PlaybackModeReporter {
    override suspend fun reportRouting(routing: PlaybackRouting) {
        // Best-effort (R7.3): fire the report and discard the outcome. setPlaybackMode returns an
        // ApiResponse rather than throwing, so a failure or server rejection cannot block the
        // caller or the local routing selection.
        service.setPlaybackMode(routing.wireValue)
    }
}

/**
 * The Server-facing `Playback_Mode` wire string for a [PlaybackRouting] (requirements glossary):
 * [PlaybackRouting.CLIENT_CAST] -> `client_cast`, [PlaybackRouting.SERVER_PLAYBACK] ->
 * `server_playback`, and [PlaybackRouting.LOCAL] -> `local` (the default device playback).
 */
private val PlaybackRouting.wireValue: String
    get() =
        when (this) {
            PlaybackRouting.LOCAL -> "local"
            PlaybackRouting.CLIENT_CAST -> "client_cast"
            PlaybackRouting.SERVER_PLAYBACK -> "server_playback"
        }
