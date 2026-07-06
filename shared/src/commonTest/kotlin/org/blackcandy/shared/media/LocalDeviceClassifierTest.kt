package org.blackcandy.shared.media

import org.blackcandy.shared.models.DeviceOrigin
import org.blackcandy.shared.models.OutputDeviceProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [LocalDeviceClassifier], the pure classification/dedup logic behind the platform
 * `LocalDeviceDiscovery` actuals (spec R6.1, R6.4, R6.6).
 *
 * The platform SDK paths (Cast `MediaRouter` / GoogleCast / `AVRouteDetector`) are integration/
 * manual concerns; only the shared, deterministic mapping rules are exercised here.
 */
class LocalDeviceClassifierTest {
    private fun chromecast(
        id: String,
        name: String = "CC $id",
        requiresPassword: Boolean = false,
    ) = DiscoveredLocalDevice(id, name, OutputDeviceProtocol.CHROMECAST, requiresPassword)

    private fun airplay(
        id: String,
        name: String = "AP $id",
        requiresPassword: Boolean = false,
    ) = DiscoveredLocalDevice(id, name, OutputDeviceProtocol.AIRPLAY, requiresPassword)

    @Test
    fun every_classified_device_is_tagged_local_origin() {
        val devices = LocalDeviceClassifier.classify(listOf(chromecast("a"), airplay("b")))

        assertTrue(devices.all { it.origin == DeviceOrigin.LOCAL })
    }

    @Test
    fun classification_preserves_protocol_and_password_flag() {
        val devices =
            LocalDeviceClassifier.classify(
                listOf(
                    chromecast("cc", name = "Living Room", requiresPassword = false),
                    airplay("ap", name = "Kitchen HomePod", requiresPassword = true),
                ),
            )

        val cc = devices.single { it.id == "cc" }
        val ap = devices.single { it.id == "ap" }

        assertEquals(OutputDeviceProtocol.CHROMECAST, cc.protocol)
        assertEquals("Living Room", cc.name)
        assertEquals(false, cc.requiresPassword)

        assertEquals(OutputDeviceProtocol.AIRPLAY, ap.protocol)
        assertEquals("Kitchen HomePod", ap.name)
        assertEquals(true, ap.requiresPassword)
    }

    @Test
    fun duplicate_ids_are_collapsed_keeping_first_seen_order() {
        val devices =
            LocalDeviceClassifier.classify(
                listOf(
                    chromecast("dup", name = "First"),
                    airplay("dup", name = "Second"),
                    chromecast("other", name = "Other"),
                ),
            )

        assertEquals(listOf("dup", "other"), devices.map { it.id })
        // First occurrence wins, so protocol/name come from the first entry.
        assertEquals("First", devices.first { it.id == "dup" }.name)
        assertEquals(OutputDeviceProtocol.CHROMECAST, devices.first { it.id == "dup" }.protocol)
    }

    @Test
    fun blank_id_or_name_entries_are_dropped() {
        val devices =
            LocalDeviceClassifier.classify(
                listOf(
                    chromecast(" ", name = "No Id"),
                    airplay("noname", name = "  "),
                    chromecast("ok", name = "Valid"),
                ),
            )

        assertEquals(listOf("ok"), devices.map { it.id })
    }

    @Test
    fun ids_and_names_are_trimmed() {
        val devices = LocalDeviceClassifier.classify(listOf(chromecast("  x  ", name = "  Speaker  ")))

        val device = devices.single()
        assertEquals("x", device.id)
        assertEquals("Speaker", device.name)
    }

    @Test
    fun dropped_out_devices_are_absent_from_the_snapshot() {
        // A device present in one snapshot but not the next must not linger (R6.4).
        val before = LocalDeviceClassifier.classify(listOf(chromecast("gone"), chromecast("stays")))
        val after = LocalDeviceClassifier.classify(listOf(chromecast("stays")))

        assertTrue(before.any { it.id == "gone" })
        assertTrue(after.none { it.id == "gone" })
        assertEquals(listOf("stays"), after.map { it.id })
    }

    @Test
    fun empty_snapshot_yields_empty_list() {
        assertTrue(LocalDeviceClassifier.classify(emptyList()).isEmpty())
    }

    @Test
    fun localDevice_helper_tags_local_origin() {
        val device =
            LocalDeviceClassifier.localDevice(
                id = "id",
                name = "Name",
                protocol = OutputDeviceProtocol.CHROMECAST,
            )

        assertEquals(DeviceOrigin.LOCAL, device.origin)
        assertEquals(OutputDeviceProtocol.CHROMECAST, device.protocol)
    }
}
