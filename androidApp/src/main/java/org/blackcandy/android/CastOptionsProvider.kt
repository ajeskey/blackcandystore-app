package org.blackcandy.android

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Cast framework configuration for Chromecast client casting (spec R9.1, R12.2).
 *
 * The Google Cast SDK discovers this class at runtime through the
 * `com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME` manifest metadata and uses the
 * returned [CastOptions] to initialize the [com.google.android.gms.cast.framework.CastContext].
 *
 * ## Receiver application id — MUST be configured before shipping
 * This currently uses [CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID] ("CC1AD845"),
 * Google's Default Media Receiver. That receiver plays plain media URLs and is fine for development,
 * but it is **not** a production receiver: it shows Google branding, is rate-limited, and cannot be
 * customized. Replace it with the project's own registered receiver application id from the
 * [Google Cast SDK Developer Console](https://cast.google.com/publish) before release, and keep the
 * iOS receiver id (see `CastConfiguration.swift`) in sync.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId(RECEIVER_APPLICATION_ID)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        // TODO(cast): replace the default media receiver with the project's production receiver id.
        val RECEIVER_APPLICATION_ID: String =
            CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    }
}
