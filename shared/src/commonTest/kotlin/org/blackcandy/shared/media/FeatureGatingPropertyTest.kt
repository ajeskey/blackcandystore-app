package org.blackcandy.shared.media

import org.blackcandy.shared.models.OutputDeviceProtocol
import org.blackcandy.shared.models.ServerCapabilities
import org.blackcandy.shared.testing.checkProperty
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Property 5: Capability and platform gating.
 *
 * A Server-dependent feature is offered if and only if the resolved [ServerCapabilities] reports
 * it, and a client-cast protocol is offered if and only if the platform supports it
 * (AirPlay ⇒ iOS only, Chromecast ⇒ both platforms when the Cast SDK is available), independent of
 * Server capabilities (spec R4.2, R4.6, R12.1, R12.2).
 *
 * The test generates random (platform, capabilities, cast-support) combinations and asserts each
 * feature/protocol is offered exactly when it is supported, via the pure [FeatureGating] logic.
 *
 * **Validates: Requirements 4.2, 4.6, 12.1, 12.2**
 */
class FeatureGatingPropertyTest {
    private fun randomCapabilities(random: kotlin.random.Random): ServerCapabilities =
        ServerCapabilities(
            resolvedStreamPaths = random.nextBoolean(),
            outputDevices = random.nextBoolean(),
            serverPlayback = random.nextBoolean(),
            castStreamUrls = random.nextBoolean(),
        )

    // Feature: app-multi-server-playback-and-casting, Property 5: Capability and platform gating
    @Test
    fun featureAndProtocolOfferedIffSupported() {
        checkProperty { random, _ ->
            val platform = if (random.nextBoolean()) AppPlatform.IOS else AppPlatform.ANDROID
            val capabilities = randomCapabilities(random)
            val castSupported = random.nextBoolean()

            // --- Server-dependent features: offered iff the resolved capability reports it (R4.2).
            assertEquals(
                capabilities.outputDevices,
                FeatureGating.isServerOutputDevicesOffered(capabilities),
                "server output-devices must be offered iff ServerCapabilities.outputDevices",
            )
            assertEquals(
                capabilities.serverPlayback,
                FeatureGating.isServerPlaybackOffered(capabilities),
                "server playback must be offered iff ServerCapabilities.serverPlayback",
            )

            // --- Client-cast protocols: gated by platform/device only (R12.1, R12.2).
            val airplayOffered =
                FeatureGating.isClientCastProtocolOffered(
                    OutputDeviceProtocol.AIRPLAY,
                    platform,
                    castSupported,
                )
            val chromecastOffered =
                FeatureGating.isClientCastProtocolOffered(
                    OutputDeviceProtocol.CHROMECAST,
                    platform,
                    castSupported,
                )

            // AirPlay client-cast is offered iff the platform is iOS (R12.1).
            assertEquals(
                platform == AppPlatform.IOS,
                airplayOffered,
                "AirPlay client-cast must be offered iff platform is iOS",
            )

            // Chromecast client-cast is offered on both platforms iff the Cast SDK is supported (R12.2).
            assertEquals(
                castSupported,
                chromecastOffered,
                "Chromecast client-cast must be offered iff the platform+device supports casting",
            )

            // --- Client-cast gating is independent of Server capabilities (R4.6):
            // re-evaluating against all-on and all-off capabilities must not change the outcome.
            listOf(ServerCapabilities.NONE, ServerCapabilities.ALL, capabilities).forEach { caps ->
                // FeatureGating.isClientCastProtocolOffered takes no capabilities, so the result is
                // structurally independent; assert stability regardless of the surrounding caps.
                assertEquals(
                    airplayOffered,
                    FeatureGating.isClientCastProtocolOffered(
                        OutputDeviceProtocol.AIRPLAY,
                        platform,
                        castSupported,
                    ),
                    "AirPlay offering must not depend on ServerCapabilities ($caps)",
                )
                assertEquals(
                    chromecastOffered,
                    FeatureGating.isClientCastProtocolOffered(
                        OutputDeviceProtocol.CHROMECAST,
                        platform,
                        castSupported,
                    ),
                    "Chromecast offering must not depend on ServerCapabilities ($caps)",
                )
            }
        }
    }
}
