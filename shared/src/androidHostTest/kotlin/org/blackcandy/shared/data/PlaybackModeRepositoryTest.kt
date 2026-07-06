package org.blackcandy.shared.data

import kotlinx.coroutines.runBlocking
import org.blackcandy.shared.api.ApiException
import org.blackcandy.shared.api.ApiResponse
import org.blackcandy.shared.api.BlackCandyService
import org.blackcandy.shared.media.PlaybackRouting
import org.blackcandy.shared.models.AuthenticationResponse
import org.blackcandy.shared.models.OutputDevice
import org.blackcandy.shared.models.PlaybackOp
import org.blackcandy.shared.models.PlaybackSession
import org.blackcandy.shared.models.Song
import org.blackcandy.shared.models.SystemInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [PlaybackModeRepository] — the concrete [org.blackcandy.shared.media.PlaybackModeReporter]
 * backed by [BlackCandyService.setPlaybackMode] (spec R7.3).
 *
 * These pin the routing -> wire-string mapping and confirm the report is best-effort: a server
 * rejection or failure must not throw or otherwise block the caller (R7.3).
 */
class PlaybackModeRepositoryTest {
    /** A fake service that records the last reported routing string and returns a canned response. */
    private class FakeService(
        private val response: ApiResponse<Unit>,
    ) : BlackCandyService {
        var reportedRouting: String? = null
        var callCount = 0

        override suspend fun setPlaybackMode(routing: String): ApiResponse<Unit> {
            reportedRouting = routing
            callCount++
            return response
        }

        // --- Unused by these tests ---------------------------------------------------------------
        override suspend fun getSystemInfo(): ApiResponse<SystemInfo> = notUsed()

        override suspend fun createAuthentication(
            email: String,
            password: String,
        ): ApiResponse<AuthenticationResponse> = notUsed()

        override suspend fun removeAuthentication(): ApiResponse<Unit> = notUsed()

        override suspend fun getSongsFromCurrentPlaylist(): ApiResponse<List<Song>> = notUsed()

        override suspend fun getSong(songId: Long): ApiResponse<Song> = notUsed()

        override suspend fun getCastStreamUrl(songId: Long): ApiResponse<String> = notUsed()

        override suspend fun addSongToFavorite(songId: Long): ApiResponse<Song> = notUsed()

        override suspend fun removeSongFromFavorite(songId: Long): ApiResponse<Song> = notUsed()

        override suspend fun removeAllSongsFromCurrentPlaylist(): ApiResponse<Unit> = notUsed()

        override suspend fun removeSongFromCurrentPlaylist(songId: Long): ApiResponse<Unit> = notUsed()

        override suspend fun moveSongInCurrentPlaylist(
            songId: Long,
            destinationSongId: Long,
        ): ApiResponse<Unit> = notUsed()

        override suspend fun replaceCurrentPlaylistWithAlbumSongs(albumId: Long): ApiResponse<List<Song>> = notUsed()

        override suspend fun replaceCurrentPlaylistWithPlaylistSongs(playlistId: Long): ApiResponse<List<Song>> = notUsed()

        override suspend fun addSongToCurrentPlaylist(
            songId: Long,
            currentSongId: Long?,
            location: String?,
        ): ApiResponse<Song> = notUsed()

        override suspend fun getOutputDevices(): ApiResponse<List<OutputDevice>> = notUsed()

        override suspend fun getPlaybackSession(): ApiResponse<PlaybackSession> = notUsed()

        override suspend fun putPlaybackSession(
            deviceIds: List<String>,
            currentSongId: Long?,
            devicePassword: String?,
        ): ApiResponse<PlaybackSession> = notUsed()

        override suspend fun controlPlaybackSession(
            op: PlaybackOp,
            volume: Double?,
            deviceId: String?,
        ): ApiResponse<PlaybackSession> = notUsed()

        private fun notUsed(): Nothing = error("not used in PlaybackModeRepositoryTest")
    }

    @Test
    fun maps_local_routing_to_local_wire_value() =
        runBlocking {
            val service = FakeService(ApiResponse.Success(Unit))
            PlaybackModeRepository(service).reportRouting(PlaybackRouting.LOCAL)
            assertEquals("local", service.reportedRouting)
        }

    @Test
    fun maps_client_cast_routing_to_client_cast_wire_value() =
        runBlocking {
            val service = FakeService(ApiResponse.Success(Unit))
            PlaybackModeRepository(service).reportRouting(PlaybackRouting.CLIENT_CAST)
            assertEquals("client_cast", service.reportedRouting)
        }

    @Test
    fun maps_server_playback_routing_to_server_playback_wire_value() =
        runBlocking {
            val service = FakeService(ApiResponse.Success(Unit))
            PlaybackModeRepository(service).reportRouting(PlaybackRouting.SERVER_PLAYBACK)
            assertEquals("server_playback", service.reportedRouting)
        }

    @Test
    fun does_not_throw_when_server_rejects_the_report() =
        runBlocking {
            // R7.3: a failed report / server rejection must not throw or block the caller.
            val failure = ApiResponse.Failure(ApiException(code = 422, message = "unsupported"))
            val service = FakeService(failure)

            PlaybackModeRepository(service).reportRouting(PlaybackRouting.CLIENT_CAST)

            // The report was still attempted with the mapped value, and no exception escaped.
            assertEquals("client_cast", service.reportedRouting)
            assertEquals(1, service.callCount)
        }
}
