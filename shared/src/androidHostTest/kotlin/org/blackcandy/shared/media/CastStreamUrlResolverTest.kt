package org.blackcandy.shared.media

import kotlinx.coroutines.runBlocking
import org.blackcandy.shared.models.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [CastStreamUrlResolver] — the Cast_Stream_Url preference ordering (spec R11).
 *
 * These pin the four-step resolution order (payload cast url → dedicated endpoint → reachable
 * playback path → not castable) and the two safety properties: the dedicated endpoint is only
 * called when the Server advertises support (R18.2 gate), and the resolver returns Server-issued
 * URLs as-is without appending the app's credentials (R11.5). No unreachable URL is loaded to
 * decide a song is not castable (R11.4).
 */
class CastStreamUrlResolverTest {
    private fun song(
        id: Long = 1L,
        url: String = "https://server.example/stream/$id",
        resolvedStreamPath: String? = null,
        castStreamUrl: String? = null,
    ) = Song(
        id = id,
        name = "Song $id",
        duration = 123.0,
        albumId = 1,
        artistId = 1,
        url = url,
        albumName = "Album",
        artistName = "Artist",
        format = "mp3",
        albumImageUrls =
            Song.ImageURLs(
                small = "https://cdn.example/s.jpg",
                medium = "https://cdn.example/m.jpg",
                large = "https://cdn.example/l.jpg",
            ),
        isFavorited = false,
        resolvedStreamPath = resolvedStreamPath,
        castStreamUrl = castStreamUrl,
    )

    /** Builds a resolver, recording whether the endpoint was consulted. */
    private class Fixture(
        val supported: Boolean,
        val endpointUrl: String?,
    ) {
        var endpointCallCount = 0

        val resolver =
            CastStreamUrlResolver(
                castStreamUrlsSupported = { supported },
                fetchCastStreamUrl = {
                    endpointCallCount++
                    endpointUrl
                },
            )
    }

    // 1. R11.2 — a cast url in the Song payload is used and the endpoint is never consulted.
    @Test
    fun prefersPayloadCastStreamUrl() =
        runBlocking {
            val fixture = Fixture(supported = true, endpointUrl = "https://server.example/cast/endpoint/1")
            val song =
                song(
                    resolvedStreamPath = "https://server.example/resolved/1",
                    castStreamUrl = "https://server.example/cast/payload/1",
                )

            val result = fixture.resolver.resolve(song)

            assertEquals("https://server.example/cast/payload/1", result)
            assertEquals(0, fixture.endpointCallCount, "endpoint must not be called when payload has a cast url")
        }

    // 2. R11.2 — no payload cast url but a supported dedicated endpoint returns one: use it.
    @Test
    fun usesDedicatedEndpointWhenPayloadAbsentAndSupported() =
        runBlocking {
            val fixture = Fixture(supported = true, endpointUrl = "https://server.example/cast/endpoint/1")
            val song = song(resolvedStreamPath = "https://server.example/resolved/1", castStreamUrl = null)

            val result = fixture.resolver.resolve(song)

            assertEquals("https://server.example/cast/endpoint/1", result)
            assertEquals(1, fixture.endpointCallCount)
        }

    // 3. R11.3 — no payload cast url and endpoint unsupported: fall back to the reachable playback path,
    //    without ever calling the endpoint (R18.2 gate).
    @Test
    fun fallsBackToPlaybackUrlWhenEndpointUnsupported() =
        runBlocking {
            val fixture = Fixture(supported = false, endpointUrl = "https://server.example/cast/endpoint/1")
            val song = song(resolvedStreamPath = "https://server.example/resolved/1", castStreamUrl = null)

            val result = fixture.resolver.resolve(song)

            assertEquals("https://server.example/resolved/1", result)
            assertEquals(0, fixture.endpointCallCount, "endpoint must not be called when the Server does not advertise support")
        }

    // 3b. R11.3 — supported endpoint returns nothing: fall through to the reachable playback path.
    @Test
    fun fallsBackToPlaybackUrlWhenEndpointReturnsNull() =
        runBlocking {
            val fixture = Fixture(supported = true, endpointUrl = null)
            val song = song(resolvedStreamPath = "https://server.example/resolved/1", castStreamUrl = null)

            val result = fixture.resolver.resolve(song)

            assertEquals("https://server.example/resolved/1", result)
            assertEquals(1, fixture.endpointCallCount)
        }

    // 3c. R11.3 — with no resolved path, the reachable fallback is the legacy url.
    @Test
    fun fallsBackToLegacyUrlWhenNoResolvedPath() =
        runBlocking {
            val fixture = Fixture(supported = false, endpointUrl = null)
            val song = song(url = "https://server.example/legacy/1", resolvedStreamPath = null, castStreamUrl = null)

            val result = fixture.resolver.resolve(song)

            assertEquals("https://server.example/legacy/1", result)
        }

    // 4. R11.4 — nothing obtainable and the song is unavailable: not castable (null), no URL loaded.
    @Test
    fun returnsNullWhenNothingObtainable() =
        runBlocking {
            val fixture = Fixture(supported = true, endpointUrl = null)
            // Empty resolved path => unavailable; empty legacy url as well.
            val song = song(url = "", resolvedStreamPath = "", castStreamUrl = null)

            val result = fixture.resolver.resolve(song)

            assertNull(result)
        }

    // Empty candidate strings are treated as absent and fall through to the next option.
    @Test
    fun treatsEmptyPayloadAndEndpointUrlsAsAbsent() =
        runBlocking {
            val fixture = Fixture(supported = true, endpointUrl = "")
            val song = song(resolvedStreamPath = "https://server.example/resolved/1", castStreamUrl = "")

            val result = fixture.resolver.resolve(song)

            // Empty payload cast url and empty endpoint url both skipped; reachable path wins.
            assertEquals("https://server.example/resolved/1", result)
        }

    // R11.5 — the resolver returns Server-issued URLs verbatim; it never appends a token/credential.
    @Test
    fun doesNotAppendCredentialsToResolvedUrl() =
        runBlocking {
            val serverIssued = "https://server.example/cast/signed/1?sig=abc123&exp=999"
            val fixture = Fixture(supported = true, endpointUrl = serverIssued)
            val song = song(resolvedStreamPath = "https://server.example/resolved/1", castStreamUrl = null)

            val result = fixture.resolver.resolve(song)

            assertEquals(serverIssued, result, "cast url must be returned exactly as issued by the Server")
        }
}
