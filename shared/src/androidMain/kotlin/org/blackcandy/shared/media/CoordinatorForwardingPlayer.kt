package org.blackcandy.shared.media

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * A [ForwardingPlayer] that redirects **transport commands** issued against the Android
 * `MediaSession` — from the lock screen, the media notification, Android Auto, or a Bluetooth
 * remote — to the [PlaybackCoordinator]'s active engine when playback is routed away from the local
 * device (spec R16.7).
 *
 * ## How it routes
 * Under the default [PlaybackRouting.LOCAL] routing every command is delegated to the wrapped
 * ExoPlayer unchanged, so local playback (and the app's own `MediaController` path
 * — coordinator → `LocalPlaybackEngine` → this session) behaves exactly as before. Under
 * [PlaybackRouting.CLIENT_CAST] / [PlaybackRouting.SERVER_PLAYBACK] the wrapped ExoPlayer is
 * paused/detached and these commands instead drive the coordinator's active Cast_Session /
 * Playback_Session, so lock-screen controls no longer touch silent local playback.
 *
 * Delegating to `super` under LOCAL is what prevents a feedback loop: the coordinator's local engine
 * ultimately calls `MediaController.play()` on this same session, which lands back here — but since
 * routing is LOCAL it resolves to the plain ExoPlayer operation rather than calling the coordinator
 * again.
 *
 * ## Known seams (documented for tasks 20.2 / 13.2)
 * - **Metadata/position mirroring:** the session's metadata comes from the wrapped ExoPlayer's
 *   queue (kept in sync with the app queue, so title/artist/artwork stay correct), but the
 *   *playback state and position* under non-local routing still reflect the paused ExoPlayer, not
 *   the cast/server session. Fully mirroring cast/server state onto the session (e.g. by swapping in
 *   a Media3 `CastPlayer` under client-cast, or a synthetic player for server-playback) is left to
 *   the routing-switch finalization (task 20.2). The command routing here is the R16.7 requirement.
 * - **App-originated queue selection:** direct `MusicServiceController.playOn` calls made by the app
 *   while non-local routing is active also pass through this player and are redirected; server-side
 *   current-song handoff for that path is covered by R13.2 (task 20.2).
 *
 * @param player the underlying ExoPlayer that backs the MediaSession.
 * @param coordinatorProvider lazy access to the coordinator; resolved on first command so the engine
 *   graph is not eagerly instantiated while the service is starting.
 */
class CoordinatorForwardingPlayer(
    player: Player,
    private val coordinatorProvider: () -> PlaybackCoordinator,
) : ForwardingPlayer(player) {
    private val isLocalRouting: Boolean
        get() = coordinatorProvider().routing.value == PlaybackRouting.LOCAL

    override fun play() {
        if (isLocalRouting) super.play() else coordinatorProvider().play()
    }

    override fun pause() {
        if (isLocalRouting) super.pause() else coordinatorProvider().pause()
    }

    override fun seekToNext() {
        if (isLocalRouting) super.seekToNext() else coordinatorProvider().next()
    }

    override fun seekToPrevious() {
        if (isLocalRouting) super.seekToPrevious() else coordinatorProvider().previous()
    }

    override fun seekToNextMediaItem() {
        if (isLocalRouting) super.seekToNextMediaItem() else coordinatorProvider().next()
    }

    override fun seekToPreviousMediaItem() {
        if (isLocalRouting) super.seekToPreviousMediaItem() else coordinatorProvider().previous()
    }

    override fun seekTo(positionMs: Long) {
        if (isLocalRouting) super.seekTo(positionMs) else coordinatorProvider().seekTo(positionMs / 1000.0)
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (isLocalRouting) super.seekTo(mediaItemIndex, positionMs) else coordinatorProvider().seekTo(positionMs / 1000.0)
    }
}
