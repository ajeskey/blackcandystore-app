package org.blackcandy.shared.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.blackcandy.shared.data.CurrentPlaylistRepository
import org.blackcandy.shared.data.PlaybackSessionRepository
import org.blackcandy.shared.models.OutputDevice
import org.blackcandy.shared.models.PlaybackOp
import org.blackcandy.shared.models.PlaybackSession
import org.blackcandy.shared.models.Song
import org.blackcandy.shared.utils.TaskResult

/**
 * The [PlaybackEngine] for `server_playback` routing, in which the **Server** is the audio
 * source and the app acts only as a Remote_Control (spec R13, R14, design §7).
 *
 * Unlike the local/cast engines, this engine never produces audio. It:
 * 1. on [activate], synchronizes the app queue to the Server's current playlist (reusing
 *    [CurrentPlaylistRepository], R13.1), then creates/updates the Playback_Session with the
 *    selected Server_Output_Devices and the intended current Song (R13.2, R13.3, R14.1);
 * 2. forwards transport operations to the Server via [PlaybackSessionRepository.controlPlaybackSession]
 *    and adopts the returned [PlaybackSession] as truth (R14.2);
 * 3. keeps its view current through **Session_Observation** — a coroutine polls
 *    [PlaybackSessionRepository.getPlaybackSession] every [pollIntervalMillis] and the engine
 *    also adopts the session returned immediately after each control op (R14.4);
 * 4. reflects device-lost / last-device-stop straight from the Server session state (R14.6); and
 * 5. detaches local audio so no local playback occurs while active (R14.3, R7.6).
 *
 * All pure mapping (Server session → [EngineStatus]) is factored into internal functions so it
 * is testable without a live network; the network path itself is covered by integration tests
 * (task 12.3, Ktor `MockEngine`).
 *
 * @param sessionRepository wraps the Playback_Session control endpoints (R13.3, R14.1, R14.2).
 * @param currentPlaylistRepository reused to sync the app queue to the Server current playlist (R13.1, R13.4).
 * @param scope the coroutine scope non-suspending transport ops and the polling loop run in
 *   (owned by the coordinator/DI graph, cancelled when the graph is torn down). Matches
 *   [LocalPlaybackEngine].
 * @param detachLocalAudio best-effort hook to silence the local App_Player so no local audio is
 *   produced while the Server is the source (R14.3, R7.6). The coordinator also deactivates the
 *   local engine before activating this one; this hook is a defensive belt-and-suspenders that
 *   keeps the requirement satisfiable even if this engine is driven directly. Kept as a lambda
 *   (rather than a [MusicServiceController] dependency) so the engine stays constructable in
 *   common tests without a platform player.
 * @param pollIntervalMillis the Session_Observation polling interval; defaults to 5s (R14.4).
 */
class ServerPlaybackEngine(
    private val sessionRepository: PlaybackSessionRepository,
    private val currentPlaylistRepository: CurrentPlaylistRepository,
    private val scope: CoroutineScope,
    private val detachLocalAudio: () -> Unit = {},
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
) : PlaybackEngine {
    private val statusState = MutableStateFlow(EngineStatus())

    override val status: StateFlow<EngineStatus> = statusState.asStateFlow()

    /** The app queue, kept so session song ids can be mapped back to full [Song]s (R14.4). */
    private var currentQueue: List<Song> = emptyList()

    /** Index of the Song the session should start from (R13.2); used to seed the current song id. */
    private var currentStartIndex: Int = 0

    /** The Server_Output_Devices selected for this session; used to reflect device-lost (R14.6). */
    private var activeDevices: List<OutputDevice> = emptyList()

    /** Password collected for a protected AirPlay Server device, passed on create (R14.7). */
    private var devicePassword: String? = null

    /** Whether this engine is the live routing; guards queue-edit propagation (R13.4). */
    private var active: Boolean = false

    /** The Session_Observation polling loop; cancelled on deactivate. */
    private var pollingJob: Job? = null

    // ---- Queue ---------------------------------------------------------------------------------

    override fun setQueue(
        songs: List<Song>,
        startIndex: Int,
    ) {
        currentQueue = songs
        currentStartIndex = startIndex.coerceIn(0, maxOf(0, songs.lastIndex))

        // While active, propagate the queue edit to the Server current playlist so the
        // Playback_Session plays the updated queue (R13.4).
        if (active) {
            scope.launch {
                when (val result = syncQueueToServer(songs)) {
                    is TaskResult.Success -> Unit
                    is TaskResult.Failure -> emitError(result.message)
                }
            }
        }
    }

    // ---- Transport operations forward to the Server session (R14.2) ---------------------------

    override fun play() {
        // A play issued while paused is a resume; otherwise a fresh play (R14.2, resume semantics).
        val op = if (statusState.value.state == PlaybackState.PAUSED) PlaybackOp.RESUME else PlaybackOp.PLAY
        control(op)
    }

    override fun pause() = control(PlaybackOp.PAUSE)

    override fun stop() = control(PlaybackOp.STOP)

    // The Server owns advancement through its current playlist and the Playback_Session control
    // contract exposes only play/resume/pause/stop (+ volume). Next/previous/seek have no remote
    // op, so they are intentionally no-ops here; the enumerated Remote_Control ops are play,
    // resume, pause, stop and volume (R14.2).
    override fun next() = Unit

    override fun previous() = Unit

    override fun seekTo(seconds: Double) = Unit

    override fun setVolume(level: Double) {
        // The control endpoint carries volume alongside an op; use a state-preserving op so a
        // volume change never disturbs transport (R14.2). Resume keeps a playing session playing;
        // pause keeps a paused/stopped session where it is.
        val preservingOp = if (statusState.value.state == PlaybackState.PLAYING) PlaybackOp.RESUME else PlaybackOp.PAUSE
        control(preservingOp, volume = level.coerceIn(0.0, 1.0))
    }

    // ---- Activation ----------------------------------------------------------------------------

    override suspend fun activate(target: PlaybackTarget?) {
        val serverTarget =
            target as? PlaybackTarget.ServerDevice
                ?: return // Only server targets are handled by this engine.

        activeDevices = serverTarget.devices
        devicePassword = serverTarget.devicePassword
        active = true

        // Detach local audio so the Server is the sole audio source (R14.3, R7.6).
        detachLocalAudio()

        // 1. Sync the app queue to the Server current playlist before starting (R13.1).
        when (val sync = syncQueueToServer(currentQueue)) {
            is TaskResult.Success -> Unit
            is TaskResult.Failure -> {
                // The Server cannot determine what to play; surface and stay stopped (R13.5).
                emitStopped(sync.message)
                return
            }
        }

        // 2. Create/update the Playback_Session with the selected devices and current song
        //    (R13.2, R13.3, R14.1), passing any collected AirPlay password (R14.7).
        val currentSongId = currentQueue.getOrNull(currentStartIndex)?.id
        when (val result = sessionRepository.putPlaybackSession(activeDevices.map { it.id }, currentSongId, devicePassword)) {
            is TaskResult.Success -> adoptSession(result.data)
            is TaskResult.Failure -> {
                emitStopped(result.message)
                return
            }
        }

        // 3. Begin Session_Observation (R14.4).
        startPolling()
    }

    override suspend fun deactivate(retainPosition: Boolean) {
        // Stop remote-controlling: end Session_Observation and stop issuing ops (R15.3, R15.4).
        // The last observed status (including position) stays on [status] so the coordinator can
        // resume elsewhere from that point (R15.4).
        active = false
        pollingJob?.cancel()
        pollingJob = null
    }

    // ---- Internals ----------------------------------------------------------------------------

    /**
     * Forward a transport op to the Server and adopt the returned session as truth (R14.2).
     * Because the control response is the post-op session state, adopting it satisfies "reflect
     * the resulting Server state ... immediately after issuing a control operation" (R14.4).
     * On failure the Server message is surfaced and the displayed state is left unchanged (R14.5).
     */
    private fun control(
        op: PlaybackOp,
        volume: Double? = null,
    ) {
        scope.launch {
            when (val result = sessionRepository.controlPlaybackSession(op, volume, null)) {
                is TaskResult.Success -> adoptSession(result.data)
                is TaskResult.Failure -> emitError(result.message)
            }
        }
    }

    /** The Session_Observation loop: poll the Server session every [pollIntervalMillis] (R14.4). */
    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob =
            scope.launch {
                while (isActive && active) {
                    delay(pollIntervalMillis)
                    if (!active) break
                    when (val result = sessionRepository.getPlaybackSession()) {
                        is TaskResult.Success -> adoptSession(result.data)
                        is TaskResult.Failure -> Unit // Transient poll failure; keep last state.
                    }
                }
            }
    }

    /**
     * Replace the app queue on the Server current playlist with [songs] (R13.1, R13.4).
     *
     * There is no bulk "set songs" endpoint, so the sync clears the current playlist and appends
     * each Song in order via the existing [CurrentPlaylistRepository] operations. Any failure is
     * returned so the caller can surface it and stay stopped (R13.5).
     */
    private suspend fun syncQueueToServer(songs: List<Song>): TaskResult<Unit> {
        when (val cleared = currentPlaylistRepository.removeAllSongs()) {
            is TaskResult.Success -> Unit
            is TaskResult.Failure -> return TaskResult.Failure(cleared.message)
        }

        for (song in songs) {
            when (val added = currentPlaylistRepository.addSongToLast(song.id)) {
                is TaskResult.Success -> Unit
                is TaskResult.Failure -> return TaskResult.Failure(added.message)
            }
        }

        return TaskResult.Success(Unit)
    }

    /** Adopt a Server [PlaybackSession] as the engine's truth (R14.2, R14.4, R14.6). */
    private fun adoptSession(session: PlaybackSession) {
        statusState.value = engineStatusFrom(session, currentQueue, activeDevices)
    }

    /** Surface a Server/remote error without changing the displayed state (R14.5, R14.7). */
    private fun emitError(message: String?) {
        statusState.value = statusState.value.copy(error = playbackErrorFrom(message))
    }

    /** Enter a stopped state carrying the Server error (R13.5). */
    private fun emitStopped(message: String?) {
        statusState.value =
            statusState.value.copy(
                state = PlaybackState.IDLE,
                position = 0.0,
                error = playbackErrorFrom(message),
            )
    }

    companion object {
        /** Default Session_Observation polling interval (R14.4). */
        const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 5_000L
    }
}

// ---- Pure mapping (network-independent, unit-testable) -----------------------------------------

/**
 * Map a Server session `state` string (`stopped`, `playing`, `paused`) to the app's
 * [PlaybackState]. `stopped` maps to [PlaybackState.IDLE]; an unknown value is treated as
 * stopped so an unexpected Server value never crashes the UI (R14.4).
 */
internal fun serverStateToPlaybackState(state: String): PlaybackState =
    when (state.lowercase()) {
        "playing" -> PlaybackState.PLAYING
        "paused" -> PlaybackState.PAUSED
        "stopped" -> PlaybackState.IDLE
        else -> PlaybackState.IDLE
    }

/**
 * Project a Server [PlaybackSession] into the engine-agnostic [EngineStatus] (R14.4, R14.6).
 *
 * The current Song is resolved from [queue] by [PlaybackSession.currentSongId]; the reported
 * target is the selected [activeDevices] filtered to those still active in the session so a
 * device the Server dropped disappears from the target, and the last-device-stop the Server
 * reports arrives as a stopped state with no active devices (R14.6).
 */
internal fun engineStatusFrom(
    session: PlaybackSession,
    queue: List<Song>,
    activeDevices: List<OutputDevice>,
): EngineStatus {
    val remainingDevices = activeDevices.filter { it.id in session.activeDeviceIds }
    return EngineStatus(
        state = serverStateToPlaybackState(session.state),
        currentSong = session.currentSongId?.let { id -> queue.firstOrNull { it.id == id } },
        position = session.position,
        volume = session.volume,
        target = PlaybackTarget.ServerDevice(remainingDevices),
        error = null,
    )
}

/** Map a Server error message to a [PlaybackError], distinguishing auth failures (R14.7). */
internal fun playbackErrorFrom(message: String?): PlaybackError {
    val lower = message?.lowercase().orEmpty()
    val isAuth = "password" in lower || "auth" in lower || "unauthorized" in lower
    return if (isAuth) {
        PlaybackError.Authentication(message)
    } else {
        PlaybackError.Message(message ?: "Server playback error")
    }
}
