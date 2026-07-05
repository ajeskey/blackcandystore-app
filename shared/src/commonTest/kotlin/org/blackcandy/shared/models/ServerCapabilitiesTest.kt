package org.blackcandy.shared.models

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerCapabilitiesTest {
    private fun systemInfo(
        version: SystemInfo.Version,
        capabilities: ServerCapabilities? = null,
    ) = SystemInfo(version = version, capabilities = capabilities)

    @Test
    fun reportedCapabilitiesArePassedThrough() {
        val reported = ServerCapabilities(resolvedStreamPaths = true, outputDevices = true)
        val info = systemInfo(SystemInfo.Version(4, 0, 0), reported)
        assertEquals(reported, info.resolvedCapabilities)
    }

    @Test
    fun reportedCapabilitiesWinEvenOnOldVersion() {
        val reported = ServerCapabilities(resolvedStreamPaths = true)
        val info = systemInfo(SystemInfo.Version(3, 2, 0), reported)
        assertEquals(reported, info.resolvedCapabilities)
    }

    @Test
    fun versionAtOrAboveMinimumInfersAllCapabilities() {
        val info = systemInfo(SystemInfo.MULTI_SERVER_MIN_VERSION)
        assertEquals(ServerCapabilities.ALL, info.resolvedCapabilities)
    }

    @Test
    fun versionBelowMinimumInfersNoCapabilities() {
        val info = systemInfo(SystemInfo.Version(3, 2, 0))
        assertEquals(ServerCapabilities.NONE, info.resolvedCapabilities)
    }

    @Test
    fun higherMajorVersionInfersAllCapabilities() {
        val info = systemInfo(SystemInfo.Version(5, 1, 2))
        assertEquals(ServerCapabilities.ALL, info.resolvedCapabilities)
    }
}
