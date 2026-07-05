package org.blackcandy.shared.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class StreamSource {
    @SerialName("local")
    LOCAL,

    @SerialName("remote")
    REMOTE,
}

@Serializable
data class Song(
    val id: Long,
    val name: String,
    val duration: Double,
    val albumId: Long,
    val artistId: Long,
    val url: String,
    val albumName: String,
    val artistName: String,
    val format: String,
    val albumImageUrls: ImageURLs,
    var isFavorited: Boolean,
    // Multi-server fields (see spec app-multi-server-playback-and-casting, R1/R11/R17).
    // Defaults keep older server payloads and cached data decoding unchanged (R18.5).
    val streamSource: StreamSource = StreamSource.LOCAL,
    val resolvedStreamPath: String? = null,
    val castStreamUrl: String? = null,
    val resolvedAssetPath: String? = null,
) {
    /**
     * The URL the App_Player fetches audio from. Prefer the server-resolved path;
     * fall back to the legacy [url] when the server does not report one (R1.2, R1.3).
     */
    val playbackUrl: String get() = resolvedStreamPath ?: url

    /**
     * Whether the song can currently be played. An empty resolved path means the
     * source is unavailable (R1.5); an absent resolved path falls back to the legacy url.
     */
    val isAvailable: Boolean
        get() = if (resolvedStreamPath != null) resolvedStreamPath.isNotEmpty() else url.isNotEmpty()

    /**
     * A URL a Chromecast receiver can fetch itself. Prefer a dedicated, independently
     * authenticated cast URL; otherwise reuse the playback url when the song is available (R11.3).
     * Returns null when the song is not castable to a receiver (R11.4).
     */
    fun castUrlOrNull(): String? = castStreamUrl ?: playbackUrl.takeIf { isAvailable }

    /**
     * Artwork URL: prefer the server-resolved asset path, fall back to the legacy large image (R17.1, R17.2).
     * The Server resolves a single asset path (no size variants), so remote artwork uses it directly
     * while local content keeps its size-specific legacy URLs.
     */
    val artworkUrl: String get() = resolvedAssetPath?.takeIf { it.isNotEmpty() } ?: albumImageUrls.large

    /** Small-size artwork URL for thumbnails, with the same resolved-then-legacy fallback (R17). */
    val artworkUrlSmall: String get() = resolvedAssetPath?.takeIf { it.isNotEmpty() } ?: albumImageUrls.small

    @Serializable
    data class ImageURLs(
        val small: String,
        val medium: String,
        val large: String,
    )
}
