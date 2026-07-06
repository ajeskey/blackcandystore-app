package org.blackcandy.shared.media

/**
 * A playback failure surfaced on [EngineStatus.error] and shown in the player UI.
 *
 * Covers the failure modes the design's Error Handling section describes for cast,
 * AirPlay route, server-playback, and availability paths. When an error forces a
 * stop, the engine sets its state accordingly and reports the matching error here.
 */
sealed interface PlaybackError {
    /** The target Output_Device could not be reached when casting began (R10.5). */
    data object NotReachable : PlaybackError

    /** The target Output_Device disconnected while playing (R8.4, R10.6, R14.6). */
    data object DeviceDisconnected : PlaybackError

    /** Audio did not begin within the 30s watchdog window (R3.4, R10.7). */
    data object Timeout : PlaybackError

    /** The selected Song cannot currently be played or cast (R1.6, R2.5, R9.6, R11.4). */
    data object SongUnavailable : PlaybackError

    /** A device password was missing or rejected (R14.7). */
    data class Authentication(val message: String? = null) : PlaybackError

    /** A Server or SDK error surfaced from the underlying platform/response (R13.5, R14.5). */
    data class Message(val message: String) : PlaybackError
}
