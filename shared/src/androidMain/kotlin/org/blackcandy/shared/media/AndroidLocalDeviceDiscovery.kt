package org.blackcandy.shared.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.suspendCancellableCoroutine
import org.blackcandy.shared.data.LocalDeviceProvider
import org.blackcandy.shared.models.OutputDevice
import org.blackcandy.shared.models.OutputDeviceProtocol
import kotlin.coroutines.resume

/**
 * Android Chromecast discovery of Local_Output_Devices (spec R6.1, R6.3, R6.4, R6.6, R12.2, R12.4).
 *
 * Android offers **Chromecast only** for `client_cast`; it never offers AirPlay (R12.1). Discovery
 * is driven by the Google Cast SDK: the [CastContext] (initialized at app startup in
 * `MainApplication`) provides the Cast `MediaRouteSelector`, and the AndroidX `MediaRouter`
 * enumerates the currently-reachable routes that match it. That live snapshot is mapped to
 * [DiscoveredLocalDevice]s (protocol =
 * [org.blackcandy.shared.models.OutputDeviceProtocol.CHROMECAST]) and classified through the shared
 * [LocalDeviceClassifier]. Because each call returns the current snapshot, a receiver that has
 * dropped off the network is simply absent (R6.4).
 *
 * This is one of the two concrete [LocalDeviceProvider] implementations that replaced the former
 * `expect`/`actual class LocalDeviceDiscovery`: Android needs an application [Context] to reach the
 * Cast SDK while iOS needs none, so rather than forcing a matching `expect`/`actual` constructor the
 * two platforms each bind their own concrete provider in `platformModule`
 * ([org.blackcandy.shared.viewmodels.DevicePickerViewModel] depends only on the [LocalDeviceProvider]
 * interface, so it is unaffected).
 *
 * ## Play services safety
 * The Cast SDK hard-requires Google Play services. Every Cast/MediaRouter call is guarded so a
 * device without (or with an outdated) Play services — many emulators, de-Googled ROMs — degrades to
 * [isClientCastSupported] `false` and an empty device set (the Device_Picker then omits the local
 * section per R12.4) rather than crashing.
 *
 * ## Threading
 * `MediaRouter` must be created and driven on the app's main looper, so route discovery is hopped
 * onto a main-looper [Handler] (dependency-free — it does not require
 * `kotlinx-coroutines-android`/`Dispatchers.Main`).
 *
 * @param context application context used to obtain the shared [CastContext] and `MediaRouter`.
 */
class AndroidLocalDeviceDiscovery(
    private val context: Context,
) : LocalDeviceProvider {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Whether this device can perform Chromecast `client_cast` at all (R12.2, R12.4): Google Play
     * services must be available and the Cast framework must be reachable. Guarded so a missing
     * Play services degrades to `false` instead of throwing.
     */
    override val isClientCastSupported: Boolean
        get() =
            try {
                val available =
                    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                available == ConnectionResult.SUCCESS &&
                    runCatching { CastContext.getSharedInstance(context) }.getOrNull() != null
            } catch (e: Exception) {
                false
            }

    /**
     * Snapshot the currently-reachable Cast receivers and classify them (R6.1, R6.4, R6.6). Returns
     * an empty list when the Cast framework is unavailable, so casting simply degrades to no local
     * devices rather than failing (R6.5, R12.4).
     */
    override suspend fun getLocalDevices(): List<OutputDevice> = LocalDeviceClassifier.classify(snapshotCastRoutes())

    /**
     * Register a short active-scan on the Cast `MediaRouteSelector`, snapshot the matching routes,
     * then unregister. All work runs on the main looper and every SDK call is guarded so a device
     * without Play services resumes with an empty list.
     */
    private suspend fun snapshotCastRoutes(): List<DiscoveredLocalDevice> =
        suspendCancellableCoroutine { continuation ->
            mainHandler.post {
                val router: MediaRouter
                val selector: MediaRouteSelector
                try {
                    val castContext = CastContext.getSharedInstance(context)
                    selector = castContext?.mergedSelector ?: MediaRouteSelector.EMPTY
                    router = MediaRouter.getInstance(context)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(emptyList())
                    return@post
                }

                // A no-op callback with active scan asks the platform to actively look for Cast
                // receivers; without an active callback `router.routes` may omit not-yet-seen ones.
                val callback = object : MediaRouter.Callback() {}

                try {
                    router.addCallback(
                        selector,
                        callback,
                        MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
                    )
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(emptyList())
                    return@post
                }

                mainHandler.postDelayed({
                    val devices =
                        try {
                            router.routes
                                .filter { route -> !route.isDefault && route.matchesSelector(selector) }
                                .map { route ->
                                    DiscoveredLocalDevice(
                                        id = route.id,
                                        name = route.name,
                                        protocol = OutputDeviceProtocol.CHROMECAST,
                                    )
                                }
                        } catch (e: Exception) {
                            emptyList()
                        }

                    runCatching { router.removeCallback(callback) }

                    if (continuation.isActive) continuation.resume(devices)
                }, DISCOVERY_WINDOW_MS)

                continuation.invokeOnCancellation {
                    mainHandler.post { runCatching { router.removeCallback(callback) } }
                }
            }
        }

    private companion object {
        /** How long to actively scan before snapshotting the reachable Cast routes. */
        private const val DISCOVERY_WINDOW_MS = 1_500L
    }
}
