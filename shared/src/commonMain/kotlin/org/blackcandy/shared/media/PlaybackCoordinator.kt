package org.blackcandy.shared.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.blackcandy.shared.models.OutputDeviceProtocol
import org.blackcandy.shared.models.Song

/**
 * Sits above the three [PlaybackEngine]s (local, client-cast, server-playback) and routes all
 * transport operations to exactly one active engine at a time (spec R7, R15).
 *
 * The coordinator is deliberately written against the [PlaybackEngine] interface only: it holds
 * no knowledge of concrete engine implementations, and engines that a platform lacks (for
 * example AirPlay on Android, or the cast engines before Phase 4) are passed as `null`. This
 * keeps the routing-exclusivity logic pure and testable with fake [PlaybackEngine] instances
 * (the routing-exclusivity property test is task 11.4).
 *
 * ## Routing switch logic (R15)
 * [selectTarget] resolves the requested [PlaybackTarget] to a target engine and routing, then:
 * 1. deactivates the current engine retaining its position (R15.4),
 * 2. transfers the queue and last-known position to the target engine,
 * 3. activates the target engine on the target, and
 * 4. resumes playback where the previous engine was playing.
 *
 * The current engine is always deactivated **before** the next engine is activated, so at most
 * one engine is ever active (R7.4, R15.1, R15.5).
 *
 * @param local the always-present local App_Player engine; the default routing (R7.1, R7.7).
 * @param chromecast the Chromecast client-cast engine, or null where unsupported (Phase 4).
 * @param airplay the AirPlay client-cast engine, or null on non-iOS platforms (R12.1).
 * @param serverPlayback the server-driven playback engine (Remote_Control role, R14).
 * @param scope the scope used to observe the active engine's status flow.
 * @param playbackModeReporter optional best-effort routing-parity reporter (R7.3); see task 12.2.
 */
class PlaybackCoordinator(
    private val local: PlaybackEngine,
    private val chromecast: PlaybackEngine?,
    private val airplay: PlaybackEngine?,
    private val serverPlayback: PlaybackEngine,
    private val scope: CoroutineScope,
    private val playbackModeReporter: PlaybackModeReporter? = null,
) {
    /** The engine currently receiving transport operations. Starts as the local engine (R7.1). */
    private var activeEngine: PlaybackEngine = local

    /** The last queue set through the coordinator, transferred across engine switches (R15.4). */
    private var currentQueue: List<Song> = emptyList()

    /** The start index last set through the coordinator; a fallback when no current song. */
    private var currentStartIndex: Int = 0

    /** The currently selected target; null means default local device playback (R7.7). */
    private var selectedTarget: PlaybackTarget? = null

    private val routingState = MutableStateFlow(PlaybackRouting.LOCAL)

    /** The active [PlaybackRouting] (R7.4). */
    val routing: StateFlow<PlaybackRouting> = routingState.asStateFlow()

    private val statusState = MutableStateFlow(PlaybackStatus())

    /** The unified, engine-agnostic playback snapshot (R7, R16). */
    val status: StateFlow<PlaybackStatus> = statusState.asStateFlow()

    private var statusJob: Job? = null

    init {
        observe(local)
    }

    // ---- Routing selection (R7, R15) ----------------------------------------------------------

    /**
     * Select where and how playback happens. Drives the routing + engine switch per R15.
     *
     * A `null` target (or [PlaybackTarget.LocalDevice]) returns to local device playback (R7.7).
     * A [PlaybackTarget.LocalCastDevice] enters [PlaybackRouting.CLIENT_CAST] on the engine that
     * speaks the device's protocol; a [PlaybackTarget.ServerDevice] enters
     * [PlaybackRouting.SERVER_PLAYBACK].
     *
     * If the target requires an engine this platform lacks (for example a Chromecast device
     * before Phase 4, or an AirPlay device on Android), the request is rejected by surfacing a
     * [PlaybackError] on [status] and the current routing/engine is left untouched — preserving
     * the "exactly one active engine" invariant (R7.4, R15.5).
     */
    suspend fun selectTarget(target: PlaybackTarget?) {
        val resolved =
            resolve(target) ?: run {
                surfaceUnsupported(target)
                return
            }
        val (nextEngine, nextRouting) = resolved

        if (nextEngine === activeEngine) {
            // Same engine, possibly a different device within the same routing (e.g. one cast
            // speaker to another). Re-activate on the new target without a full engine switch.
            nextEngine.activate(target)
            selectedTarget = target
            setRouting(nextRouting)
            pushStatus()
            return
        }

        // Capture the current queue + position so we can resume on the next engine (R15.4).
        val previousStatus = activeEngine.status.value
        val retainedPosition = previousStatus.position
        val wasPlaying = previousStatus.state == PlaybackState.PLAYING
        val transferIndex = resolveTransferIndex(previousStatus.currentSong)

        // 1. Deactivate the current engine BEFORE activating the next (R15.1, R15.2, R15.3, R15.5).
        activeEngine.deactivate(retainPosition = true)

        // 2. Swap the active engine and start observing its status.
        switchActiveEngine(nextEngine)

        // 3. Transfer the queue + position to the next engine.
        if (currentQueue.isNotEmpty()) {
            nextEngine.setQueue(currentQueue, transferIndex)
            if (retainedPosition > 0.0) {
                nextEngine.seekTo(retainedPosition)
            }
        }

        // 4. Activate the next engine on the selected target.
        nextEngine.activate(target)
        selectedTarget = target
        setRouting(nextRouting)

        // 5. Resume where the previous engine was playing (R15.4).
        if (wasPlaying) {
            nextEngine.play()
        }

        pushStatus()
    }

    // ---- Transport operations forward to the active engine ------------------------------------

    /** Set the playing queue and the index to start from, retained across engine switches. */
    fun setQueue(
        songs: List<Song>,
        startIndex: Int,
    ) {
        currentQueue = songs
        currentStartIndex = startIndex.coerceIn(0, maxOf(0, songs.lastIndex))
        activeEngine.setQueue(songs, startIndex)
    }

    fun play() = activeEngine.play()

    fun pause() = activeEngine.pause()

    fun stop() = activeEngine.stop()

    fun next() = activeEngine.next()

    fun previous() = activeEngine.previous()

    fun seekTo(seconds: Double) = activeEngine.seekTo(seconds)

    fun setVolume(level: Double) = activeEngine.setVolume(level)

    // ---- Internals ----------------------------------------------------------------------------

    /**
     * Map a [PlaybackTarget] to the engine that should handle it and the routing it implies.
     * Returns null when the target requires an engine this platform does not provide.
     */
    private fun resolve(target: PlaybackTarget?): Pair<PlaybackEngine, PlaybackRouting>? =
        when (target) {
            null, PlaybackTarget.LocalDevice -> local to PlaybackRouting.LOCAL
            is PlaybackTarget.LocalCastDevice -> {
                val engine =
                    when (target.device.protocol) {
                        OutputDeviceProtocol.CHROMECAST -> chromecast
                        OutputDeviceProtocol.AIRPLAY -> airplay
                    }
                engine?.let { it to PlaybackRouting.CLIENT_CAST }
            }
            is PlaybackTarget.ServerDevice -> serverPlayback to PlaybackRouting.SERVER_PLAYBACK
        }

    private fun resolveTransferIndex(currentSong: Song?): Int {
        if (currentSong == null) return currentStartIndex
        val index = currentQueue.indexOfFirst { it.id == currentSong.id }
        return if (index >= 0) index else currentStartIndex
    }

    private fun switchActiveEngine(engine: PlaybackEngine) {
        activeEngine = engine
        observe(engine)
    }

    private fun observe(engine: PlaybackEngine) {
        statusJob?.cancel()
        statusJob =
            scope.launch {
                engine.status.collect { engineStatus ->
                    statusState.value =
                        PlaybackStatus.from(routingState.value, selectedTarget, engineStatus)
                }
            }
    }

    private fun setRouting(routing: PlaybackRouting) {
        val changed = routingState.value != routing
        routingState.value = routing
        if (changed) {
            reportRouting(routing)
        }
    }

    /** Push a status recomputed from the active engine + current routing/target. */
    private fun pushStatus() {
        statusState.value =
            PlaybackStatus.from(routingState.value, selectedTarget, activeEngine.status.value)
    }

    /** Best-effort routing-parity report; never blocks the switch on failure (R7.3). */
    private fun reportRouting(routing: PlaybackRouting) {
        val reporter = playbackModeReporter ?: return
        scope.launch {
            try {
                reporter.reportRouting(routing)
            } catch (_: Exception) {
                // R7.3: honor the local selection regardless of the report outcome.
            }
        }
    }

    private fun surfaceUnsupported(target: PlaybackTarget?) {
        val name = (target as? PlaybackTarget.LocalCastDevice)?.device?.name
        val message =
            if (name != null) {
                "Casting to $name is not supported on this device."
            } else {
                "The selected playback target is not supported on this device."
            }
        statusState.value = statusState.value.copy(error = PlaybackError.Message(message))
    }
}
