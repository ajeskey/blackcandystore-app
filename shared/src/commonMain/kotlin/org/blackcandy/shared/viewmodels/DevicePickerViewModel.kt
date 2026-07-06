package org.blackcandy.shared.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.blackcandy.shared.data.LocalDeviceProvider
import org.blackcandy.shared.data.ServerDeviceRepository
import org.blackcandy.shared.data.SystemInfoRepository
import org.blackcandy.shared.media.PlaybackCoordinator
import org.blackcandy.shared.media.PlaybackError
import org.blackcandy.shared.media.PlaybackRouting
import org.blackcandy.shared.media.PlaybackState
import org.blackcandy.shared.media.PlaybackTarget
import org.blackcandy.shared.models.DeviceOrigin
import org.blackcandy.shared.models.OutputDevice
import org.blackcandy.shared.models.OutputDeviceProtocol
import org.blackcandy.shared.models.ServerCapabilities
import org.blackcandy.shared.utils.TaskResult

/**
 * One section of the Device_Picker — either the Server_Output_Device set or the
 * Local_Output_Device set. The two are kept as separate values so the UI can render them as
 * visually and functionally distinct namespaces (spec R6.1, R6.7).
 *
 * @property origin which namespace this section represents (server vs app-discovered local).
 * @property isSupported whether this routing is available at all — the Server advertises output
 *   devices for the server section (R16.1), or the platform can client-cast for the local section
 *   (R12.4). When false the UI omits the section entirely rather than showing a dead option.
 * @property devices the devices currently discovered in this namespace; empty is a valid,
 *   non-error "no devices found" state (R6.5).
 */
data class DeviceSection(
    val origin: DeviceOrigin,
    val isSupported: Boolean = false,
    val devices: List<OutputDevice> = emptyList(),
) {
    /** True when this section is supported but currently lists no devices (R6.5). */
    val isEmpty: Boolean get() = devices.isEmpty()
}

/**
 * The Device_Picker UI state (spec R6, R16.1). It carries the two device sections separately and
 * mirrors the coordinator's active routing/target so the picker can indicate where audio is
 * currently going.
 */
data class DevicePickerUiState(
    val serverSection: DeviceSection = DeviceSection(origin = DeviceOrigin.SERVER),
    val localSection: DeviceSection = DeviceSection(origin = DeviceOrigin.LOCAL),
    val activeRouting: PlaybackRouting = PlaybackRouting.LOCAL,
    val activeTarget: PlaybackTarget? = null,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val volume: Double = 1.0,
    val isLoading: Boolean = false,
    val error: PlaybackError? = null,
) {
    /**
     * Whether the Device_Picker entry point should be shown at all: only where the Server reports
     * output devices or the platform supports client casting (R16.1).
     */
    val isPickerAvailable: Boolean get() = serverSection.isSupported || localSection.isSupported
}

/**
 * Combines the two Output_Device namespaces into a single Device_Picker UI model and turns a
 * device selection into a [PlaybackCoordinator] routing switch (spec R6.1, R6.5, R6.7, R7.2, R16.1).
 *
 * ## Two separate namespaces (R6.1, R6.7)
 * The Server_Output_Device set (from [ServerDeviceRepository], gated by
 * [ServerCapabilities.outputDevices], R16.1) and the Local_Output_Device set (from
 * [LocalDeviceProvider]) are surfaced as two distinct [DeviceSection]s and never merged. Each
 * device keeps its own `origin`/`protocol` so the user understands which routing a choice implies.
 *
 * ## Empty / unavailable is not an error (R6.5)
 * [ServerDeviceRepository.getOutputDevices] already collapses an empty list, a Server that reports
 * discovery unavailable, and a failed request into an empty list. This ViewModel therefore renders
 * an empty-but-supported server section as a "no devices found" state and never raises an error
 * from device loading.
 *
 * ## Local devices seam
 * Real Local_Output_Device discovery is platform-specific and bound per-platform in `platformModule`:
 * [org.blackcandy.shared.media.AndroidLocalDeviceDiscovery] (Chromecast via the Cast SDK MediaRouter)
 * on Android and [org.blackcandy.shared.media.IosLocalDeviceDiscovery] (AirPlay via the iOS system
 * route picker; Chromecast pending a GoogleCast cinterop) on iOS. This ViewModel depends only on the
 * [LocalDeviceProvider] interface, so it resolves whichever concrete provider the active platform
 * contributes without change. [org.blackcandy.shared.data.EmptyLocalDeviceProvider] remains as a
 * no-support fallback for tests.
 *
 * ## Selection drives routing (R7.2)
 * Selecting a server-origin device enters `server_playback`; selecting a local-origin device enters
 * `client_cast`; the explicit local-playback option returns to local device playback. All three go
 * through [PlaybackCoordinator.selectTarget], which owns the routing-exclusivity switch (R15).
 */
class DevicePickerViewModel(
    private val serverDeviceRepository: ServerDeviceRepository,
    private val systemInfoRepository: SystemInfoRepository,
    private val coordinator: PlaybackCoordinator,
    private val localDeviceProvider: LocalDeviceProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DevicePickerUiState())

    /**
     * The Device_Picker state. The two device sections come from [_uiState]; the active
     * routing/target/error are layered in live from the coordinator so the picker reflects the
     * current routing selection reactively (R16.1).
     */
    val uiState =
        combine(
            _uiState,
            coordinator.status,
        ) { state, status ->
            state.copy(
                activeRouting = status.routing,
                activeTarget = status.target,
                playbackState = status.state,
                volume = status.volume,
                error = status.error,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DevicePickerUiState(),
        )

    init {
        refreshDevices()
    }

    /**
     * (Re)load both namespaces. The server section is gated by [ServerCapabilities.outputDevices]
     * (R16.1); the local section is gated by [LocalDeviceProvider.isClientCastSupported] (R12.4).
     * A failed or empty server list is a valid empty section, not an error (R6.5).
     */
    fun refreshDevices() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val capabilities = resolveCapabilities()

            val serverSupported = capabilities.outputDevices
            val serverDevices = if (serverSupported) serverDeviceRepository.getOutputDevices() else emptyList()

            val localSupported = localDeviceProvider.isClientCastSupported
            val localDevices = if (localSupported) localDeviceProvider.getLocalDevices() else emptyList()

            _uiState.update {
                it.copy(
                    serverSection = serverDeviceSection(serverSupported, serverDevices),
                    localSection = localDeviceSection(localSupported, localDevices),
                    isLoading = false,
                )
            }
        }
    }

    /**
     * Select a discovered device. Server-origin devices route to `server_playback`; local-origin
     * devices route to `client_cast` (R7.2). The coordinator performs the exclusive engine switch.
     *
     * A protected AirPlay Server_Output_Device must instead be selected through
     * [selectDeviceWithPassword] so the collected password reaches the Server (R14.7); use
     * [requiresDevicePassword] to decide which path a device needs.
     */
    fun selectDevice(device: OutputDevice) {
        selectDeviceWithPassword(device, password = null)
    }

    /**
     * Select a device, carrying a device password collected from the user for a protected AirPlay
     * Server_Output_Device (R14.7). The password is only attached to a server-origin device that
     * actually requires one (see [requiresDevicePassword]); for every other device the behaviour is
     * identical to [selectDevice] and the password is ignored.
     *
     * When the Server rejects a missing or incorrect password it surfaces a
     * [PlaybackError.Authentication] on the coordinator status, which flows into
     * [DevicePickerUiState.error]; the UI re-prompts and calls this again with a new password.
     */
    fun selectDeviceWithPassword(
        device: OutputDevice,
        password: String?,
    ) {
        viewModelScope.launch {
            coordinator.selectTarget(playbackTargetFor(device, password))
        }
    }

    /** Return to the default local App_Player playback on the device (R7.7). */
    fun selectLocalPlayback() {
        viewModelScope.launch {
            coordinator.selectTarget(PlaybackTarget.LocalDevice)
        }
    }

    /**
     * Set the output volume for whichever routing is active on a normalized 0.0–1.0 scale
     * (spec R10.4, R14.2). The coordinator forwards the change to the active engine — the Cast
     * receiver under `client_cast`, or the Server Playback_Session under `server_playback` — so the
     * one volume control works across every routing. The level is clamped so the UI can pass a
     * slider value straight through without pre-validating it; the resulting volume is reflected
     * back on [DevicePickerUiState.volume] from the coordinator status.
     */
    fun setVolume(level: Double) {
        coordinator.setVolume(level.coerceIn(0.0, 1.0))
    }

    /**
     * Resolve the Server_Capabilities used to gate the server section (R16.1). A failure to reach
     * the Server degrades to [ServerCapabilities.NONE] so the picker hides server devices rather
     * than erroring (R4.5).
     */
    private suspend fun resolveCapabilities(): ServerCapabilities =
        when (val result = systemInfoRepository.getSystemInfo()) {
            is TaskResult.Success -> result.data.resolvedCapabilities
            is TaskResult.Failure -> ServerCapabilities.NONE
        }
}

// ---- Pure builders (network/coroutine-independent, unit-testable) ------------------------------

/**
 * Build the Server_Output_Device section (R6.1, R6.5, R6.7, R16.1). When the server section is
 * unsupported the device list is forced empty so a stale list can never leak past a capability
 * that turned off.
 */
internal fun serverDeviceSection(
    supported: Boolean,
    devices: List<OutputDevice>,
): DeviceSection =
    DeviceSection(
        origin = DeviceOrigin.SERVER,
        isSupported = supported,
        devices = if (supported) devices else emptyList(),
    )

/**
 * Build the Local_Output_Device section (R6.1, R6.7, R12.4). When client casting is unsupported on
 * this platform the section is empty so no unusable local option is presented (R12.4).
 */
internal fun localDeviceSection(
    supported: Boolean,
    devices: List<OutputDevice>,
): DeviceSection =
    DeviceSection(
        origin = DeviceOrigin.LOCAL,
        isSupported = supported,
        devices = if (supported) devices else emptyList(),
    )

/**
 * Map a selected [OutputDevice] to the [PlaybackTarget] its namespace implies (R7.2):
 * a server-origin device becomes a [PlaybackTarget.ServerDevice] (`server_playback`), and a
 * local-origin device becomes a [PlaybackTarget.LocalCastDevice] (`client_cast`).
 *
 * [password] is the device password the user entered for a protected AirPlay
 * Server_Output_Device (R14.7). It is only attached when [requiresDevicePassword] holds for the
 * device, so a stray password on a device that does not need one never reaches the Server and the
 * mapping for non-protected devices is unchanged (it stays `null`).
 */
internal fun playbackTargetFor(
    device: OutputDevice,
    password: String? = null,
): PlaybackTarget =
    when (device.origin) {
        DeviceOrigin.SERVER ->
            PlaybackTarget.ServerDevice(
                devices = listOf(device),
                devicePassword = password?.takeIf { requiresDevicePassword(device) },
            )
        DeviceOrigin.LOCAL -> PlaybackTarget.LocalCastDevice(device)
    }

/**
 * Whether selecting [device] must first collect a device password from the user (R14.7). This is
 * true only for a Server_Output_Device that speaks AirPlay and reports `requiresPassword`; every
 * other device is selected directly. Shared by both platforms so the prompt decision stays
 * identical.
 */
fun requiresDevicePassword(device: OutputDevice): Boolean =
    device.origin == DeviceOrigin.SERVER &&
        device.protocol == OutputDeviceProtocol.AIRPLAY &&
        device.requiresPassword
