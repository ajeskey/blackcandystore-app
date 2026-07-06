package org.blackcandy.shared.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.blackcandy.shared.models.Song

/**
 * iOS Chromecast [PlaybackEngine] (spec R9, R10, R11).
 *
 * The intended iOS implementation drives a `GCKSessionManager` + `GCKRemoteMediaClient`: on
 * [activate] it attaches to the current Cast session, on [play] it loads a `GCKMediaInformation`
 * built from the Song's Cast_Stream_Url (R9.2, R9.3, R11.3) so the **receiver fetches and decodes**
 * the audio itself (R9.4), and it mirrors `GCKRemoteMediaClient` state onto [CastSessionMachine]
 * (R9.5). Like the Android actual, all observable [EngineStatus] flows through the pure
 * [CastSessionMachine] (Property 3).
 *
 * ## Status: compiling stub — GoogleCast cinterop not yet configured
 * The GoogleCast SDK is added to the iOS app target via Swift Package Manager in task 17.2
 * (`CastConfiguration.swift`, guarded by `#if canImport(GoogleCast)`), **but it is not exposed to
 * this Kotlin/Native `iosMain` source set** — there is no GoogleCast cinterop `.def` under
 * `shared/src/nativeInterop/cinterop`, so `GCKSessionManager` / `GCKRemoteMediaClient` /
 * `GCKMediaInformation` are not callable from Kotlin here. Rather than break the iOS build by
 * importing an unavailable dependency, this actual is a correct-shape stub:
 *
 * - It maintains the shared [CastSessionMachine] and exposes a valid [status] so the coordinator and
 *   UI behave consistently.
 * - It has no receiver to drive, so [play] reports the current Song as not castable on iOS
 *   (`targetReachable = false`, R9.6/R10.5) instead of pretending to cast; transport ops are no-ops
 *   beyond keeping the machine in a valid state (R10.8).
 *
 * ## TODO(GCK cinterop): complete the real integration
 * When a GoogleCast cinterop is configured for `iosMain` (add a `GoogleCast.def` cinterop wiring the
 * SPM/CocoaPods framework and register it in `shared/build.gradle.kts`):
 *   1. In [activate], obtain `GCKCastContext.sharedInstance().sessionManager` and its current
 *      `GCKCastSession.remoteMediaClient`.
 *   2. In [play]/[setQueue], build `GCKMediaInformation(contentURL = <Cast_Stream_Url>)` from
 *      [castStreamUrlResolver] and call `remoteMediaClient.loadMedia(...)` (R9.2, R9.3).
 *   3. Add a `GCKRemoteMediaClientListener` translating receiver state into [CastSessionMachine]
 *      transitions (R9.5), including disconnect handling (R10.6).
 *
 * The constructor already accepts the [castStreamUrlResolver] and [scope] the real implementation
 * needs, so wiring the cinterop later requires no signature change here or in the Koin module.
 *
 * @param castStreamUrlResolver resolves each Song's receiver-fetchable Cast_Stream_Url (R11); used
 *   by the real implementation once the GCK cinterop is available.
 * @param scope coroutine scope for cast-URL resolution; owned by the DI graph.
 */
actual class ChromecastEngine(
    @Suppress("unused") private val castStreamUrlResolver: CastStreamUrlResolver,
    @Suppress("unused") private val scope: CoroutineScope,
) : PlaybackEngine {
    private var machine = CastSessionMachine()
    private var target: PlaybackTarget? = null
    private var queue: List<Song> = emptyList()
    private var currentIndex: Int = 0

    private val statusState = MutableStateFlow(machine.toEngineStatus(target))
    actual override val status: StateFlow<EngineStatus> = statusState.asStateFlow()

    actual override fun setQueue(
        songs: List<Song>,
        startIndex: Int,
    ) {
        queue = songs
        currentIndex = startIndex.coerceIn(0, maxOf(0, songs.lastIndex))
        // TODO(GCK cinterop): resolve each Song's Cast_Stream_Url and preload onto the receiver.
    }

    actual override fun play() {
        val song = queue.getOrNull(currentIndex) ?: return

        // Not castable when no Cast_Stream_Url is obtainable (R9.6, R11.4).
        if (song.castUrlOrNull() == null) {
            update {
                machine.copy(
                    state = CastSessionState.STOPPED,
                    currentSong = song,
                    position = 0.0,
                    error = PlaybackError.SongUnavailable,
                )
            }
            return
        }

        if (machine.state == CastSessionState.PAUSED) {
            update { machine.resume() }
            return
        }

        // TODO(GCK cinterop): loadMedia on GCKRemoteMediaClient. Until the SDK is callable from
        // Kotlin/Native there is no reachable receiver, so the cast is rejected as not-reachable
        // (R10.5) rather than silently claiming to play.
        update { machine.play(song, machine.position, targetReachable = false) }
    }

    actual override fun pause() = update { machine.pause() }

    actual override fun stop() = update { machine.stop() }

    actual override fun next() {
        currentIndex = (currentIndex + 1).coerceAtMost(maxOf(0, queue.lastIndex))
    }

    actual override fun previous() {
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
    }

    actual override fun seekTo(seconds: Double) = update { machine.copy(position = seconds) }

    actual override fun setVolume(level: Double) = update { machine.setVolume(level) }

    actual override suspend fun activate(target: PlaybackTarget?) {
        this.target = target
        pushStatus()
        // TODO(GCK cinterop): attach to GCKSessionManager's current GCKCastSession.
    }

    actual override suspend fun deactivate(retainPosition: Boolean) {
        if (retainPosition) update { machine.pause() } else update { machine.stop() }
        target = null
        // TODO(GCK cinterop): end/leave the GCKCastSession as appropriate.
    }

    private inline fun update(transition: () -> CastSessionMachine) {
        machine = transition()
        pushStatus()
    }

    private fun pushStatus() {
        statusState.value = machine.toEngineStatus(target)
    }
}
