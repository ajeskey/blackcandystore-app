package org.blackcandy.shared.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.blackcandy.shared.api.ApiError
import org.blackcandy.shared.api.ApiException
import org.blackcandy.shared.api.BlackCandyService
import org.blackcandy.shared.api.BlackCandyServiceImpl
import org.blackcandy.shared.data.CastStreamUrlRepository
import org.blackcandy.shared.data.CurrentPlaylistRepository
import org.blackcandy.shared.data.EncryptedDataSource
import org.blackcandy.shared.data.FavoritePlaylistRepository
import org.blackcandy.shared.data.PlaybackModeRepository
import org.blackcandy.shared.data.PlaybackSessionRepository
import org.blackcandy.shared.data.PreferencesDataSource
import org.blackcandy.shared.data.ServerAddressRepository
import org.blackcandy.shared.data.ServerDeviceRepository
import org.blackcandy.shared.data.SongRepository
import org.blackcandy.shared.data.SystemInfoRepository
import org.blackcandy.shared.data.UserRepository
import org.blackcandy.shared.media.CastStreamUrlResolver
import org.blackcandy.shared.media.ChromecastEngine
import org.blackcandy.shared.media.LocalPlaybackEngine
import org.blackcandy.shared.media.PlaybackCoordinator
import org.blackcandy.shared.media.PlaybackEngine
import org.blackcandy.shared.media.PlaybackModeReporter
import org.blackcandy.shared.media.ServerPlaybackEngine
import org.blackcandy.shared.utils.BLACK_CANDY_USER_AGENT
import org.blackcandy.shared.utils.TaskResult
import org.blackcandy.shared.viewmodels.DevicePickerViewModel
import org.blackcandy.shared.viewmodels.LoginViewModel
import org.blackcandy.shared.viewmodels.MainViewModel
import org.blackcandy.shared.viewmodels.MiniPlayerViewModel
import org.blackcandy.shared.viewmodels.MusicServiceViewModel
import org.blackcandy.shared.viewmodels.PlayerViewModel
import org.blackcandy.shared.viewmodels.WebViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val commonModule =
    module {
        single { provideJson() }
        single { provideHttpClient(get(), get(), get()) }

        single { PreferencesDataSource(get(named("PreferencesDataStore"))) }
        single<BlackCandyService> { BlackCandyServiceImpl(get()) }
        single { ServerAddressRepository(get()) }
        single { SystemInfoRepository(get()) }
        single { UserRepository(get(), get(), get(), get()) }
        single { CurrentPlaylistRepository(get()) }
        single { FavoritePlaylistRepository(get()) }
        single { SongRepository(get()) }
        single { CastStreamUrlRepository(get()) }
        single { ServerDeviceRepository(get()) }
        single { PlaybackSessionRepository(get()) }
        single<PlaybackModeReporter> { PlaybackModeRepository(get()) }

        // Cast_Stream_Url resolution for the Chromecast receiver-fetch path (spec R11). The dedicated
        // endpoint is gated on the resolved ServerCapabilities.castStreamUrls flag; a failure to reach
        // the Server degrades to "unsupported" so the resolver simply falls back to the reachable
        // playback path (R11.3) or treats the song as not castable (R11.4). Wired here now; the
        // ChromecastEngine (task 17.1) will consume it once the Cast SDK path is live.
        single {
            val systemInfoRepository = get<SystemInfoRepository>()
            val castStreamUrlRepository = get<CastStreamUrlRepository>()
            CastStreamUrlResolver(
                castStreamUrlsSupported = {
                    when (val result = systemInfoRepository.getSystemInfo()) {
                        is TaskResult.Success -> result.data.resolvedCapabilities.castStreamUrls
                        is TaskResult.Failure -> false
                    }
                },
                fetchCastStreamUrl = { songId -> castStreamUrlRepository.getCastStreamUrl(songId) },
            )
        }
        // Local_Output_Device discovery seam (R6.1, R6.3, R12.2, R12.4) is now bound per-platform
        // in each `platformModule`, because discovery is genuinely platform-specific and Android
        // needs an application Context (for the Cast SDK / MediaRouter) while iOS needs none:
        //   - Android: `single<LocalDeviceProvider> { AndroidLocalDeviceDiscovery(androidContext()) }`
        //   - iOS:     `single<LocalDeviceProvider> { IosLocalDeviceDiscovery() }`
        // DevicePickerViewModel depends only on the LocalDeviceProvider interface, so it resolves
        // whichever concrete provider the active platform module contributes. EmptyLocalDeviceProvider
        // remains available for tests/fallbacks but is no longer bound here.

        // A long-lived application scope for the playback engines/coordinator status flows.
        // SupervisorJob keeps one engine's failure from cancelling the others.
        single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

        // Playback engines + coordinator (R7.1, R7.7, R14).
        //
        // The Chromecast engine is a platform expect/actual, constructed in `platformModule`
        // (Media3 CastPlayer on Android; a compiling stub on iOS until the GoogleCast cinterop is
        // configured). It is resolved here with getOrNull so the coordinator receives it where the
        // platform provides one and null otherwise (R9, R12.2). AirPlay is iOS-only (R8.5, R12.1):
        // the iOS platformModule binds an AirPlayEngine under the "airplay" qualifier while Android
        // binds none, so getOrNull yields the engine on iOS and null on Android.
        single { LocalPlaybackEngine(get(), get()) }
        single { ServerPlaybackEngine(get(), get(), get()) }
        single {
            PlaybackCoordinator(
                local = get<LocalPlaybackEngine>(),
                chromecast = getOrNull<ChromecastEngine>(),
                airplay = getOrNull<PlaybackEngine>(named("airplay")),
                serverPlayback = get<ServerPlaybackEngine>(),
                scope = get(),
                playbackModeReporter = get(),
            )
        }

        viewModel { MainViewModel(get(), get()) }
        viewModel { LoginViewModel(get(), get(), get()) }
        viewModel { MiniPlayerViewModel(get()) }
        viewModel { PlayerViewModel(get(), get(), get(), get()) }
        viewModel { WebViewModel(get(), get(), get()) }
        viewModel { MusicServiceViewModel(get(), get()) }
        viewModel { DevicePickerViewModel(get(), get(), get(), get()) }
    }

private fun provideHttpClient(
    json: Json,
    preferencesDataSource: PreferencesDataSource,
    encryptedDataSource: EncryptedDataSource,
): HttpClient =
    HttpClient {
        expectSuccess = true

        install(UserAgent) {
            agent = BLACK_CANDY_USER_AGENT
        }

        install(ContentNegotiation) {
            json(json)
        }

        install(Auth) {
            bearer {
                loadTokens {
                    encryptedDataSource.getApiToken()?.let {
                        BearerTokens(it, "")
                    }
                }
            }
        }

        defaultRequest {
            val serverAddress =
                runBlocking {
                    preferencesDataSource.getServerAddress()
                }

            url("$serverAddress/")
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

                    else -> {
                        throw ApiException(
                            code = null,
                            message = exception.message,
                        )
                    }
                }
            }
        }
    }

@OptIn(ExperimentalSerializationApi::class)
private fun provideJson() =
    Json {
        isLenient = true
        ignoreUnknownKeys = true
        namingStrategy = JsonNamingStrategy.SnakeCase
        useAlternativeNames = false
    }
