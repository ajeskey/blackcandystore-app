package org.blackcandy.shared.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The cast protocol an [OutputDevice] speaks. Each device is classified as exactly
 * one of these (spec R6.6).
 */
@Serializable
enum class OutputDeviceProtocol {
    @SerialName("airplay")
    AIRPLAY,

    @SerialName("chromecast")
    CHROMECAST,
}

/**
 * Which namespace an [OutputDevice] belongs to (spec R6.7):
 * - [LOCAL]: discovered by the app itself for `client_cast`.
 * - [SERVER]: reported by the connected Server for `server_playback`.
 *
 * The two sets are distinct namespaces and are never merged.
 */
@Serializable
enum class DeviceOrigin {
    @SerialName("local")
    LOCAL,

    @SerialName("server")
    SERVER,
}

/**
 * A network audio endpoint (an AirPlay or Chromecast device) that can be a playback target.
 *
 * Devices returned by the Server ([BlackCandyService.getOutputDevices]) carry no explicit
 * `origin` field, so [origin] defaults to [DeviceOrigin.SERVER]; app-discovered local devices
 * are constructed with [DeviceOrigin.LOCAL] (spec R6.1, R6.2, R6.7).
 */
@Serializable
data class OutputDevice(
    val id: String,
    val name: String,
    val protocol: OutputDeviceProtocol,
    val requiresPassword: Boolean = false,
    val origin: DeviceOrigin = DeviceOrigin.SERVER,
)
