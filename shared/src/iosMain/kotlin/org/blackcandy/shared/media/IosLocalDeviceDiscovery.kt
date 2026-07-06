package org.blackcandy.shared.media

import org.blackcandy.shared.data.LocalDeviceProvider
import org.blackcandy.shared.models.OutputDevice
import platform.AVFoundation.AVRouteDetector

/**
 * iOS discovery of Local_Output_Devices for `client_cast` (spec R6.1, R6.3, R6.4, R6.6, R12.1,
 * R12.2, R12.4).
 *
 * iOS exposes **two** protocols:
 * - **AirPlay** via first-party `AVFoundation` — always available on iOS (R12.1), so this provider
 *   is always client-cast-capable. `AVRouteDetector` reports whether additional (AirPlay) routes are
 *   currently reachable.
 * - **Chromecast** via the GoogleCast SDK — not yet exposed to Kotlin/Native (no GoogleCast cinterop
 *   configured for `iosMain`), so the Chromecast portion contributes no devices for now (see the
 *   TODO below).
 *
 * This is one of the two concrete [LocalDeviceProvider] implementations that replaced the former
 * `expect`/`actual class LocalDeviceDiscovery`: Android needs an application `Context` to reach the
 * Cast SDK while iOS needs none, so each platform binds its own concrete provider in `platformModule`
 * ([org.blackcandy.shared.viewmodels.DevicePickerViewModel] depends only on the [LocalDeviceProvider]
 * interface, so it is unaffected).
 *
 * Anything this class surfaces is classified through the shared [LocalDeviceClassifier], so every
 * device is tagged [org.blackcandy.shared.models.DeviceOrigin.LOCAL] and carries its
 * `airplay`/`chromecast` protocol (R6.6); a device that drops out is absent from the next snapshot
 * (R6.4).
 *
 * ## AirPlay enumeration note
 * Apple does not expose an enumerable list of named AirPlay devices to third-party apps —
 * `AVRouteDetector` only reports *whether* alternative routes exist ([airPlayRouteAvailable]), and
 * the actual device list + password entry are presented by the system route picker
 * (`AVRoutePickerView`). That picker-based selection is added in the iOS UI follow-up
 * (`AirPlayEngine` / `AVRoutePickerView`). Consequently this snapshot does not fabricate named
 * AirPlay rows; AirPlay is offered to the user through the system picker rather than as
 * individually-listed [OutputDevice]s here. Reporting [isClientCastSupported] `true` is what makes
 * the picker's local section appear so that route-picker entry can live in it.
 *
 * ## TODO(GoogleCast cinterop): wire the GoogleCast SDK
 * Once a `GoogleCast` cinterop is configured for `iosMain`, register a `GCKDiscoveryManager`
 * listener, snapshot its `GCKDevice` list as [DiscoveredLocalDevice]s (protocol =
 * [org.blackcandy.shared.models.OutputDeviceProtocol.CHROMECAST]), and merge them with any AirPlay
 * entries through `LocalDeviceClassifier.classify(...)`.
 */
class IosLocalDeviceDiscovery : LocalDeviceProvider {
    private val routeDetector: AVRouteDetector =
        AVRouteDetector().apply {
            // Enable route detection so `multipleRoutesDetected` reflects live AirPlay availability.
            setRouteDetectionEnabled(true)
        }

    /**
     * iOS can always perform AirPlay `client_cast` (R12.1), so the local section is offered on iOS
     * regardless of the Chromecast SDK's presence (R12.2, R12.4).
     */
    override val isClientCastSupported: Boolean = true

    /** Whether iOS currently detects reachable AirPlay routes beyond the built-in output. */
    val airPlayRouteAvailable: Boolean
        get() = routeDetector.multipleRoutesDetected

    override suspend fun getLocalDevices(): List<OutputDevice> {
        val discovered = mutableListOf<DiscoveredLocalDevice>()

        // Chromecast entries: TODO(GoogleCast cinterop) — populate from GCKDiscoveryManager once the
        // GoogleCast SDK is exposed to Kotlin/Native. Kept as the explicit seam so no fabricated
        // devices leak before the SDK exists.

        // AirPlay entries are selected through the system route picker (AVRoutePickerView, iOS UI
        // follow-up); Apple exposes no enumerable named list here, so nothing is fabricated. Any
        // future named entries would be classified as OutputDeviceProtocol.AIRPLAY via the classifier.

        return LocalDeviceClassifier.classify(discovered)
    }
}
