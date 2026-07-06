package org.blackcandy.shared.media

import org.blackcandy.shared.models.Song

/**
 * The three states a Cast_Session can occupy. Modeled as an enum so that a session
 * is always in *exactly one* of these states, structurally guaranteeing the R10.8
 * invariant (validated by Property 3).
 */
enum class CastSessionState {
    STOPPED,
    PLAYING,
    PAUSED,
}

/**
 * Pure, immutable state machine for a client-cast session (spec R10), shared by both
 * the Chromecast and AirPlay engines so casting behavior is identical and testable
 * independent of the Cast SDK / AVPlayer route picker.
 *
 * Every transition returns a new [CastSessionMachine]; no coroutines, timers, or SDK
 * calls live here. The engine owns the actual device I/O and the 30s watchdog timer,
 * then feeds discrete events ([onDisconnect], [onPlaybackStalledTimeout]) into this
 * machine, which decides the resulting state deterministically.
 *
 * Invariants (Property 3):
 * - [state] is always exactly one of [CastSessionState] (R10.8).
 * - A [resume] applied directly after a [pause], with no intervening operation, returns
 *   the session to [CastSessionState.PLAYING] with the same [currentSong] and [position]
 *   held at the pause (R10.9).
 *
 * @property state the current cast state (R10.8).
 * @property currentSong the song targeted by the session, or null when never started.
 * @property position the playback position in seconds; retained across pause (R10.2),
 *   cleared on stop / disconnect / timeout (R10.3, R10.6, R10.7).
 * @property volume the target volume on a normalized 0.0–1.0 scale (R10.4).
 * @property error the most recent failure that forced a stop, or null when none.
 */
data class CastSessionMachine(
    val state: CastSessionState = CastSessionState.STOPPED,
    val currentSong: Song? = null,
    val position: Double = 0.0,
    val volume: Double = 1.0,
    val error: PlaybackError? = null,
) {
    /**
     * Start casting [song] from [positionSeconds] (R10.1).
     *
     * Reachability rejection (R10.5): if [targetReachable] is false the cast is rejected —
     * the session goes to [CastSessionState.STOPPED] with a [PlaybackError.NotReachable]
     * and a cleared position rather than entering [CastSessionState.PLAYING].
     */
    fun play(
        song: Song,
        positionSeconds: Double = 0.0,
        targetReachable: Boolean = true,
    ): CastSessionMachine =
        if (!targetReachable) {
            copy(
                state = CastSessionState.STOPPED,
                currentSong = song,
                position = 0.0,
                error = PlaybackError.NotReachable,
            )
        } else {
            copy(
                state = CastSessionState.PLAYING,
                currentSong = song,
                position = positionSeconds,
                error = null,
            )
        }

    /**
     * Resume playback on the target (R10.1). A resume from [CastSessionState.PAUSED]
     * returns to [CastSessionState.PLAYING] while retaining the exact [currentSong] and
     * [position] held at the pause (R10.9). A resume while already playing is idempotent;
     * a resume from a stopped session (nothing to resume) is a no-op.
     */
    fun resume(): CastSessionMachine =
        if (state == CastSessionState.PAUSED || state == CastSessionState.PLAYING) {
            copy(state = CastSessionState.PLAYING, error = null)
        } else {
            this
        }

    /**
     * Pause the target (R10.2). Valid only from [CastSessionState.PLAYING]; the current
     * song and position are retained so a subsequent [resume] can restore them (R10.9).
     * Pausing from any other state is a no-op.
     */
    fun pause(): CastSessionMachine =
        if (state == CastSessionState.PLAYING) {
            copy(state = CastSessionState.PAUSED, error = null)
        } else {
            this
        }

    /**
     * Stop the target, clearing the position and returning to [CastSessionState.STOPPED]
     * (R10.3). The [currentSong] is retained as the last-cast song; any prior error is cleared.
     */
    fun stop(): CastSessionMachine =
        copy(
            state = CastSessionState.STOPPED,
            position = 0.0,
            error = null,
        )

    /**
     * Set the target device volume on a normalized 0.0–1.0 scale (R10.4). Values outside
     * the range are clamped. Volume changes never alter the playback [state].
     */
    fun setVolume(level: Double): CastSessionMachine = copy(volume = level.coerceIn(0.0, 1.0))

    /**
     * Handle the target device disconnecting (R10.6). While the session is not already
     * stopped, this forces [CastSessionState.STOPPED], clears the position, and reports
     * [PlaybackError.DeviceDisconnected] so the UI can indicate casting stopped because
     * the device disconnected. A disconnect on an already-stopped session is a no-op.
     */
    fun onDisconnect(): CastSessionMachine =
        if (state != CastSessionState.STOPPED) {
            copy(
                state = CastSessionState.STOPPED,
                position = 0.0,
                error = PlaybackError.DeviceDisconnected,
            )
        } else {
            this
        }

    /**
     * Handle the 30s no-audio watchdog firing after a play request (R10.7). The engine owns
     * the timer; this transition stops the cast and reports [PlaybackError.Timeout] so the UI
     * can indicate the song is currently unavailable. Only meaningful while playing (a play
     * request is outstanding); a no-op otherwise.
     */
    fun onPlaybackStalledTimeout(): CastSessionMachine =
        if (state == CastSessionState.PLAYING) {
            copy(
                state = CastSessionState.STOPPED,
                position = 0.0,
                error = PlaybackError.Timeout,
            )
        } else {
            this
        }

    /**
     * Project this pure cast state onto an [EngineStatus] for the given [target] so a cast
     * engine can expose it uniformly (R10). [CastSessionState.STOPPED] maps to
     * [PlaybackState.IDLE].
     */
    fun toEngineStatus(target: PlaybackTarget? = null): EngineStatus =
        EngineStatus(
            state =
                when (state) {
                    CastSessionState.STOPPED -> PlaybackState.IDLE
                    CastSessionState.PLAYING -> PlaybackState.PLAYING
                    CastSessionState.PAUSED -> PlaybackState.PAUSED
                },
            currentSong = currentSong,
            position = position,
            volume = volume,
            target = target,
            error = error,
        )
}
