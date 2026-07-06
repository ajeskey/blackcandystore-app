package org.blackcandy.shared.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the new device/session models decode from the assumed Server JSON contract,
 * mirroring the app's shared Json config (snake_case, lenient, ignore unknown keys).
 * Covers spec R6.2, R6.6, R14.
 */
@OptIn(ExperimentalSerializationApi::class)
class OutputDeviceAndSessionTest {
    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            namingStrategy = JsonNamingStrategy.SnakeCase
            useAlternativeNames = false
        }

    @Test
    fun decodesOutputDeviceListWithSnakeCaseAndProtocol() {
        val payload =
            """
            [
              {"id": "a1", "name": "Living Room", "protocol": "airplay", "requires_password": true},
              {"id": "c1", "name": "Kitchen", "protocol": "chromecast"}
            ]
            """.trimIndent()

        val devices = json.decodeFromString<List<OutputDevice>>(payload)

        assertEquals(2, devices.size)
        assertEquals(OutputDeviceProtocol.AIRPLAY, devices[0].protocol)
        assertTrue(devices[0].requiresPassword)
        // Server-reported devices default to the SERVER namespace (R6.7).
        assertEquals(DeviceOrigin.SERVER, devices[0].origin)
        assertEquals(OutputDeviceProtocol.CHROMECAST, devices[1].protocol)
        // requires_password absent -> defaults to false.
        assertEquals(false, devices[1].requiresPassword)
    }

    @Test
    fun localDeviceIsConstructedWithLocalOrigin() {
        val device =
            OutputDevice(
                id = "cast-1",
                name = "Speaker",
                protocol = OutputDeviceProtocol.CHROMECAST,
                origin = DeviceOrigin.LOCAL,
            )

        assertEquals(DeviceOrigin.LOCAL, device.origin)
    }

    @Test
    fun decodesPlaybackSessionWithSnakeCaseFields() {
        val payload =
            """
            {
              "state": "playing",
              "current_song_id": 42,
              "position": 12.5,
              "active_device_ids": ["a1", "c1"],
              "volume": 0.8
            }
            """.trimIndent()

        val session = json.decodeFromString<PlaybackSession>(payload)

        assertEquals("playing", session.state)
        assertEquals(42L, session.currentSongId)
        assertEquals(12.5, session.position)
        assertEquals(listOf("a1", "c1"), session.activeDeviceIds)
        assertEquals(0.8, session.volume)
    }

    @Test
    fun decodesPlaybackSessionWithNullSongAndDefaultVolume() {
        val payload =
            """
            {
              "state": "stopped",
              "current_song_id": null,
              "position": 0.0,
              "active_device_ids": []
            }
            """.trimIndent()

        val session = json.decodeFromString<PlaybackSession>(payload)

        assertEquals("stopped", session.state)
        assertNull(session.currentSongId)
        assertEquals(emptyList(), session.activeDeviceIds)
        // volume absent -> defaults to 1.0.
        assertEquals(1.0, session.volume)
    }

    @Test
    fun playbackOpWireValuesAreLowercase() {
        assertEquals("play", PlaybackOp.PLAY.value)
        assertEquals("resume", PlaybackOp.RESUME.value)
        assertEquals("pause", PlaybackOp.PAUSE.value)
        assertEquals("stop", PlaybackOp.STOP.value)
    }
}
