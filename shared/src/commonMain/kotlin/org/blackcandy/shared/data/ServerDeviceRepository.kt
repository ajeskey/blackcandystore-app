package org.blackcandy.shared.data

import org.blackcandy.shared.api.BlackCandyService
import org.blackcandy.shared.models.OutputDevice

/**
 * Wraps [BlackCandyService.getOutputDevices] for the `server_playback` device namespace.
 *
 * Per spec R6.5 and R18.2, an empty set of Server_Output_Devices, a Server that indicates
 * device discovery is unavailable, or a failed request all resolve to the same empty
 * "no devices" state rather than an error. This lets the Device_Picker present an empty
 * server section instead of surfacing a failure, and keeps capability probing silent for
 * gating on servers without output-device support.
 */
class ServerDeviceRepository(
    private val service: BlackCandyService,
) {
    suspend fun getOutputDevices(): List<OutputDevice> = service.getOutputDevices().orNull() ?: emptyList()
}
