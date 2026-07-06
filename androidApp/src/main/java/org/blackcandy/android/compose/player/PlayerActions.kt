package org.blackcandy.android.compose.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.blackcandy.android.R
import org.blackcandy.shared.media.PlaybackMode
import org.blackcandy.shared.media.PlaybackRouting

@Composable
fun PlayerActions(
    modifier: Modifier = Modifier,
    playbackMode: PlaybackMode,
    isFavorited: Boolean,
    onModeSwitchButtonClicked: () -> Unit,
    onFavoriteButtonClicked: () -> Unit,
    onPlaylistButtonClicked: (() -> Unit)? = null,
    isDevicePickerAvailable: Boolean = false,
    activeRouting: PlaybackRouting = PlaybackRouting.LOCAL,
    onDevicePickerButtonClicked: (() -> Unit)? = null,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier =
            modifier
                .fillMaxWidth(),
    ) {
        // Repeat/shuffle control — independent of the Playback_Routing selection (R16.6).
        FilledIconToggleButton(
            checked = playbackMode != PlaybackMode.NO_REPEAT,
            onCheckedChange = { _ -> onModeSwitchButtonClicked() },
        ) {
            PlaybackModeIcon(playbackMode)
        }

        // Device_Picker entry point — shown only when a server reports output devices or the
        // platform can client-cast (R16.1).
        if (isDevicePickerAvailable && onDevicePickerButtonClicked != null) {
            IconButton(
                onClick = onDevicePickerButtonClicked,
            ) {
                val isRoutedExternally = activeRouting != PlaybackRouting.LOCAL
                Icon(
                    painter =
                        painterResource(
                            if (isRoutedExternally) {
                                R.drawable.baseline_cast_connected_24
                            } else {
                                R.drawable.baseline_cast_24
                            },
                        ),
                    contentDescription = stringResource(R.string.device_picker),
                    tint = if (isRoutedExternally) MaterialTheme.colorScheme.primary else Color.Unspecified,
                )
            }
        }

        IconButton(
            onClick = onFavoriteButtonClicked,
        ) {
            if (isFavorited) {
                Icon(
                    painter = painterResource(R.drawable.baseline_favorite_24),
                    contentDescription = stringResource(R.string.favorited),
                    tint = Color.Red,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.baseline_favorite_border_24),
                    contentDescription = stringResource(R.string.unfavorited),
                )
            }
        }

        if (onPlaylistButtonClicked != null) {
            IconButton(
                onClick = onPlaylistButtonClicked,
            ) {
                Icon(
                    painter = painterResource(R.drawable.baseline_format_list_bulleted_24),
                    contentDescription = stringResource(R.string.playlist),
                )
            }
        }
    }
}

/**
 * A compact indication of the active [PlaybackRouting] and the target device name (spec R16.2,
 * R16.3). Shown in the player when audio is routed to an external device; hidden for local
 * playback so the default player looks unchanged. Reflects state changes reactively within one
 * emission (R16.5).
 */
@Composable
fun RoutingIndicator(
    modifier: Modifier = Modifier,
    activeRouting: PlaybackRouting,
    deviceName: String?,
) {
    if (activeRouting == PlaybackRouting.LOCAL || deviceName == null) {
        return
    }

    val label =
        when (activeRouting) {
            PlaybackRouting.CLIENT_CAST -> stringResource(R.string.routing_client_cast, deviceName)
            PlaybackRouting.SERVER_PLAYBACK -> stringResource(R.string.routing_server_playback, deviceName)
            PlaybackRouting.LOCAL -> return
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(R.drawable.baseline_cast_connected_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(0.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PlaybackModeIcon(playbackMode: PlaybackMode) {
    val iconResourceId =
        when (playbackMode) {
            PlaybackMode.NO_REPEAT -> R.drawable.baseline_repeat_24
            PlaybackMode.REPEAT -> R.drawable.baseline_repeat_24
            PlaybackMode.REPEAT_ONE -> R.drawable.baseline_repeat_one_24
            PlaybackMode.SHUFFLE -> R.drawable.baseline_shuffle_24
        }

    val titleResourceId =
        when (playbackMode) {
            PlaybackMode.NO_REPEAT -> R.string.no_repeat_mode
            PlaybackMode.REPEAT -> R.string.repeat_mode
            PlaybackMode.REPEAT_ONE -> R.string.repeat_one_mode
            PlaybackMode.SHUFFLE -> R.string.shuffle_mode
        }

    Icon(
        painter = painterResource(iconResourceId),
        contentDescription = stringResource(titleResourceId),
    )
}
