package org.blackcandy.shared.data

import org.blackcandy.shared.models.OutputDevice

/**
 * The `client_cast` (Local_Output_Device) namespace source for the Device_Picker (spec R6.1, R6.3).
 *
 * ## Platform-specific implementations
 * Local device discovery is genuinely platform-specific — Chromecast via the Google Cast SDK, plus
 * AirPlay via the iOS system route picker (R6.3, R12.1, R12.2) — so each platform provides its own
 * concrete implementation of this interface and binds it in `platformModule`:
 * [org.blackcandy.shared.media.AndroidLocalDeviceDiscovery] (Cast SDK MediaRouter) on Android and
 * [org.blackcandy.shared.media.IosLocalDeviceDiscovery] (`AVRouteDetector`/route picker) on iOS.
 *
 * [org.blackcandy.shared.viewmodels.DevicePickerViewModel] depends only on this interface and never
 * on a concrete discovery type, so it resolves whichever provider the active platform contributes
 * with no change. [EmptyLocalDeviceProvider] remains as a no-support fallback used by tests.
 */
interface LocalDeviceProvider {
    /**
     * Whether this platform+device can perform any `client_cast` protocol at all (R12.4, R16.1).
     * When false the Device_Picker omits the local section entirely rather than presenting an
     * option that cannot work. Defaults to false via [EmptyLocalDeviceProvider] until discovery
     * is wired up.
     */
    val isClientCastSupported: Boolean

    /**
     * The currently-discovered Local_Output_Devices, each tagged [org.blackcandy.shared.models.DeviceOrigin.LOCAL]
     * and classified as `airplay` or `chromecast` (R6.3, R6.6). Devices that have dropped out are
     * simply absent from the returned list (R6.4).
     */
    suspend fun getLocalDevices(): List<OutputDevice>
}

/**
 * The default [LocalDeviceProvider] used until `LocalDeviceDiscovery` (task 15.3) is implemented.
 *
 * It reports no client-cast support and an empty device set, so the Device_Picker's local section
 * renders as an empty, unsupported section — never a fabricated device and never an error state
 * (R6.5). This is the clear extension point: replace this binding with the real discovery provider.
 */
object EmptyLocalDeviceProvider : LocalDeviceProvider {
    override val isClientCastSupported: Boolean = false

    override suspend fun getLocalDevices(): List<OutputDevice> = emptyList()
}
