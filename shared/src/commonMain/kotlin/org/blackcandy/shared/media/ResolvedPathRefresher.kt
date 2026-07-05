package org.blackcandy.shared.media

import org.blackcandy.shared.models.Song
import org.blackcandy.shared.models.StreamSource
import org.blackcandy.shared.utils.TaskResult

/**
 * Bounded refresh-and-retry policy for stale/expired remote stream paths (spec R3).
 *
 * When the player fails to load a `remote` song, the app re-fetches the song's
 * resolved fields once and either retries with the refreshed path or marks the
 * song unavailable. At most one refresh is attempted per song per play request
 * so a persistently failing song cannot loop (R3.5).
 *
 * The bounded behavior is validated by Property 6.
 */
class ResolvedPathRefresher(
    private val fetchSong: suspend (Long) -> TaskResult<Song>,
) {
    private val attemptedSongIds = mutableSetOf<Long>()

    /** Reset the per-play-request attempt scope. Call when a new play request starts. */
    fun beginPlayRequest() {
        attemptedSongIds.clear()
    }

    /**
     * React to a player load failure for [song]. Returns [RefreshOutcome.Retry] with the
     * refreshed song when a new non-empty path is obtained, otherwise [RefreshOutcome.Unavailable].
     */
    suspend fun onLoadFailure(song: Song): RefreshOutcome {
        // Only remote songs are re-resolved; local failures are terminal here (R3.1).
        if (song.streamSource != StreamSource.REMOTE) return RefreshOutcome.Unavailable

        // R3.5: at most one refresh per song per play request.
        if (!attemptedSongIds.add(song.id)) return RefreshOutcome.Unavailable

        return when (val result = fetchSong(song.id)) {
            is TaskResult.Success -> {
                val refreshed = result.data
                if (refreshed.isAvailable) RefreshOutcome.Retry(refreshed) else RefreshOutcome.Unavailable
            }

            is TaskResult.Failure -> {
                RefreshOutcome.Unavailable
            }
        }
    }

    sealed interface RefreshOutcome {
        /** A refreshed, playable song to retry with (R3.2). */
        data class Retry(
            val song: Song,
        ) : RefreshOutcome

        /** The song could not be re-resolved to a playable path; mark unavailable (R3.3). */
        data object Unavailable : RefreshOutcome
    }
}
