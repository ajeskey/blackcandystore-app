package org.blackcandy.shared.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.blackcandy.shared.models.Song
import platform.AVFAudio.AVAudioSessionPortAirPlay
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangePreviousRouteKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionRouteDescription
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol

/**
 * The iOS-only [PlaybackEngine] for AirPlay `client_cast` routing (spec R8, R10).
 *
 * AirPlay is fundamentally different from Chromecast: iOS routes the **decoded output of the local
 * `AVPlayer` to the selected AirPlay device**, so the phone stays the audio source (R8.2). This
 * engine therefore does not send media URLs to a receiver — it **reuses the existing local
 * playback path** ([MusicServiceController], which already builds each `AVURLAsset` from
 * [Song.playbackUrl] with the app's own `Authorization`/`User-Agent` headers), and simply keeps
 * that player producing audio while iOS handles the routing. Remote (proxied) songs play exactly
 * like local ones because they share the same [MusicServiceController] asset setup (R8.1, R8.2).
 *
 * ## Why this engine is iOS-only (not `expect`/`actual`)
 * Android has no supported native AirPlay sender (R8.5, R12.1), so there is nothing to make common.
 * The class lives only in `iosMain` and is registered only by the iOS Koin module; the
 * [PlaybackCoordinator] receives it through its `airplay` parameter on iOS and `null` on Android.
 *
 * ## Device + password selection: the `AVRoutePickerView` seam (R8.3)
 * Apple does not let third-party apps enumerate named AirPlay devices or collect the device
 * password themselves — selection and any password entry are owned by the **system route picker**
 * (`AVRoutePickerView`, a UIKit/SwiftUI view). This engine therefore must **not** collect the
 * AirPlay password (R8.3). Instead it exposes a presentation seam the SwiftUI layer wires to an
 * `AVRoutePickerView`:
 * - [routePickerRequested] — a [StateFlow] the SwiftUI player screen observes; when it flips to
 *   `true` the UI programmatically triggers its hidden `AVRoutePickerView` (which shows the system
 *   device list + password prompt), then calls [onRoutePickerPresented] to reset it.
 * - [routePickerPresenter] — an optional direct callback the SwiftUI layer may assign instead of
 *   observing the flow; invoked when the engine wants the picker shown.
 *
 * A minimal SwiftUI wiring looks like:
 * ```swift
 * struct RoutePicker: UIViewRepresentable {                 // wraps AVRoutePickerView
 *     func makeUIView(context: Context) -> AVRoutePickerView { AVRoutePickerView() }
 *     func updateUIView(_ v: AVRoutePickerView, context: Context) {}
 * }
 * // observe engine.routePickerRequested; when true, send a .touchUpInside to the picker's button
 * // (or just render the AVRoutePickerView button in the player and let the user tap it directly),
 * // then call engine.onRoutePickerPresented().
 * ```
 * Because the picker drives the shared `AVAudioSession` route, the local `AVPlayer` output follows
 * the chosen route automatically — no per-song URL handoff is needed.
 *
 * ## State + disconnect handling (R10)
 * Observable [EngineStatus] is driven through the shared pure [CastSessionMachine] for consistency
 * with the Chromecast engine (state, target, error, volume), enriched with the real current song
 * and position from the [MusicServiceController] (which is the actual audio source). While the
 * session is `playing`, if iOS reports the AirPlay route became unavailable
 * (`AVAudioSessionRouteChangeReasonOldDeviceUnavailable` on a route that carried an AirPlay
 * output), the engine pauses the local player and drives [CastSessionMachine.onDisconnect] →
 * `stopped` with [PlaybackError.DeviceDisconnected] (R8.4 → R10.6).
 *
 * @param controller the shared local audio player reused as the AirPlay audio source (R8.1, R8.2).
 * @param scope the coroutine scope the exposed [status] flow is shared in (owned by the DI graph).
 */
@OptIn(ExperimentalForeignApi::class)
class AirPlayEngine(
    private val controller: MusicServiceController,
    scope: CoroutineScope,
) : PlaybackEngine {
    /** Pure cast state; the single source of truth for the session's state/target/error (R10.8). */
    private val machineState = MutableStateFlow(CastSessionMachine())
    private var machine: CastSessionMachine
        get() = machineState.value
        set(value) {
            machineState.value = value
        }

    /** The active cast target (the selected AirPlay [PlaybackTarget.LocalCastDevice]), or null. */
    private val targetState = MutableStateFlow<PlaybackTarget?>(null)

    /** Route-loss observer, registered only while this engine is the active routing. */
    private var routeChangeObserver: NSObjectProtocol? = null

    // ---- AVRoutePickerView presentation seam (R8.3) -------------------------------------------

    private val routePickerRequestedState = MutableStateFlow(false)

    /**
     * Observed by the SwiftUI layer: when `true`, present the system `AVRoutePickerView` so the
     * user can pick the AirPlay device and enter any device password (R8.3), then call
     * [onRoutePickerPresented].
     */
    val routePickerRequested: StateFlow<Boolean> = routePickerRequestedState.asStateFlow()

    /**
     * Optional direct hook for the SwiftUI layer: assign a closure that triggers the app's hidden
     * `AVRoutePickerView`. Invoked in addition to flipping [routePickerRequested] so the UI can use
     * whichever mechanism it prefers.
     */
    var routePickerPresenter: (() -> Unit)? = null

    /** Called by the SwiftUI layer once it has presented the route picker, to reset the request. */
    fun onRoutePickerPresented() {
        routePickerRequestedState.value = false
    }

    // ---- Status --------------------------------------------------------------------------------

    override val status: StateFlow<EngineStatus> =
        combine(
            controller.musicState,
            controller.currentPosition,
            machineState,
            targetState,
        ) { musicState, position, machineSnapshot, target ->
            airPlayEngineStatus(machineSnapshot, musicState, position, target)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue =
                airPlayEngineStatus(
                    machineState.value,
                    controller.musicState.value,
                    0.0,
                    targetState.value,
                ),
        )

    // ---- Queue ---------------------------------------------------------------------------------

    override fun setQueue(
        songs: List<Song>,
        startIndex: Int,
    ) {
        // Reuse the local playback path: the AVPlayer produces audio and iOS routes it to AirPlay.
        controller.updateSongs(songs)

        if (startIndex in songs.indices) {
            val song = songs[startIndex]
            controller.playOn(startIndex)
            machine = machine.play(song, positionSeconds = 0.0)
        } else {
            machine = machine.copy(currentSong = songs.firstOrNull())
        }
    }

    // ---- Transport (forwards to the local AVPlayer; drives the cast machine, R8.2) -------------

    override fun play() {
        controller.play()
        machine =
            if (machine.state == CastSessionState.PAUSED) {
                machine.resume()
            } else {
                val song = controller.musicState.value.currentSong
                if (song != null) machine.play(song, machine.position) else machine.resume()
            }
    }

    override fun pause() {
        controller.pause()
        machine = machine.pause()
    }

    override fun stop() {
        // The iOS MusicServiceController exposes a real stop(); use it so the AVPlayer item is
        // torn down and the position cleared (R10.3).
        controller.stop()
        machine = machine.stop()
    }

    override fun next() {
        controller.next()
        val song = controller.musicState.value.currentSong
        machine = if (song != null) machine.play(song, positionSeconds = 0.0) else machine
    }

    override fun previous() {
        controller.previous()
        val song = controller.musicState.value.currentSong
        machine = if (song != null) machine.play(song, positionSeconds = 0.0) else machine
    }

    override fun seekTo(seconds: Double) {
        controller.seekTo(seconds)
        machine = machine.copy(position = seconds)
    }

    override fun setVolume(level: Double) {
        // AirPlay device volume is controlled by the system/route picker, not the app; retain the
        // normalized value on the machine for status parity with the other engines (R10.4).
        machine = machine.setVolume(level)
    }

    // ---- Activation ----------------------------------------------------------------------------

    override suspend fun activate(target: PlaybackTarget?) {
        this.targetState.value = target
        startRouteObservation()
        // Ask the SwiftUI layer to present the system route picker for device + password (R8.3).
        requestRoutePicker()
    }

    override suspend fun deactivate(retainPosition: Boolean) {
        stopRouteObservation()
        routePickerRequestedState.value = false

        if (retainPosition) {
            // Pause in place; the retained position stays observable on status.position (R15.4).
            controller.pause()
            machine = machine.pause()
        } else {
            controller.stop()
            machine = machine.stop()
        }
        targetState.value = null
    }

    // ---- Internals -----------------------------------------------------------------------------

    private fun requestRoutePicker() {
        routePickerRequestedState.value = true
        routePickerPresenter?.invoke()
    }

    private fun startRouteObservation() {
        if (routeChangeObserver != null) return
        routeChangeObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVAudioSessionRouteChangeNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
                usingBlock = { notification -> handleRouteChange(notification) },
            )
    }

    private fun stopRouteObservation() {
        routeChangeObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        routeChangeObserver = null
    }

    /**
     * Detect the AirPlay route becoming unavailable while playing and apply R10.6 disconnect
     * handling (reached from R8.4): pause the local player and drive the machine to `stopped`.
     */
    private fun handleRouteChange(notification: NSNotification?) {
        val userInfo = notification?.userInfo ?: return
        val reason = (userInfo[AVAudioSessionRouteChangeReasonKey] as? NSNumber)?.unsignedIntegerValue ?: return

        if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) {
            val previousRoute =
                userInfo[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription ?: return

            if (hasAirPlayOutput(previousRoute) && machine.state == CastSessionState.PLAYING) {
                controller.pause()
                machine = machine.onDisconnect()
            }
        }
    }

    private fun hasAirPlayOutput(routeDescription: AVAudioSessionRouteDescription): Boolean =
        routeDescription.outputs.any { output ->
            (output as? AVAudioSessionPortDescription)?.portType == AVAudioSessionPortAirPlay
        }
}

/**
 * Pure projection of the cast [CastSessionMachine] plus the local player's [MusicState]/position
 * into the engine-agnostic [EngineStatus]. The cast machine supplies state, target, volume, and
 * error (R10); the local [MusicServiceController] is the real audio source, so the current song and
 * position are taken from it. Extracted for straightforward reasoning/testing.
 */
internal fun airPlayEngineStatus(
    machine: CastSessionMachine,
    musicState: MusicState,
    position: Double,
    target: PlaybackTarget?,
): EngineStatus {
    val base = machine.toEngineStatus(target)
    return base.copy(
        currentSong = musicState.currentSong ?: base.currentSong,
        position = position,
    )
}
