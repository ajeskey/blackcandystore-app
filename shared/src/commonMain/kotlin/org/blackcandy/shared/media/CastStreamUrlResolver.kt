package org.blackcandy.shared.media

import org.blackcandy.shared.models.Song

/**
 * Resolves the Cast_Stream_Url a Chromecast receiver should fetch for a Song (spec R11).
 *
 * A Chromecast receiver fetches the media URL itself and carries none of the app's session
 * credentials (R11.1), so the URL handed to it must be independently authenticated and reachable.
 * This resolver applies the fixed preference order below and never appends the app's bearer token
 * or session cookie to any candidate, so no long-lived credential is leaked into the cast URL
 * (R11.5). It applies only to the Chromecast receiver-fetch path — AirPlay and local App_Player
 * playback use the app's own credentials and do not go through here (R11.6).
 *
 * Resolution order:
 * 1. **Server-provided cast url in the Song payload** — [Song.castStreamUrl], when present and
 *    non-empty, is used outright (R11.2).
 * 2. **Dedicated cast-url endpoint** — when the Server advertises support ([castStreamUrlsSupported],
 *    gated on [org.blackcandy.shared.models.ServerCapabilities.castStreamUrls]), fetch the URL from
 *    the endpoint via [fetchCastStreamUrl]; use it when non-empty (R11.2). The gate keeps the app
 *    from calling an endpoint an older Server does not serve (R18.2), and a `null`/empty result
 *    falls through rather than failing.
 * 3. **Reachable resolved playback path** — when no dedicated cast url is obtainable but the Song's
 *    resolved playback path is itself reachable ([Song.isAvailable]), use [Song.playbackUrl] (R11.3).
 * 4. **Not castable** — otherwise return `null`; the caller must treat the Song as not castable and
 *    indicate this rather than loading an unreachable URL (R11.4). No unreachable URL is loaded to
 *    make this determination.
 *
 * The two collaborators are injected as suspending functions so the pure preference-ordering logic
 * is unit-testable without a live network; production wiring binds them to the resolved
 * [org.blackcandy.shared.models.ServerCapabilities] and
 * [org.blackcandy.shared.data.CastStreamUrlRepository].
 */
class CastStreamUrlResolver(
    private val castStreamUrlsSupported: suspend () -> Boolean,
    private val fetchCastStreamUrl: suspend (songId: Long) -> String?,
) {
    /**
     * Resolve the Cast_Stream_Url for [song], or `null` when the Song is not castable to a
     * Chromecast receiver (R11.4).
     */
    suspend fun resolve(song: Song): String? {
        // 1. R11.2 — a Server-provided cast url embedded in the Song payload wins outright.
        song.castStreamUrl?.takeIf { it.isNotEmpty() }?.let { return it }

        // 2. R11.2 — otherwise the dedicated endpoint, only when the Server advertises support.
        if (castStreamUrlsSupported()) {
            fetchCastStreamUrl(song.id)?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        // 3. R11.3 — no dedicated cast url, but the resolved playback path is itself reachable.
        if (song.isAvailable) return song.playbackUrl

        // 4. R11.4 — nothing obtainable: not castable. The caller must not load an unreachable URL.
        return null
    }
}
