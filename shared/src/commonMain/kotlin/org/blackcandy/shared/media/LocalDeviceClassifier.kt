package org.blackcandy.shared.media

import org.blackcandy.shared.models.DeviceOrigin
import org.blackcandy.shared.models.OutputDevice
import org.blackcandy.shared.models.OutputDeviceProtocol

/**
 * A single device as reported by a platform discovery source (Android Cast `MediaRouter`, iOS
 * `GCKDiscoveryManager`, iOS `AVRouteDetector`/route picker) before it is turned into the shared
 * [OutputDevice] model. Kept protocol-agnostic so both the Chromecast and AirPlay discovery paths
 * feed the same pure classification/dedup logic.
 */
data class DiscoveredLocalDevice(
    val id: String,
    val name: String,
    val protocol: OutputDeviceProtocol,
    val requiresPassword: Boolean = false,
)

/**
 * Pure, platform-independent classification and reconciliation for Local_Output_Devices
 * (spec R6.1, R6.4, R6.6).
 *
 * Platform `LocalDeviceDiscovery` actuals talk to their SDKs (Cast SDK / `AVRouteDetector`) to
 * produce a raw snapshot of currently-reachable devices, then route it through here so the shared
 * rules are testable in `commonMain` without any SDK:
 *
 * - Every returned device is tagged [DeviceOrigin.LOCAL] (R6.1) and keeps its `airplay`/`chromecast`
 *   classification exactly (R6.6).
 * - Devices are de-duplicated by `id`, preserving first-seen order, so the same receiver reported by
 *   more than one discovery source (e.g. a device that is both a Cast target and an AirPlay target)
 *   is presented once.
 * - Because the input is a *snapshot* of currently-reachable devices, a device that has dropped out
 *   is simply absent from that snapshot and therefore absent from the result (R6.4).
 * - Entries with a blank `id` or `name` are dropped rather than surfaced as unusable rows.
 */
object LocalDeviceClassifier {
    /** Build a single Local_Output_Device, always tagged [DeviceOrigin.LOCAL] (R6.1, R6.6). */
    fun localDevice(
        id: String,
        name: String,
        protocol: OutputDeviceProtocol,
        requiresPassword: Boolean = false,
    ): OutputDevice =
        OutputDevice(
            id = id,
            name = name,
            protocol = protocol,
            requiresPassword = requiresPassword,
            origin = DeviceOrigin.LOCAL,
        )

    /**
     * Classify a raw discovery snapshot into the Local_Output_Device set surfaced by the
     * Device_Picker. See the class docs for the invariants this enforces (R6.1, R6.4, R6.6).
     */
    fun classify(discovered: List<DiscoveredLocalDevice>): List<OutputDevice> {
        val seenIds = mutableSetOf<String>()
        val result = mutableListOf<OutputDevice>()

        for (device in discovered) {
            val id = device.id.trim()
            val name = device.name.trim()

            // Skip unusable entries and duplicates (keep first-seen order).
            if (id.isEmpty() || name.isEmpty()) continue
            if (!seenIds.add(id)) continue

            result.add(
                localDevice(
                    id = id,
                    name = name,
                    protocol = device.protocol,
                    requiresPassword = device.requiresPassword,
                ),
            )
        }

        return result
    }
}
