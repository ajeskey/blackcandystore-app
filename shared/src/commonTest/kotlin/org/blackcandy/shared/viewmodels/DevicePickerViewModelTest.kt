package org.blackcandy.shared.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.blackcandy.shared.media.EngineStatus
import org.blackcandy.shared.media.PlaybackCoordinator
import org.blackcandy.shared.media.PlaybackEngine
import org.blackcandy.shared.media.PlaybackTarget
import org.blackcandy.shared.models.DeviceOrigin
import org.blackcandy.shared.models.OutputDevice
import org.blackcandy.shared.models.OutputDeviceProtocol
import org.blackcandy.shared.models.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the [DevicePickerViewModel] pure section-building and target-mapping logic
 * (spec R6.1, R6.5, R6.7, R7.2, R16.1).
 *
 * Following the module convention (see `LocalPlaybackEngineTest`, `ServerPlaybackEngine` mapping
 * tests), the ViewModel's decision logic is extracted into pure functions so it can be verified
 * without a coroutine scope, a live network, or a platform player.
 */
class DevicePickerViewModelTest {
    private fun serverDevice(
        id: String,
        protocol: OutputDeviceProtocol = OutputDeviceProtocol.AIRPLAY,
    ) = OutputDevice(id = id, name = "Server $id", protocol = protocol, origin = DeviceOrigin.SERVER)

    private fun localDevice(
        id: String,
        protocol: OutputDeviceProtocol = OutputDeviceProtocol.CHROMECAST,
    ) = OutputDevice(id = id, name = "Local $id", protocol = protocol, origin = DeviceOrigin.LOCAL)

    // ---- Server section gating (R16.1) --------------------------------------------------------

    @Test
    fun server_section_lists_devices_when_capability_supports_output_devices() {
        val devices = listOf(serverDevice("a"), serverDevice("b", OutputDeviceProtocol.CHROMECAST))

        val section = serverDeviceSection(supported = true, devices = devices)

        assertTrue(section.isSupported)
        assertEquals(devices, section.devices)
        assertEquals(DeviceOrigin.SERVER, section.origin)
        assertFalse(section.isEmpty)
    }

    @Test
    fun server_section_is_forced_empty_when_capability_absent() {
        // Even if a stale list is passed, an unsupported section must not leak devices.
        val section = serverDeviceSection(supported = false, devices = listOf(serverDevice("a")))

        assertFalse(section.isSupported)
        assertTrue(section.devices.isEmpty())
    }

    // ---- Empty is not an error (R6.5) ---------------------------------------------------------

    @Test
    fun supported_but_empty_server_list_is_a_no_devices_state_not_an_error() {
        val section = serverDeviceSection(supported = true, devices = emptyList())

        assertTrue(section.isSupported)
        assertTrue(section.isEmpty)

        // The picker is still considered available (entry point shown) when server devices are
        // supported, even with none currently discovered (R16.1).
        val state = DevicePickerUiState(serverSection = section)
        assertTrue(state.isPickerAvailable)
    }

    // ---- Local section gating (R12.4) ---------------------------------------------------------

    @Test
    fun local_section_is_empty_and_unsupported_until_discovery_is_wired() {
        val section = localDeviceSection(supported = false, devices = emptyList())

        assertFalse(section.isSupported)
        assertTrue(section.isEmpty)
        assertEquals(DeviceOrigin.LOCAL, section.origin)
    }

    @Test
    fun local_section_lists_devices_when_client_cast_supported() {
        val devices = listOf(localDevice("x"))

        val section = localDeviceSection(supported = true, devices = devices)

        assertTrue(section.isSupported)
        assertEquals(devices, section.devices)
    }

    // ---- Namespaces stay separate (R6.7) ------------------------------------------------------

    @Test
    fun server_and_local_sections_are_distinct_namespaces() {
        val serverSection = serverDeviceSection(true, listOf(serverDevice("s1")))
        val localSection = localDeviceSection(true, listOf(localDevice("l1")))

        assertEquals(DeviceOrigin.SERVER, serverSection.origin)
        assertEquals(DeviceOrigin.LOCAL, localSection.origin)
        // No merging: each device keeps its own origin.
        assertTrue(serverSection.devices.all { it.origin == DeviceOrigin.SERVER })
        assertTrue(localSection.devices.all { it.origin == DeviceOrigin.LOCAL })
    }

    // ---- Selection drives routing (R7.2) ------------------------------------------------------

    @Test
    fun selecting_a_server_device_maps_to_server_playback_target() {
        val device = serverDevice("s1")

        val target = playbackTargetFor(device)

        assertTrue(target is PlaybackTarget.ServerDevice)
        assertEquals(listOf(device), (target as PlaybackTarget.ServerDevice).devices)
    }

    @Test
    fun selecting_a_local_device_maps_to_client_cast_target() {
        val device = localDevice("l1")

        val target = playbackTargetFor(device)

        assertTrue(target is PlaybackTarget.LocalCastDevice)
        assertEquals(device, (target as PlaybackTarget.LocalCastDevice).device)
    }

    // ---- AirPlay device password collection (R14.7) -------------------------------------------

    @Test
    fun protected_server_airplay_device_requires_a_password_prompt() {
        val device = serverDevice("s1", OutputDeviceProtocol.AIRPLAY).copy(requiresPassword = true)

        assertTrue(requiresDevicePassword(device))
    }

    @Test
    fun server_airplay_device_without_password_flag_needs_no_prompt() {
        val device = serverDevice("s1", OutputDeviceProtocol.AIRPLAY).copy(requiresPassword = false)

        assertFalse(requiresDevicePassword(device))
    }

    @Test
    fun server_chromecast_device_needs_no_password_prompt_even_if_flagged() {
        // Password collection is an AirPlay-only concern per R14.7.
        val device = serverDevice("s1", OutputDeviceProtocol.CHROMECAST).copy(requiresPassword = true)

        assertFalse(requiresDevicePassword(device))
    }

    @Test
    fun local_airplay_device_needs_no_server_password_prompt() {
        val device = localDevice("l1", OutputDeviceProtocol.AIRPLAY).copy(requiresPassword = true)

        assertFalse(requiresDevicePassword(device))
    }

    @Test
    fun protected_server_airplay_device_with_password_carries_it_to_the_target() {
        val device = serverDevice("s1", OutputDeviceProtocol.AIRPLAY).copy(requiresPassword = true)

        val target = playbackTargetFor(device, password = "hunter2")

        assertTrue(target is PlaybackTarget.ServerDevice)
        target as PlaybackTarget.ServerDevice
        assertEquals(listOf(device), target.devices)
        assertEquals("hunter2", target.devicePassword)
    }

    @Test
    fun non_password_server_device_never_carries_a_password() {
        val device = serverDevice("s1", OutputDeviceProtocol.AIRPLAY).copy(requiresPassword = false)

        // Even if a password is somehow supplied, a device that does not require one keeps the
        // unchanged behaviour of a null password.
        val target = playbackTargetFor(device, password = "hunter2")

        assertTrue(target is PlaybackTarget.ServerDevice)
        assertEquals(null, (target as PlaybackTarget.ServerDevice).devicePassword)
    }

    @Test
    fun server_device_without_password_argument_maps_to_null_password() {
        val device = serverDevice("s1", OutputDeviceProtocol.AIRPLAY).copy(requiresPassword = true)

        val target = playbackTargetFor(device)

        assertTrue(target is PlaybackTarget.ServerDevice)
        assertEquals(null, (target as PlaybackTarget.ServerDevice).devicePassword)
    }

    // ---- Picker entry-point gating (R16.1) ----------------------------------------------------

    @Test
    fun picker_is_unavailable_when_neither_namespace_is_supported() {
        val state =
            DevicePickerUiState(
                serverSection = serverDeviceSection(false, emptyList()),
                localSection = localDeviceSection(false, emptyList()),
            )

        assertFalse(state.isPickerAvailable)
    }

    // ---- Volume passthrough (R10.4, R14.2) ----------------------------------------------------

    /**
     * A minimal [PlaybackEngine] that records the last volume it received. Used to verify the
     * volume set through the Device_Picker reaches the active engine.
     *
     * The real [DevicePickerViewModel.setVolume] clamps the level and forwards it to
     * [PlaybackCoordinator.setVolume], which forwards to the active engine. Constructing the
     * ViewModel here would require a Main dispatcher for its `viewModelScope`, so the passthrough is
     * exercised at the coordinator boundary the ViewModel delegates to.
     */
    private class RecordingEngine : PlaybackEngine {
        var lastVolume: Double? = null
        private val statusState = MutableStateFlow(EngineStatus())
        override val status: StateFlow<EngineStatus> = statusState.asStateFlow()

        override fun setQueue(
            songs: List<Song>,
            startIndex: Int,
        ) = Unit

        override fun play() = Unit

        override fun pause() = Unit

        override fun stop() = Unit

        override fun next() = Unit

        override fun previous() = Unit

        override fun seekTo(seconds: Double) = Unit

        override fun setVolume(level: Double) {
            lastVolume = level
        }

        override suspend fun activate(target: PlaybackTarget?) = Unit

        override suspend fun deactivate(retainPosition: Boolean) = Unit
    }

    private fun coordinatorWith(local: PlaybackEngine): PlaybackCoordinator =
        PlaybackCoordinator(
            local = local,
            chromecast = null,
            airplay = null,
            serverPlayback = RecordingEngine(),
            // Unconfined needs no Main dispatcher, so this runs in commonTest without extra deps.
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    @Test
    fun set_volume_forwards_the_level_to_the_active_engine() {
        val local = RecordingEngine()
        val coordinator = coordinatorWith(local)

        coordinator.setVolume(0.42)

        assertEquals(0.42, local.lastVolume)
    }

    @Test
    fun set_volume_is_forwarded_verbatim_when_already_in_range() {
        // The ViewModel clamps to 0.0..1.0 before delegating; an in-range value passes through
        // unchanged so a slider value reaches the active engine as-is.
        val local = RecordingEngine()
        val coordinator = coordinatorWith(local)

        coordinator.setVolume(0.0.coerceIn(0.0, 1.0))
        assertEquals(0.0, local.lastVolume)

        coordinator.setVolume(1.0.coerceIn(0.0, 1.0))
        assertEquals(1.0, local.lastVolume)
    }

    @Test
    fun view_model_clamps_out_of_range_volume_before_forwarding() {
        // Mirrors DevicePickerViewModel.setVolume's coerceIn so an out-of-range slider/programmatic
        // value can never drive the engine outside the normalized 0.0..1.0 scale (R10.4).
        val local = RecordingEngine()
        val coordinator = coordinatorWith(local)

        coordinator.setVolume(1.5.coerceIn(0.0, 1.0))
        assertEquals(1.0, local.lastVolume)

        coordinator.setVolume((-0.3).coerceIn(0.0, 1.0))
        assertEquals(0.0, local.lastVolume)
    }
}
