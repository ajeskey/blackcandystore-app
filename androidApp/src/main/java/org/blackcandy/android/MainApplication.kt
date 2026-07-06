package org.blackcandy.android

import android.app.Application
import android.util.Log
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dev.hotwire.core.bridge.BridgeComponentFactory
import dev.hotwire.core.bridge.KotlinXJsonConverter
import dev.hotwire.core.config.Hotwire
import dev.hotwire.core.turbo.config.PathConfiguration
import dev.hotwire.navigation.config.defaultFragmentDestination
import dev.hotwire.navigation.config.registerBridgeComponents
import dev.hotwire.navigation.config.registerFragmentDestinations
import org.blackcandy.android.bridge.AccountComponent
import org.blackcandy.android.bridge.AlbumComponent
import org.blackcandy.android.bridge.FlashComponent
import org.blackcandy.android.bridge.LibraryComponent
import org.blackcandy.android.bridge.PlaylistComponent
import org.blackcandy.android.bridge.SearchComponent
import org.blackcandy.android.bridge.SongsComponent
import org.blackcandy.android.bridge.ThemeComponent
import org.blackcandy.android.fragments.web.WebBottomSheetFragment
import org.blackcandy.android.fragments.web.WebFragment
import org.blackcandy.android.fragments.web.WebHomeFragment
import org.blackcandy.android.fragments.web.WebLibraryFragment
import org.blackcandy.shared.di.appModule
import org.blackcandy.shared.utils.BLACK_CANDY_USER_AGENT
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        configureApp()
        initializeCast()

        startKoin {
            androidLogger()
            androidContext(this@MainApplication)
            modules(appModule())
        }
    }

    private fun configureApp() {
        Hotwire.config.applicationUserAgentPrefix = "${BLACK_CANDY_USER_AGENT};"
        Hotwire.config.jsonConverter = KotlinXJsonConverter()
        Hotwire.defaultFragmentDestination = WebFragment::class

        Hotwire.loadPathConfiguration(
            context = this,
            location =
                PathConfiguration.Location(
                    assetFilePath = "json/path-configuration.json",
                ),
        )

        Hotwire.registerFragmentDestinations(
            WebFragment::class,
            WebHomeFragment::class,
            WebLibraryFragment::class,
            WebBottomSheetFragment::class,
        )

        Hotwire.registerBridgeComponents(
            BridgeComponentFactory("account", ::AccountComponent),
            BridgeComponentFactory("search", ::SearchComponent),
            BridgeComponentFactory("album", ::AlbumComponent),
            BridgeComponentFactory("flash", ::FlashComponent),
            BridgeComponentFactory("library", ::LibraryComponent),
            BridgeComponentFactory("playlist", ::PlaylistComponent),
            BridgeComponentFactory("songs", ::SongsComponent),
            BridgeComponentFactory("theme", ::ThemeComponent),
        )
    }

    /**
     * Eagerly initialize the Cast framework so Chromecast discovery/casting is ready when the user
     * opens the Device_Picker (spec R9.1, R12.2). Initialization reads [CastOptionsProvider] via the
     * manifest metadata.
     *
     * This is guarded because the Cast SDK hard-requires Google Play services: on devices where it
     * is missing or out of date (many emulators, de-Googled ROMs), touching [CastContext] throws.
     * We check availability first and swallow any failure so the app still launches and works with
     * casting simply unavailable, rather than crashing on startup.
     */
    private fun initializeCast() {
        try {
            val availability =
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)

            if (availability != ConnectionResult.SUCCESS) {
                Log.i(TAG, "Google Play services unavailable ($availability); skipping Cast init.")
                return
            }

            // Initializes CastContext on a background executor using CastOptionsProvider.
            CastContext.getSharedInstance(this, Runnable::run)
        } catch (e: Exception) {
            // Casting is a non-critical enhancement; never let its setup block app launch.
            Log.w(TAG, "Cast initialization failed; casting will be unavailable.", e)
        }
    }

    companion object {
        private const val TAG = "MainApplication"
    }
}
