package org.blackcandy.shared.data

import org.blackcandy.shared.api.BlackCandyService

/**
 * Wraps [BlackCandyService.getCastStreamUrl] — the Server's optional dedicated Cast_Stream_Url
 * endpoint (spec R11.2).
 *
 * Per R18.2 the endpoint is capability-gated and degrades silently: a Server that does not provide
 * it, an empty response, or a failed request all resolve to `null` here rather than an error, so
 * the [org.blackcandy.shared.media.CastStreamUrlResolver] can fall through to its next option
 * (R11.3) or treat the song as not castable (R11.4) without surfacing a failure.
 *
 * The returned URL is the Server-issued value as-is; this repository never appends the app's bearer
 * token or session cookie to it, so no long-lived credential is leaked into the cast URL (R11.5).
 */
class CastStreamUrlRepository(
    private val service: BlackCandyService,
) {
    suspend fun getCastStreamUrl(songId: Long): String? = service.getCastStreamUrl(songId).orNull()
}
