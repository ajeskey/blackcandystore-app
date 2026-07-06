package org.blackcandy.shared.media

/**
 * How audio for the current playback activity is produced and routed (spec R7).
 *
 * - [LOCAL]: the App_Player plays audio on the device itself (the default, R7.1/R7.7).
 * - [CLIENT_CAST]: the app or a Cast receiver is the audio source, targeting a
 *   Local_Output_Device (AirPlay via iOS system routing, or Chromecast via the Cast SDK) (R7.5).
 * - [SERVER_PLAYBACK]: the Server is the audio source and the app acts only as a
 *   Remote_Control, targeting one or more Server_Output_Devices (R7.6).
 *
 * Exactly one routing is active for a single playback activity at a time (R7.4).
 */
enum class PlaybackRouting {
    LOCAL,
    CLIENT_CAST,
    SERVER_PLAYBACK,
}
