package org.blackcandy.shared.data

import org.blackcandy.shared.api.BlackCandyService
import org.blackcandy.shared.models.Song
import org.blackcandy.shared.utils.TaskResult

/**
 * Fetches a single Song, used to re-resolve a stale/expired remote stream path
 * before deciding the song is unavailable (spec R3).
 */
class SongRepository(
    private val service: BlackCandyService,
) {
    suspend fun getSong(songId: Long): TaskResult<Song> = service.getSong(songId).asResult()
}
