package org.blackcandy.shared.media

import org.blackcandy.shared.models.OutputDevice

/**
 * Where playback is directed. A `null` target (or [LocalDevice]) means the default
 * local App_Player playback on the device; the other variants select an external
 * routing (spec R7).
 *
 * The Local_Output_Device set ([LocalCastDevice]) and the Server_Output_Device set
 * ([ServerDevice]) are distinct namespaces and never merged (R6.7).
 */
sealed interface PlaybackTarget {
    /** Default local App_Player playback on the device (R7.7). */
    data object LocalDevice : PlaybackTarget

    /**
     * Client-cast to a single app-discovered Local_Output_Device (AirPlay or Chromecast).
     * Selecting this enters [PlaybackRouting.CLIENT_CAST] (R7.2).
     */
    data class LocalCastDevice(val device: OutputDevice) : PlaybackTarget

    /**
     * Server-driven playback to one or more Server_Output_Devices.
     * Selecting this enters [PlaybackRouting.SERVER_PLAYBACK] (R7.2, R14.1).
     *
     * [devicePassword] carries the password the app collected for a protected AirPlay
     * Server_Output_Device so the [ServerPlaybackEngine] can pass it to the Server when
     * creating the Playback_Session (R14.7). It is null when no device requires a password.
     */
    data class ServerDevice(
        val devices: List<OutputDevice>,
        val devicePassword: String? = null,
    ) : PlaybackTarget
}
