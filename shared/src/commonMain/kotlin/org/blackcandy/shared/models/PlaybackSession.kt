package org.blackcandy.shared.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A transport operation the app issues to a server-driven [PlaybackSession] while acting
 * as a Remote_Control (spec R14.2). The wire value is the lowercase name.
 */
@Serializable
enum class PlaybackOp {
    @SerialName("play")
    PLAY,

    @SerialName("resume")
    RESUME,

    @SerialName("pause")
    PAUSE,

    @SerialName("stop")
    STOP,

    ;

    /** The value sent to the Server for this operation. */
    val value: String get() = name.lowercase()
}

/**
 * The app's view of a Server-side Playback_Session under `server_playback` routing (spec R14).
 *
 * [state] is one of `stopped`, `playing`, or `paused`. The app observes this session by polling
 * and adopts the returned session as truth after each control operation.
 */
@Serializable
data class PlaybackSession(
    val state: String,
    val currentSongId: Long?,
    val position: Double,
    val activeDeviceIds: List<String>,
    val volume: Double = 1.0,
)
