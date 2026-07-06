package org.blackcandy.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.blackcandy.shared.api.ApiError
import org.blackcandy.shared.api.ApiException
import org.blackcandy.shared.api.BlackCandyServiceImpl
import org.blackcandy.shared.media.PlaybackState
import org.blackcandy.shared.media.PlaybackTarget
import org.blackcandy.shared.media.ServerPlaybackEngine
import org.blackcandy.shared.models.DeviceOrigin
import org.blackcandy.shared.models.OutputDevice
import org.blackcandy.shared.models.OutputDeviceProtocol
import org.blackcandy.shared.models.PlaybackOp
import org.blackcandy.shared.utils.TaskResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the server-driven playback control + polling **network path**
 * (task 12.3; spec R14.1, R14.2, R14.4, R14.5, R14.6).
 *
 * These are NOT property-based. They drive the real [BlackCandyServiceImpl] over a real Ktor
 * [HttpClient] whose engine is a [MockEngine] stub, so the JSON (snake_case) request-body
 * building, response decoding, and error-to-[ApiException] mapping are all exercised end to end,
 * exactly as they are configured in `di/CommonModule.kt`. The higher-level [ServerPlaybackEngine]
 * and [PlaybackSessionRepository] are layered on top of that same client so the polling loop and
 * session adoption are verified against stubbed server responses.
 *
 * Polling timing is controlled with a small [ServerPlaybackEngine] poll interval and short real
 * delays under `runBlocking`; no coroutines-test virtual clock is required.
 */
class ServerPlaybackNetworkIntegrationTest {
    // ---- Test client wiring (mirrors di/CommonModule.provideHttpClient) -------------------------

    @OptIn(ExperimentalSerializationApi::class)
    private fun testJson(): Json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            namingStrategy = JsonNamingStrategy.SnakeCase
            useAlternativeNames = false
        }

    /**
     * Build an [HttpClient] backed by [MockEngine] configured like the production client:
     * `expectSuccess`, JSON content negotiation with the snake_case [Json], a default base URL so
     * relative service paths resolve, and the same [HttpResponseValidator] that converts 4xx
     * responses into [ApiException] carrying the server [ApiError] message (used by R14.5).
     */
    private fun mockClient(
        json: Json,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): HttpClient =
        HttpClient(MockEngine) {
            expectSuccess = true

            engine {
                addHandler(handler)
            }

            install(ContentNegotiation) {
                json(json)
            }

            defaultRequest {
                url("https://server.test/")
                contentType(ContentType.Application.Json)
            }

            HttpResponseValidator {
                handleResponseExceptionWithRequest { exception, _ ->
                    when (exception) {
                        is ClientRequestException -> {
                            val response = exception.response
                            val apiError =
                                try {
                                    json.decodeFromString<ApiError>(response.bodyAsText())
                                } catch (e: Exception) {
                                    null
                                }
                            throw ApiException(
                                code = response.status.value,
                                message = apiError?.message ?: exception.message,
                            )
                        }

                        else ->
                            throw ApiException(
                                code = null,
                                message = exception.message,
                            )
                    }
                }
            }
        }

    private fun MockRequestHandleScope.jsonOk(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

    private fun MockRequestHandleScope.jsonError(
        status: HttpStatusCode,
        type: String,
        message: String,
    ) = respond(
        content = """{"type":"$type","message":"$message"}""",
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    /** A server Playback_Session payload in the wire (snake_case) shape the app decodes. */
    private fun sessionJson(
        state: String,
        currentSongId: Long? = null,
        position: Double = 0.0,
        activeDeviceIds: List<String> = listOf("d1"),
        volume: Double = 1.0,
    ): String {
        val ids = activeDeviceIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val songId = currentSongId?.toString() ?: "null"
        return """
            {
              "state": "$state",
              "current_song_id": $songId,
              "position": $position,
              "active_device_ids": $ids,
              "volume": $volume
            }
            """.trimIndent()
    }

    private fun bodyOf(request: HttpRequestData): String = (request.body as? TextContent)?.text.orEmpty()

    private fun serverDevice(
        id: String,
        requiresPassword: Boolean = false,
    ) = OutputDevice(
        id = id,
        name = "Server $id",
        protocol = OutputDeviceProtocol.AIRPLAY,
        requiresPassword = requiresPassword,
        origin = DeviceOrigin.SERVER,
    )

    // ---- R14.1: create/update session sends the correct body and adopts the returned session ----

    @Test
    fun putPlaybackSession_sendsSelectedDevicesAndCurrentSong_andAdoptsReturnedSession() =
        runBlocking {
            val json = testJson()
            var capturedPath: String? = null
            var capturedMethod: HttpMethod? = null
            var capturedBody = ""

            val service =
                BlackCandyServiceImpl(
                    mockClient(json) { request ->
                        capturedPath = request.url.encodedPath
                        capturedMethod = request.method
                        capturedBody = bodyOf(request)
                        jsonOk(
                            sessionJson(
                                state = "playing",
                                currentSongId = 42,
                                position = 0.0,
                                activeDeviceIds = listOf("d1", "d2"),
                            ),
                        )
                    },
                )

            val repo = PlaybackSessionRepository(service)
            val result = repo.putPlaybackSession(deviceIds = listOf("d1", "d2"), currentSongId = 42, devicePassword = "secret")

            // Correct endpoint + verb (PUT playback_session — R14.1).
            assertEquals("/playback_session", capturedPath)
            assertEquals(HttpMethod.Put, capturedMethod)

            // Correct request body: selected device ids, current song reference, and password (R13.3, R14.1, R14.7).
            val body = json.parseToJsonElement(capturedBody).jsonObject
            assertEquals(
                listOf("d1", "d2"),
                body["device_ids"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(42L, body["current_song_id"]!!.jsonPrimitive.content.toLong())
            assertEquals("secret", body["device_password"]!!.jsonPrimitive.content)

            // Adopts the returned session as truth.
            val session = (result as TaskResult.Success).data
            assertEquals("playing", session.state)
            assertEquals(42L, session.currentSongId)
            assertEquals(listOf("d1", "d2"), session.activeDeviceIds)
        }

    // ---- R14.2: play / pause / stop / volume issue the correct request and adopt the state ------

    @Test
    fun controlPlaybackSession_play_pause_stop_issueCorrectOpAndAdoptState() =
        runBlocking {
            val json = testJson()

            data class Case(
                val op: PlaybackOp,
                val serverState: String,
                val expected: PlaybackState,
            )

            val cases =
                listOf(
                    Case(PlaybackOp.PLAY, "playing", PlaybackState.PLAYING),
                    Case(PlaybackOp.PAUSE, "paused", PlaybackState.PAUSED),
                    Case(PlaybackOp.STOP, "stopped", PlaybackState.IDLE),
                )

            for (case in cases) {
                var capturedPath: String? = null
                var capturedOp: String? = null

                val service =
                    BlackCandyServiceImpl(
                        mockClient(json) { request ->
                            capturedPath = request.url.encodedPath
                            capturedOp =
                                json
                                    .parseToJsonElement(bodyOf(request))
                                    .jsonObject["op"]
                                    ?.jsonPrimitive
                                    ?.content
                            jsonOk(sessionJson(state = case.serverState, currentSongId = 7))
                        },
                    )

                val result = PlaybackSessionRepository(service).controlPlaybackSession(case.op)

                assertEquals("/playback_session/control", capturedPath, "control endpoint for ${case.op}")
                assertEquals(case.op.value, capturedOp, "wire op value for ${case.op}")

                val session = (result as TaskResult.Success).data
                assertEquals(case.serverState, session.state, "adopted server state for ${case.op}")
            }
        }

    @Test
    fun controlPlaybackSession_volume_sendsNormalizedVolumeAndAdoptsIt() =
        runBlocking {
            val json = testJson()
            var capturedVolume: Double? = null

            val service =
                BlackCandyServiceImpl(
                    mockClient(json) { request ->
                        capturedVolume =
                            json
                                .parseToJsonElement(bodyOf(request))
                                .jsonObject["volume"]
                                ?.jsonPrimitive
                                ?.content
                                ?.toDouble()
                        jsonOk(sessionJson(state = "playing", currentSongId = 7, volume = 0.35))
                    },
                )

            val result = PlaybackSessionRepository(service).controlPlaybackSession(PlaybackOp.RESUME, volume = 0.35)

            assertEquals(0.35, capturedVolume)
            assertEquals(0.35, (result as TaskResult.Success).data.volume)
        }

    // ---- R14.4: Session_Observation polling adopts server state changes over time ---------------

    @Test
    fun serverPlaybackEngine_pollingAdoptsServerStateChangesOverTime() =
        runBlocking {
            val json = testJson()

            // The server reports "playing" when the session is created, then "paused" on every
            // subsequent poll — simulating state changing under the app (e.g. another remote).
            val service =
                BlackCandyServiceImpl(
                    mockClient(json) { request ->
                        val path = request.url.encodedPath
                        when {
                            request.method == HttpMethod.Delete && path == "/current_playlist/songs" -> jsonOk("{}")
                            request.method == HttpMethod.Put && path == "/playback_session" ->
                                jsonOk(sessionJson(state = "playing", currentSongId = 7))
                            request.method == HttpMethod.Get && path == "/playback_session" ->
                                jsonOk(sessionJson(state = "paused", currentSongId = 7))
                            else -> jsonOk("{}")
                        }
                    },
                )

            val scope = CoroutineScope(Dispatchers.Default)
            val engine =
                ServerPlaybackEngine(
                    sessionRepository = PlaybackSessionRepository(service),
                    currentPlaylistRepository = CurrentPlaylistRepository(service),
                    scope = scope,
                    pollIntervalMillis = 30,
                )

            try {
                engine.activate(PlaybackTarget.ServerDevice(devices = listOf(serverDevice("d1"))))

                // Immediately after create, the engine reflects the created (playing) session (R14.1).
                assertEquals(PlaybackState.PLAYING, engine.status.value.state)

                // After a few poll intervals, the engine adopts the polled (paused) server state (R14.4).
                waitUntil(timeoutMillis = 2_000) { engine.status.value.state == PlaybackState.PAUSED }
                assertEquals(PlaybackState.PAUSED, engine.status.value.state)
            } finally {
                scope.cancel()
            }
        }

    // ---- R14.5: play/resume with no active device surfaces the server error, state unchanged -----

    @Test
    fun noActiveDevice_playSurfacesServerErrorAndLeavesStateUnchanged() =
        runBlocking {
            val json = testJson()

            // At the repository/service level: a 4xx "no device selected" surfaces via TaskResult.Failure.
            val failingService =
                BlackCandyServiceImpl(
                    mockClient(json) {
                        jsonError(HttpStatusCode.UnprocessableEntity, type = "no_device", message = "No device selected")
                    },
                )
            val controlResult = PlaybackSessionRepository(failingService).controlPlaybackSession(PlaybackOp.PLAY)
            assertTrue(controlResult is TaskResult.Failure)
            assertEquals("No device selected", (controlResult as TaskResult.Failure).message)

            // Through the engine: create the session first (playing), then a failing play must
            // surface the error while leaving the displayed transport state unchanged (R14.5).
            var created = false
            val service =
                BlackCandyServiceImpl(
                    mockClient(json) { request ->
                        val path = request.url.encodedPath
                        when {
                            request.method == HttpMethod.Delete && path == "/current_playlist/songs" -> jsonOk("{}")
                            request.method == HttpMethod.Put && path == "/playback_session" -> {
                                created = true
                                jsonOk(sessionJson(state = "playing", currentSongId = 7))
                            }
                            // No active device: control ops are rejected by the server (R14.5).
                            request.method == HttpMethod.Post && path == "/playback_session/control" ->
                                jsonError(HttpStatusCode.UnprocessableEntity, type = "no_device", message = "No device selected")
                            // Polling keeps returning the last-good playing state.
                            request.method == HttpMethod.Get && path == "/playback_session" ->
                                jsonOk(sessionJson(state = "playing", currentSongId = 7))
                            else -> jsonOk("{}")
                        }
                    },
                )

            val scope = CoroutineScope(Dispatchers.Default)
            val engine =
                ServerPlaybackEngine(
                    sessionRepository = PlaybackSessionRepository(service),
                    currentPlaylistRepository = CurrentPlaylistRepository(service),
                    scope = scope,
                    pollIntervalMillis = 10_000, // large: keep polling out of this assertion
                )

            try {
                engine.activate(PlaybackTarget.ServerDevice(devices = listOf(serverDevice("d1"))))
                assertTrue(created)
                assertEquals(PlaybackState.PLAYING, engine.status.value.state)

                engine.play() // rejected by the server (no device)

                // The server error is surfaced and the transport state is left unchanged (R14.5).
                waitUntil(timeoutMillis = 2_000) { engine.status.value.error != null }
                assertNotNull(engine.status.value.error)
                assertEquals(PlaybackState.PLAYING, engine.status.value.state)
            } finally {
                scope.cancel()
            }
        }

    // ---- R14.6: last-device-stop reflected from the server session ------------------------------

    @Test
    fun lastDeviceLost_pollingReflectsServerStopWithNoActiveDevices() =
        runBlocking {
            val json = testJson()

            // Session is created playing on one device; then the server reports the device gone:
            // stopped with an empty active-device set (last-device-stop, R14.6).
            val service =
                BlackCandyServiceImpl(
                    mockClient(json) { request ->
                        val path = request.url.encodedPath
                        when {
                            request.method == HttpMethod.Delete && path == "/current_playlist/songs" -> jsonOk("{}")
                            request.method == HttpMethod.Put && path == "/playback_session" ->
                                jsonOk(sessionJson(state = "playing", currentSongId = 7, activeDeviceIds = listOf("d1")))
                            request.method == HttpMethod.Get && path == "/playback_session" ->
                                jsonOk(sessionJson(state = "stopped", currentSongId = 7, activeDeviceIds = emptyList()))
                            else -> jsonOk("{}")
                        }
                    },
                )

            val scope = CoroutineScope(Dispatchers.Default)
            val engine =
                ServerPlaybackEngine(
                    sessionRepository = PlaybackSessionRepository(service),
                    currentPlaylistRepository = CurrentPlaylistRepository(service),
                    scope = scope,
                    pollIntervalMillis = 30,
                )

            try {
                engine.activate(PlaybackTarget.ServerDevice(devices = listOf(serverDevice("d1"))))
                assertEquals(PlaybackState.PLAYING, engine.status.value.state)

                // After polling picks up the server's stop, transport is stopped and no active
                // devices remain in the reported target (R14.6).
                waitUntil(timeoutMillis = 2_000) { engine.status.value.state == PlaybackState.IDLE }
                assertEquals(PlaybackState.IDLE, engine.status.value.state)

                val target = engine.status.value.target as? PlaybackTarget.ServerDevice
                assertNotNull(target)
                assertTrue(target.devices.isEmpty(), "no active devices should remain after last-device stop")
            } finally {
                scope.cancel()
            }
        }

    /** Poll a condition on the calling thread until true or the timeout elapses. */
    private fun waitUntil(
        timeoutMillis: Long,
        condition: () -> Boolean,
    ) = runBlocking {
        val step = 10L
        var waited = 0L
        while (!condition() && waited < timeoutMillis) {
            delay(step)
            waited += step
        }
    }
}
