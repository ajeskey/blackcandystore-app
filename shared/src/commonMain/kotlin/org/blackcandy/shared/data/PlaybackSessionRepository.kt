package org.blackcandy.shared.data

import org.blackcandy.shared.api.BlackCandyService
import org.blackcandy.shared.models.PlaybackOp
import org.blackcandy.shared.models.PlaybackSession
import org.blackcandy.shared.utils.TaskResult

/**
 * Wraps the server-driven Playback_Session endpoints on [BlackCandyService] (spec R13, R14).
 *
 * Unlike [ServerDeviceRepository], session operations are genuine actions whose failures are
 * meaningful (e.g. R14.5 "no device selected", R14.7 authentication errors), so they surface
 * through [TaskResult.Failure] via the existing `asResult()` path rather than being swallowed.
 */
class PlaybackSessionRepository(
    private val service: BlackCandyService,
) {
    suspend fun getPlaybackSession(): TaskResult<PlaybackSession> = service.getPlaybackSession().asResult()

    suspend fun putPlaybackSession(
        deviceIds: List<String>,
        currentSongId: Long?,
        devicePassword: String?,
    ): TaskResult<PlaybackSession> = service.putPlaybackSession(deviceIds, currentSongId, devicePassword).asResult()

    suspend fun controlPlaybackSession(
        op: PlaybackOp,
        volume: Double? = null,
        deviceId: String? = null,
    ): TaskResult<PlaybackSession> = service.controlPlaybackSession(op, volume, deviceId).asResult()
}
