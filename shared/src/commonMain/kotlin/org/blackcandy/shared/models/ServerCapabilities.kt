package org.blackcandy.shared.models

import kotlinx.serialization.Serializable

/**
 * The multi-server / playback features the connected Server supports (spec R4).
 *
 * When the Server reports this block explicitly it is used as-is; otherwise the
 * app infers it from the Server version (see [SystemInfo.resolvedCapabilities]).
 * All fields default to `false` so an unknown/older Server degrades gracefully (R18).
 */
@Serializable
data class ServerCapabilities(
    val resolvedStreamPaths: Boolean = false,
    val outputDevices: Boolean = false,
    val serverPlayback: Boolean = false,
    val castStreamUrls: Boolean = false,
) {
    companion object {
        val NONE = ServerCapabilities()

        /** Every multi-server capability enabled. */
        val ALL =
            ServerCapabilities(
                resolvedStreamPaths = true,
                outputDevices = true,
                serverPlayback = true,
                castStreamUrls = true,
            )
    }
}
