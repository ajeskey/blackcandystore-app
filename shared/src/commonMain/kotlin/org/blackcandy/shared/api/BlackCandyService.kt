package org.blackcandy.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.blackcandy.shared.models.AuthenticationResponse
import org.blackcandy.shared.models.OutputDevice
import org.blackcandy.shared.models.PlaybackOp
import org.blackcandy.shared.models.PlaybackSession
import org.blackcandy.shared.models.Song
import org.blackcandy.shared.models.SystemInfo
import org.blackcandy.shared.models.User

interface BlackCandyService {
    suspend fun getSystemInfo(): ApiResponse<SystemInfo>

    suspend fun createAuthentication(
        email: String,
        password: String,
    ): ApiResponse<AuthenticationResponse>

    suspend fun removeAuthentication(): ApiResponse<Unit>

    suspend fun getSongsFromCurrentPlaylist(): ApiResponse<List<Song>>

    suspend fun getSong(songId: Long): ApiResponse<Song>

    /**
     * Fetch an independently authenticated, receiver-reachable Cast_Stream_Url for a Song from the
     * Server's dedicated cast-url endpoint (spec R11.2). This is an optional part of the server
     * contract, gated by [org.blackcandy.shared.models.ServerCapabilities.castStreamUrls]; callers
     * degrade silently when it is absent (R18.2).
     */
    suspend fun getCastStreamUrl(songId: Long): ApiResponse<String>

    suspend fun addSongToFavorite(songId: Long): ApiResponse<Song>

    suspend fun removeSongFromFavorite(songId: Long): ApiResponse<Song>

    suspend fun removeAllSongsFromCurrentPlaylist(): ApiResponse<Unit>

    suspend fun removeSongFromCurrentPlaylist(songId: Long): ApiResponse<Unit>

    suspend fun moveSongInCurrentPlaylist(
        songId: Long,
        destinationSongId: Long,
    ): ApiResponse<Unit>

    suspend fun replaceCurrentPlaylistWithAlbumSongs(albumId: Long): ApiResponse<List<Song>>

    suspend fun replaceCurrentPlaylistWithPlaylistSongs(playlistId: Long): ApiResponse<List<Song>>

    suspend fun addSongToCurrentPlaylist(
        songId: Long,
        currentSongId: Long?,
        location: String?,
    ): ApiResponse<Song>

    suspend fun getOutputDevices(): ApiResponse<List<OutputDevice>>

    suspend fun getPlaybackSession(): ApiResponse<PlaybackSession>

    suspend fun putPlaybackSession(
        deviceIds: List<String>,
        currentSongId: Long?,
        devicePassword: String?,
    ): ApiResponse<PlaybackSession>

    suspend fun controlPlaybackSession(
        op: PlaybackOp,
        volume: Double?,
        deviceId: String?,
    ): ApiResponse<PlaybackSession>

    suspend fun setPlaybackMode(routing: String): ApiResponse<Unit>
}

class BlackCandyServiceImpl(
    private val client: HttpClient,
) : BlackCandyService {
    override suspend fun getSystemInfo(): ApiResponse<SystemInfo> =
        handleResponse {
            val response = client.get("system")
            val responseUrl = response.request.url
            val systemInfo: SystemInfo = response.body()

            systemInfo.serverAddress =
                URLBuilder(
                    protocol = responseUrl.protocol,
                    host = responseUrl.host,
                    port = responseUrl.port,
                ).buildString()

            systemInfo
        }

    override suspend fun createAuthentication(
        email: String,
        password: String,
    ): ApiResponse<AuthenticationResponse> =
        handleResponse {
            val response: HttpResponse =
                client.post("sessions") {
                    setBody(
                        buildJsonObject {
                            putJsonObject("session") {
                                put("email", email)
                                put("password", password)
                            }
                        },
                    )
                }

            val userElement = Json.parseToJsonElement(response.bodyAsText()).jsonObject["user"]!!

            val token = userElement.jsonObject["api_token"]?.jsonPrimitive.toString()
            val id = userElement.jsonObject["id"]?.jsonPrimitive?.long!!
            val userEmail = userElement.jsonObject["email"]?.jsonPrimitive.toString()
            val isAdmin = userElement.jsonObject["is_admin"]?.jsonPrimitive?.boolean!!
            val cookies = response.headers.getAll(HttpHeaders.SetCookie) ?: emptyList()

            AuthenticationResponse(
                token = token,
                user =
                    User(
                        id = id,
                        email = userEmail,
                        isAdmin = isAdmin,
                    ),
                cookies = cookies,
            )
        }

    override suspend fun removeAuthentication(): ApiResponse<Unit> =
        handleResponse {
            client.delete("my/session").body()
        }

    override suspend fun getSongsFromCurrentPlaylist(): ApiResponse<List<Song>> =
        handleResponse {
            client.get("current_playlist/songs").body()
        }

    override suspend fun getSong(songId: Long): ApiResponse<Song> =
        handleResponse {
            client.get("songs/$songId").body()
        }

    override suspend fun getCastStreamUrl(songId: Long): ApiResponse<String> =
        handleResponse {
            // ASSUMPTION (pending server-contract confirmation): the dedicated cast-url endpoint is
            // `GET songs/{id}/cast_stream_url` and returns `{ "cast_stream_url": "<url>" }`, matching
            // the `songs/{id}` convention above and the snake_case JSON contract. A missing/empty
            // field is treated as "no cast url" via an ApiException so the caller degrades silently.
            val response = client.get("songs/$songId/cast_stream_url")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val castStreamUrl = body["cast_stream_url"]?.jsonPrimitive?.contentOrNull

            if (castStreamUrl.isNullOrEmpty()) {
                throw ApiException(code = null, message = "Missing cast_stream_url in response")
            }

            castStreamUrl
        }

    override suspend fun addSongToFavorite(songId: Long): ApiResponse<Song> =
        handleResponse {
            client
                .post("favorite_playlist/songs") {
                    setBody(
                        buildJsonObject {
                            put("song_id", songId)
                        },
                    )
                }.body()
        }

    override suspend fun removeSongFromFavorite(songId: Long): ApiResponse<Song> =
        handleResponse {
            client.delete("favorite_playlist/songs/$songId").body()
        }

    override suspend fun removeAllSongsFromCurrentPlaylist(): ApiResponse<Unit> =
        handleResponse {
            client.delete("current_playlist/songs").body()
        }

    override suspend fun removeSongFromCurrentPlaylist(songId: Long): ApiResponse<Unit> =
        handleResponse {
            client.delete("current_playlist/songs/$songId").body()
        }

    override suspend fun moveSongInCurrentPlaylist(
        songId: Long,
        destinationSongId: Long,
    ): ApiResponse<Unit> =
        handleResponse {
            client
                .put("current_playlist/songs/$songId/move") {
                    setBody(
                        buildJsonObject {
                            put("destination_song_id", destinationSongId)
                        },
                    )
                }.body()
        }

    override suspend fun replaceCurrentPlaylistWithAlbumSongs(albumId: Long): ApiResponse<List<Song>> =
        handleResponse {
            client.put("current_playlist/songs/albums/$albumId").body()
        }

    override suspend fun replaceCurrentPlaylistWithPlaylistSongs(playlistId: Long): ApiResponse<List<Song>> =
        handleResponse {
            client.put("current_playlist/songs/playlists/$playlistId").body()
        }

    override suspend fun addSongToCurrentPlaylist(
        songId: Long,
        currentSongId: Long?,
        location: String?,
    ): ApiResponse<Song> =
        handleResponse {
            client
                .post("current_playlist/songs") {
                    setBody(
                        buildJsonObject {
                            put("song_id", songId)

                            if (currentSongId != null) {
                                put("current_song_id", currentSongId)
                            }

                            if (location != null) {
                                put("location", location)
                            }
                        },
                    )
                }.body()
        }

    override suspend fun getOutputDevices(): ApiResponse<List<OutputDevice>> =
        handleResponse {
            client.get("output_devices").body()
        }

    override suspend fun getPlaybackSession(): ApiResponse<PlaybackSession> =
        handleResponse {
            client.get("playback_session").body()
        }

    override suspend fun putPlaybackSession(
        deviceIds: List<String>,
        currentSongId: Long?,
        devicePassword: String?,
    ): ApiResponse<PlaybackSession> =
        handleResponse {
            client
                .put("playback_session") {
                    setBody(
                        buildJsonObject {
                            putJsonArray("device_ids") {
                                deviceIds.forEach { add(it) }
                            }

                            if (currentSongId != null) {
                                put("current_song_id", currentSongId)
                            }

                            if (devicePassword != null) {
                                put("device_password", devicePassword)
                            }
                        },
                    )
                }.body()
        }

    override suspend fun controlPlaybackSession(
        op: PlaybackOp,
        volume: Double?,
        deviceId: String?,
    ): ApiResponse<PlaybackSession> =
        handleResponse {
            client
                .post("playback_session/control") {
                    setBody(
                        buildJsonObject {
                            put("op", op.value)

                            if (volume != null) {
                                put("volume", volume)
                            }

                            if (deviceId != null) {
                                put("device_id", deviceId)
                            }
                        },
                    )
                }.body()
        }

    override suspend fun setPlaybackMode(routing: String): ApiResponse<Unit> =
        handleResponse {
            client
                .put("playback_mode") {
                    setBody(
                        buildJsonObject {
                            put("routing", routing)
                        },
                    )
                }.body()
        }

    private suspend fun <T> handleResponse(request: suspend () -> T): ApiResponse<T> =
        try {
            ApiResponse.Success(request())
        } catch (e: ApiException) {
            ApiResponse.Failure(e)
        }
}
