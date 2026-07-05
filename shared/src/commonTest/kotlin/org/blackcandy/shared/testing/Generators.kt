package org.blackcandy.shared.testing

import org.blackcandy.shared.models.Song
import org.blackcandy.shared.models.StreamSource
import kotlin.random.Random

/** Generators for property-based tests. */
object Generators {
    private fun imageUrls(random: Random): Song.ImageURLs {
        val n = random.nextInt(0, 1_000_000)
        return Song.ImageURLs(
            small = "https://cdn.example/$n/s.jpg",
            medium = "https://cdn.example/$n/m.jpg",
            large = "https://cdn.example/$n/l.jpg",
        )
    }

    /**
     * A random Song covering the resolution matrix (R1): resolved path may be
     * present-nonempty, present-empty, or absent (null); legacy url may be empty.
     */
    fun song(
        random: Random,
        id: Long = random.nextLong(1, 1_000_000),
    ): Song {
        val hasLegacyUrl = random.nextInt(10) != 0 // usually present
        val legacyUrl = if (hasLegacyUrl) "https://server.example/stream/$id" else ""

        val resolved: String? =
            when (random.nextInt(3)) {
                0 -> "https://server.example/resolved/$id"

                // present, non-empty
                1 -> ""

                // present, empty -> unavailable
                else -> null // absent -> fall back to legacy url
            }

        val source = if (random.nextBoolean()) StreamSource.REMOTE else StreamSource.LOCAL

        return Song(
            id = id,
            name = "Song $id",
            duration = random.nextDouble(1.0, 600.0),
            albumId = random.nextLong(1, 1000),
            artistId = random.nextLong(1, 1000),
            url = legacyUrl,
            albumName = "Album ${random.nextInt(1000)}",
            artistName = "Artist ${random.nextInt(1000)}",
            format = if (random.nextBoolean()) "flac" else "mp3",
            albumImageUrls = imageUrls(random),
            isFavorited = random.nextBoolean(),
            streamSource = source,
            resolvedStreamPath = resolved,
            castStreamUrl = if (random.nextInt(4) == 0) "https://server.example/cast/$id" else null,
            resolvedAssetPath = if (random.nextInt(4) == 0) "https://server.example/art/$id" else null,
        )
    }

    /** A queue of random songs of length [1, maxSize]. */
    fun queue(
        random: Random,
        maxSize: Int = 12,
    ): List<Song> {
        val size = random.nextInt(1, maxSize + 1)
        return (0 until size).map { song(random, id = it.toLong() + 1) }
    }
}
