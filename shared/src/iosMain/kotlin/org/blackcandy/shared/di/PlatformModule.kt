package org.blackcandy.shared.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import org.blackcandy.shared.data.EncryptedDataSource
import org.blackcandy.shared.data.LocalDeviceProvider
import org.blackcandy.shared.media.AirPlayEngine
import org.blackcandy.shared.media.ChromecastEngine
import org.blackcandy.shared.media.IosLocalDeviceDiscovery
import org.blackcandy.shared.media.MusicServiceController
import org.blackcandy.shared.media.PlaybackEngine
import org.koin.core.qualifier.named
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

actual val platformModule =
    module {
        single(named("PreferencesDataStore")) { provideDataStore() }
        single { EncryptedDataSource() }
        single { MusicServiceController(get()) }

        // Local_Output_Device discovery (spec R6.1, R6.3, R6.4, R6.6, R12.1, R12.2, R12.4). iOS
        // always supports AirPlay client-cast, so this reports isClientCastSupported = true and the
        // picker's local section appears (the AVRoutePickerView entry lives there in the UI wave);
        // the Chromecast list stays empty until a GoogleCast cinterop is configured.
        single<LocalDeviceProvider> { IosLocalDeviceDiscovery() }

        // Chromecast client-cast engine (spec R9). iOS supports Chromecast (R12.2), but the
        // GoogleCast SDK is not yet exposed to Kotlin/Native via cinterop, so this binds the
        // compiling stub actual (see ChromecastEngine iosMain). The coordinator picks it up via
        // getOrNull in the common module. TODO(GCK cinterop): the real GCK-backed engine will use
        // the same binding once a GoogleCast cinterop is configured for iosMain.
        single { ChromecastEngine(get(), get()) }

        // AirPlay client-cast engine (spec R8) — iOS only (R8.5, R12.1). It reuses the local
        // MusicServiceController's AVPlayer as the audio source and exposes the AVRoutePickerView
        // presentation seam for the SwiftUI layer. The concrete singleton is also bound as a
        // PlaybackEngine under the "airplay" qualifier so the common PlaybackCoordinator can receive
        // it via getOrNull without referencing the iOS-only type. Android registers no such binding,
        // so the coordinator gets null there.
        single { AirPlayEngine(get(), get()) }
        single<PlaybackEngine>(named("airplay")) { get<AirPlayEngine>() }
    }

private const val DATASTORE_PREFERENCES_NAME = "user.preferences_pb"

@OptIn(ExperimentalForeignApi::class)
private fun provideDataStore(): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            val documentDirectory: NSURL? =
                NSFileManager.defaultManager.URLForDirectory(
                    directory = NSDocumentDirectory,
                    inDomain = NSUserDomainMask,
                    appropriateForURL = null,
                    create = false,
                    error = null,
                )

            (requireNotNull(documentDirectory).path + "/$DATASTORE_PREFERENCES_NAME").toPath()
        },
    )
