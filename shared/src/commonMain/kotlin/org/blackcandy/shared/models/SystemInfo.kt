package org.blackcandy.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class SystemInfo(
    val version: Version,
    var serverAddress: String? = null,
    val minAppVersion: Version? = null,
    // Multi-server capability advertisement (spec R4.1). Null on Servers that
    // predate the capabilities contract; in that case capabilities are inferred
    // from [version] (see [resolvedCapabilities]).
    val capabilities: ServerCapabilities? = null,
) {
    val isServerSupported get() = MinServerVersion.isSupported(version)

    val isAppSupported: Boolean
        get() {
            val min = minAppVersion ?: return true
            return AppVersion.isSupported(min)
        }

    /**
     * The capabilities the app should gate on (R4.1, R4.4, R4.5):
     * use the Server-reported block when present, otherwise infer from the Server
     * version. A Server below the multi-server minimum resolves to [ServerCapabilities.NONE].
     */
    val resolvedCapabilities: ServerCapabilities
        get() =
            capabilities ?: if (version.isAtLeast(MULTI_SERVER_MIN_VERSION)) {
                ServerCapabilities.ALL
            } else {
                ServerCapabilities.NONE
            }

    @Serializable
    data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val pre: String? = null,
    ) {
        /** Version-order comparison ignoring the pre-release tag. */
        fun isAtLeast(other: Version): Boolean {
            if (major != other.major) return major > other.major
            if (minor != other.minor) return minor > other.minor
            return patch >= other.patch
        }
    }

    companion object {
        /**
         * Minimum Server version assumed to support the multi-server / casting features
         * when the Server does not advertise a [capabilities] block.
         *
         * ASSUMPTION pending server-contract confirmation: adjust to the actual Black
         * Candy Store release that introduced multi-server-library-sharing.
         */
        val MULTI_SERVER_MIN_VERSION = Version(major = 4, minor = 0, patch = 0)
    }
}
