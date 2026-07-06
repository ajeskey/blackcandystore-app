package org.blackcandy.shared.media

import org.blackcandy.shared.models.OutputDeviceProtocol
import org.blackcandy.shared.models.ServerCapabilities

/**
 * The app platform the pure gating logic decides for (spec R12). Kept as a tiny pure enum so the
 * platform-support rules can be exercised in `commonMain` without any `expect`/`actual` seam.
 */
enum class AppPlatform {
    IOS,
    ANDROID,
}

/**
 * Pure Server-capability and platform gating for the Device_Picker (spec R4.2, R4.6, R12.1, R12.2).
 *
 * This centralises the "is this feature/protocol offered?" decision that
 * [org.blackcandy.shared.viewmodels.DevicePickerViewModel] applies when it builds its sections:
 *
 * - **Server-dependent features** (output-device discovery, server-driven playback) are offered
 *   **iff** the resolved [ServerCapabilities] reports them (R4.2). This is exactly the gate the
 *   ViewModel uses for `serverDeviceSection(supported = capabilities.outputDevices, …)`.
 * - **Client-cast protocols** are gated purely by platform + device capability, **independent of**
 *   [ServerCapabilities] (R4.6), since client casting needs no Server support beyond a
 *   Cast_Stream_Url:
 *     - **AirPlay** `client_cast` is offered **iff** the platform is iOS (R12.1); Android has no
 *       supported native AirPlay sender.
 *     - **Chromecast** `client_cast` is offered on **both** platforms, gated by whether the
 *       platform+device build can actually run the Cast SDK ([castSupported]) (R12.2, R12.4).
 *
 * All functions are total and side-effect free so they can be property-tested (Property 5).
 */
object FeatureGating {
    /**
     * Whether the Server_Output_Device section is offered. Offered iff the resolved capabilities
     * report output-device discovery (R4.2). Mirrors
     * `serverDeviceSection(supported = capabilities.outputDevices, …)`.
     */
    fun isServerOutputDevicesOffered(capabilities: ServerCapabilities): Boolean = capabilities.outputDevices

    /**
     * Whether server-driven playback is offered. Offered iff the resolved capabilities report it
     * (R4.2).
     */
    fun isServerPlaybackOffered(capabilities: ServerCapabilities): Boolean = capabilities.serverPlayback

    /**
     * Whether a given `client_cast` [protocol] is offered on [platform] (R12.1, R12.2, R12.4).
     *
     * - AirPlay: iOS only (R12.1).
     * - Chromecast: either platform, when the platform+device can run the Cast SDK ([castSupported])
     *   (R12.2). [castSupported] corresponds to
     *   [org.blackcandy.shared.data.LocalDeviceProvider.isClientCastSupported].
     *
     * Deliberately takes no [ServerCapabilities]: client casting is independent of Server support
     * (R4.6).
     */
    fun isClientCastProtocolOffered(
        protocol: OutputDeviceProtocol,
        platform: AppPlatform,
        castSupported: Boolean,
    ): Boolean =
        when (protocol) {
            OutputDeviceProtocol.AIRPLAY -> platform == AppPlatform.IOS
            OutputDeviceProtocol.CHROMECAST -> castSupported
        }
}
