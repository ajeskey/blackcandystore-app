package org.blackcandy.android.compose.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.blackcandy.android.R
import org.blackcandy.shared.media.PlaybackError
import org.blackcandy.shared.media.PlaybackRouting
import org.blackcandy.shared.media.PlaybackTarget
import org.blackcandy.shared.models.OutputDevice
import org.blackcandy.shared.models.OutputDeviceProtocol
import org.blackcandy.shared.viewmodels.DevicePickerUiState
import org.blackcandy.shared.viewmodels.DeviceSection

/**
 * The Device_Picker surface (spec R6, R16.1-R16.3). Presented as a Material bottom sheet from the
 * player's cast entry point. Lists the two device namespaces separately (R6.1, R6.7) plus the
 * "this device" local-playback option, and marks the currently active target so the user can see
 * where audio is going (R16.2, R16.3). Selecting an item drives the [PlaybackRouting] switch
 * through the ViewModel callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerSheet(
    state: DevicePickerUiState,
    onDismiss: () -> Unit,
    onServerDeviceSelected: (OutputDevice) -> Unit,
    onLocalDeviceSelected: (OutputDevice) -> Unit,
    onLocalPlaybackSelected: () -> Unit,
    onSetVolume: (Double) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.device_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )

                // A refresh in progress shows a small spinner next to the title (R6.5).
                if (state.isLoading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Surface cast/routing errors reported by the coordinator without blocking the sheet
            // (R6.5, R10.5-R10.7, R14.5-R14.6). The AirPlay auth re-prompt is handled separately in
            // PlayerScreen, so it isn't duplicated here.
            state.error?.let { error ->
                if (error !is PlaybackError.Authentication) {
                    DeviceErrorBanner(error)
                }
            }

            // A volume control for the active cast/server device (R10.4, R14.2). Local playback
            // uses the system volume, so it's only shown while routed externally.
            if (state.activeRouting != PlaybackRouting.LOCAL) {
                DeviceVolumeControl(
                    volume = state.volume,
                    onSetVolume = onSetVolume,
                )
            }

            LazyColumn {
                // Local App_Player playback option (R7.7).
                item {
                    DeviceRow(
                        title = stringResource(R.string.this_device),
                        subtitle = stringResource(R.string.local_playback_description),
                        iconResId = R.drawable.baseline_play_arrow_24,
                        isActive = state.activeRouting == PlaybackRouting.LOCAL,
                        onClick = onLocalPlaybackSelected,
                    )
                }

                // Local_Output_Device namespace (client_cast) — only when the platform supports it.
                if (state.localSection.isSupported) {
                    item {
                        SectionHeader(stringResource(R.string.local_devices_section))
                    }
                    deviceSectionItems(
                        section = state.localSection,
                        activeDeviceId = activeDeviceIdFor(state.activeTarget),
                        onDeviceSelected = onLocalDeviceSelected,
                    )
                }

                // Server_Output_Device namespace (server_playback) — only when the server reports it.
                if (state.serverSection.isSupported) {
                    item {
                        SectionHeader(stringResource(R.string.server_devices_section))
                    }
                    deviceSectionItems(
                        section = state.serverSection,
                        activeDeviceId = activeDeviceIdFor(state.activeTarget),
                        onDeviceSelected = onServerDeviceSelected,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.deviceSectionItems(
    section: DeviceSection,
    activeDeviceId: String?,
    onDeviceSelected: (OutputDevice) -> Unit,
) {
    // Empty-but-supported is a valid "no devices" state, not an error (R6.5).
    if (section.isEmpty) {
        item {
            Text(
                text = stringResource(R.string.no_devices_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    } else {
        items(section.devices, key = { it.id }) { device ->
            DeviceRow(
                title = device.name,
                subtitle = if (device.requiresPassword) stringResource(R.string.requires_password) else null,
                iconResId = deviceIconRes(device.protocol),
                isActive = device.id == activeDeviceId,
                onClick = { onDeviceSelected(device) },
            )
        }
    }
}

/**
 * A non-blocking error banner shown at the top of the sheet when the coordinator reports a
 * cast/routing failure (R6.5, R10.5-R10.7, R14.5-R14.6). Each [PlaybackError] subtype maps to a
 * readable message.
 */
@Composable
private fun DeviceErrorBanner(error: PlaybackError) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.baseline_warning_24),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = deviceErrorMessage(error),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun deviceErrorMessage(error: PlaybackError): String =
    when (error) {
        PlaybackError.NotReachable -> stringResource(R.string.device_error_not_reachable)
        PlaybackError.DeviceDisconnected -> stringResource(R.string.device_error_disconnected)
        PlaybackError.Timeout -> stringResource(R.string.device_error_timeout)
        PlaybackError.SongUnavailable -> stringResource(R.string.device_error_song_unavailable)
        is PlaybackError.Authentication -> error.message ?: stringResource(R.string.device_error_authentication)
        is PlaybackError.Message -> error.message.ifEmpty { stringResource(R.string.device_error_generic) }
    }

/**
 * A volume slider bound to the active cast/server device (R10.4, R14.2). Reports changes back
 * through [onSetVolume]; the [volume] value comes from the coordinator status.
 */
@Composable
private fun DeviceVolumeControl(
    volume: Double,
    onSetVolume: (Double) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.baseline_volume_up_24),
            contentDescription = stringResource(R.string.device_volume),
            modifier = Modifier.size(24.dp),
        )
        Slider(
            value = volume.toFloat().coerceIn(0f, 1f),
            onValueChange = { onSetVolume(it.toDouble()) },
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun DeviceRow(
    title: String,
    subtitle: String?,
    iconResId: Int,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Icon(
            painter = painterResource(iconResId),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (isActive) {
            Icon(
                painter = painterResource(R.drawable.baseline_cast_connected_24),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun deviceIconRes(protocol: OutputDeviceProtocol): Int =
    when (protocol) {
        OutputDeviceProtocol.AIRPLAY -> R.drawable.baseline_airplay_24
        OutputDeviceProtocol.CHROMECAST -> R.drawable.baseline_cast_24
    }

/**
 * The id of the device the active [PlaybackTarget] points at, used to mark the active row
 * (R16.2). Null when playback is local or no device is selected.
 */
internal fun activeDeviceIdFor(target: PlaybackTarget?): String? =
    when (target) {
        is PlaybackTarget.LocalCastDevice -> target.device.id
        is PlaybackTarget.ServerDevice -> target.devices.firstOrNull()?.id
        PlaybackTarget.LocalDevice, null -> null
    }

/**
 * The human-readable name of the active target device, or null when playback is local (R16.2,
 * R16.3).
 */
internal fun activeDeviceNameFor(target: PlaybackTarget?): String? =
    when (target) {
        is PlaybackTarget.LocalCastDevice -> target.device.name
        is PlaybackTarget.ServerDevice -> target.devices.joinToString { it.name }.ifEmpty { null }
        PlaybackTarget.LocalDevice, null -> null
    }
