package org.blackcandy.shared.di

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import okhttp3.OkHttpClient
import org.blackcandy.shared.data.EncryptedDataSource
import org.blackcandy.shared.data.LocalDeviceProvider
import org.blackcandy.shared.media.AndroidLocalDeviceDiscovery
import org.blackcandy.shared.media.ChromecastEngine
import org.blackcandy.shared.media.MusicServiceController
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual val platformModule =
    module {
        single { provideEncryptedSharedPreferences(androidContext()) }
        single(named("PreferencesDataStore")) { provideDataStore(androidContext()) }
        single { provideDataSourceFactory(get()) }

        single { EncryptedDataSource(get()) }
        single { MusicServiceController(androidContext()) }

        // Local_Output_Device discovery (spec R6.1, R6.3, R6.4, R6.6, R12.2, R12.4). Android
        // discovers Chromecast receivers via the Cast SDK MediaRouter; a device without Google Play
        // services degrades to unsupported/empty. DevicePickerViewModel resolves this by interface.
        single<LocalDeviceProvider> { AndroidLocalDeviceDiscovery(androidContext()) }

        // Chromecast client-cast engine (spec R9). Android supports Chromecast (R12.2), so the real
        // Media3 CastPlayer-backed engine is bound here; the coordinator picks it up via getOrNull
        // in the common module. It follows the CastContext initialized in MainApplication.
        single { ChromecastEngine(androidContext(), get(), get()) }
    }

private const val DATASTORE_PREFERENCES_NAME = "user_preferences"
private const val ENCRYPTED_SHARED_PREFERENCES_FILE_NAME = "encrypted_preferences.txt"

private fun provideDataStore(appContext: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        produceFile = { appContext.preferencesDataStoreFile(DATASTORE_PREFERENCES_NAME) },
    )

private fun provideEncryptedSharedPreferences(appContext: Context): SharedPreferences =
    try {
        createEncryptedSharedPreferences(appContext)
    } catch (e: Exception) {
        appContext
            .getSharedPreferences(ENCRYPTED_SHARED_PREFERENCES_FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        appContext.deleteSharedPreferences(ENCRYPTED_SHARED_PREFERENCES_FILE_NAME)
        createEncryptedSharedPreferences(appContext)
    }

private fun createEncryptedSharedPreferences(appContext: Context): SharedPreferences =
    EncryptedSharedPreferences.create(
        ENCRYPTED_SHARED_PREFERENCES_FILE_NAME,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        appContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

@androidx.annotation.OptIn(UnstableApi::class)
private fun provideDataSourceFactory(encryptedDataSource: EncryptedDataSource): DataSource.Factory {
    val httpClient = OkHttpClient().newBuilder().build()

    return DataSource.Factory {
        val apiToken = encryptedDataSource.getApiToken()
        val dataSource =
            OkHttpDataSource.Factory(httpClient).createDataSource()

        dataSource.setRequestProperty("Authorization", "Token $apiToken")

        dataSource
    }
}
