package org.blackcandy.shared.models

import org.blackcandy.shared.testing.Generators
import org.blackcandy.shared.testing.checkProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongResolutionPropertyTest {
    // Feature: app-multi-server-playback-and-casting, Property 1: Resolution consistency
    @Test
    fun resolutionConsistency() {
        checkProperty { random, _ ->
            val song = Generators.song(random)

            when {
                song.resolvedStreamPath != null -> {
                    // resolved path present: it alone determines playback url and availability
                    assertEquals(song.resolvedStreamPath, song.playbackUrl)
                    assertEquals(song.resolvedStreamPath!!.isNotEmpty(), song.isAvailable)
                }

                else -> {
                    // resolved path absent: fall back to legacy url
                    assertEquals(song.url, song.playbackUrl)
                    assertEquals(song.url.isNotEmpty(), song.isAvailable)
                }
            }

            // castUrlOrNull: dedicated cast url wins; else playback url only when available
            if (song.castStreamUrl != null) {
                assertEquals(song.castStreamUrl, song.castUrlOrNull())
            } else if (song.isAvailable) {
                assertEquals(song.playbackUrl, song.castUrlOrNull())
            } else {
                assertEquals(null, song.castUrlOrNull())
            }

            // artwork: resolved asset path (non-empty) wins, else legacy large image
            val expectedArt =
                song.resolvedAssetPath?.takeIf { it.isNotEmpty() } ?: song.albumImageUrls.large
            assertEquals(expectedArt, song.artworkUrl)
        }
    }

    @Test
    fun emptyResolvedPathIsUnavailable() {
        val song = Generators.song(kotlin.random.Random(1)).copy(resolvedStreamPath = "")
        assertFalse(song.isAvailable)
    }

    @Test
    fun nonEmptyResolvedPathIsAvailable() {
        val song =
            Generators.song(kotlin.random.Random(1)).copy(resolvedStreamPath = "https://x/y")
        assertTrue(song.isAvailable)
        assertEquals("https://x/y", song.playbackUrl)
    }
}
