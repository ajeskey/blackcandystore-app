package org.blackcandy.shared.media

import org.blackcandy.shared.models.Song

/**
 * Pure, engine-independent logic for choosing the next playable song in a queue
 * when some songs may be unavailable (spec R2, R3). Shared by every PlaybackEngine
 * so local, cast, and server playback skip unavailable songs identically.
 *
 * Validated by Property 2 (queue integrity under unavailability).
 */
object QueuePlanner {
    enum class Direction { FORWARD, BACKWARD }

    /**
     * The index of the next available song starting from [from] and moving in
     * [direction], honoring the repeat/shuffle [playbackMode], or `null` when no
     * available song remains (R2.4, R2.5, R2.7).
     *
     * @param from the current index (may be out of range; treated as a starting point).
     */
    fun nextAvailableIndex(
        queue: List<Song>,
        from: Int,
        direction: Direction = Direction.FORWARD,
        playbackMode: PlaybackMode = PlaybackMode.NO_REPEAT,
    ): Int? {
        if (queue.isEmpty()) return null

        // REPEAT_ONE stays on the current song only if it is available (R2.7).
        if (playbackMode == PlaybackMode.REPEAT_ONE) {
            val current = queue.getOrNull(from)
            return if (current != null && current.isAvailable) from else null
        }

        val step = if (direction == Direction.BACKWARD) -1 else 1
        val size = queue.size
        val wraps = playbackMode == PlaybackMode.REPEAT || playbackMode == PlaybackMode.SHUFFLE

        // Scan up to `size` subsequent positions. With wrapping we consider the whole
        // ring (excluding the start); without wrapping we stop at the queue boundary.
        var index = from + step
        var scanned = 0
        while (scanned < size) {
            if (index < 0 || index >= size) {
                if (!wraps) return null
                index = ((index % size) + size) % size
            }
            val song = queue[index]
            if (song.isAvailable) return index
            index += step
            scanned++
        }
        return null
    }

    /** True when at least one song in the queue is currently playable. */
    fun hasAvailableSong(queue: List<Song>): Boolean = queue.any { it.isAvailable }

    /**
     * The index to start playback from when a queue is (re)loaded: the requested
     * [preferredIndex] if available, otherwise the first available song, or `null`
     * when nothing is playable (R2.5).
     */
    fun startIndex(
        queue: List<Song>,
        preferredIndex: Int = 0,
    ): Int? {
        queue.getOrNull(preferredIndex)?.let { if (it.isAvailable) return preferredIndex }
        val firstAvailable = queue.indexOfFirst { it.isAvailable }
        return if (firstAvailable >= 0) firstAvailable else null
    }
}
