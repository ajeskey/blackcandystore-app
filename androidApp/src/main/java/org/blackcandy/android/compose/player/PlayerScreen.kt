package org.blackcandy.android.compose.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.blackcandy.android.R
import org.blackcandy.android.utils.SnackbarUtil.Companion.ShowSnackbar
import org.blackcandy.shared.media.PlaybackError
import org.blackcandy.shared.models.OutputDevice
import org.blackcandy.shared.viewmodels.DevicePickerUiState
import org.blackcandy.shared.viewmodels.DevicePickerViewModel
import org.blackcandy.shared.viewmodels.PlayerViewModel
import org.blackcandy.shared.viewmodels.requiresDevicePassword
import org.koin.androidx.compose.koinViewModel

enum class PlayerRoute {
    FullPlayer,
    Playlist,
}

@Composable
fun PlayerScreen(
    navController: NavHostController = rememberNavController(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    viewModel: PlayerViewModel = koinViewModel(),
    devicePickerViewModel: DevicePickerViewModel = koinViewModel(),
    windowSizeClass: WindowSizeClass,
) {
    val uiState by viewModel.uiState.collectAsState()
    val devicePickerState by devicePickerViewModel.uiState.collectAsState()
    var showDevicePicker by remember { mutableStateOf(false) }

    // Password collection for a protected AirPlay Server_Output_Device (R14.7). [passwordDevice]
    // is the device whose password dialog is currently shown; [passwordAttemptDevice] is the last
    // device we submitted a password for, kept so an auth failure can re-open the prompt.
    var passwordDevice by remember { mutableStateOf<OutputDevice?>(null) }
    var passwordAttemptDevice by remember { mutableStateOf<OutputDevice?>(null) }
    var passwordErrorMessage by remember { mutableStateOf<String?>(null) }

    // When the Server rejects a missing/incorrect password it surfaces a PlaybackError.Authentication
    // on the picker state; re-open the prompt so the user can re-enter it (R14.7).
    val authErrorFallback = stringResource(R.string.device_password_incorrect)
    val pickerError = devicePickerState.error
    LaunchedEffect(pickerError) {
        val attempted = passwordAttemptDevice
        if (pickerError is PlaybackError.Authentication && attempted != null) {
            passwordErrorMessage = pickerError.message ?: authErrorFallback
            passwordDevice = attempted
        }
    }

    val isWideLayout =
        windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact &&
            windowSizeClass.heightSizeClass != WindowHeightSizeClass.Compact

    // Opening the picker refreshes both device namespaces before presenting them (R6.4).
    val onDevicePickerButtonClicked = {
        devicePickerViewModel.refreshDevices()
        showDevicePicker = true
    }

    if (isWideLayout) {
        PlayerScreenWideLayout(
            snackbarHostState = snackbarHostState,
            viewModel = viewModel,
            windowSizeClass = windowSizeClass,
            uiState = uiState,
            devicePickerState = devicePickerState,
            onDevicePickerButtonClicked = onDevicePickerButtonClicked,
        )
    } else {
        PlayerScreenCompactLayout(
            navController = navController,
            snackbarHostState = snackbarHostState,
            viewModel = viewModel,
            windowSizeClass = windowSizeClass,
            uiState = uiState,
            devicePickerState = devicePickerState,
            onDevicePickerButtonClicked = onDevicePickerButtonClicked,
        )
    }

    if (showDevicePicker) {
        DevicePickerSheet(
            state = devicePickerState,
            onDismiss = { showDevicePicker = false },
            onServerDeviceSelected = { device ->
                // A protected AirPlay device collects a password before entering server_playback
                // (R14.7); any other device is selected directly.
                if (requiresDevicePassword(device)) {
                    passwordErrorMessage = null
                    passwordDevice = device
                } else {
                    devicePickerViewModel.selectDevice(device)
                }
                showDevicePicker = false
            },
            onLocalDeviceSelected = { device ->
                devicePickerViewModel.selectDevice(device)
                showDevicePicker = false
            },
            onLocalPlaybackSelected = {
                devicePickerViewModel.selectLocalPlayback()
                showDevicePicker = false
            },
            onSetVolume = { level -> devicePickerViewModel.setVolume(level) },
        )
    }

    passwordDevice?.let { device ->
        DevicePasswordDialog(
            deviceName = device.name,
            errorMessage = passwordErrorMessage,
            onDismiss = {
                passwordDevice = null
                passwordAttemptDevice = null
                passwordErrorMessage = null
            },
            onConfirm = { password ->
                passwordAttemptDevice = device
                passwordErrorMessage = null
                devicePickerViewModel.selectDeviceWithPassword(device, password)
                passwordDevice = null
            },
        )
    }
}

/**
 * Collects a device password for a protected AirPlay Server_Output_Device before entering
 * server_playback (spec R14.7). Re-shown with [errorMessage] set when the Server rejects the
 * password so the user can re-enter it.
 */
@Composable
private fun DevicePasswordDialog(
    deviceName: String,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_password_title, deviceName)) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.device_password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = errorMessage != null,
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty(),
            ) {
                Text(stringResource(R.string.device_password_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun PlayerScreenWideLayout(
    snackbarHostState: SnackbarHostState,
    viewModel: PlayerViewModel,
    windowSizeClass: WindowSizeClass,
    uiState: org.blackcandy.shared.viewmodels.PlayerUiState,
    devicePickerState: DevicePickerUiState,
    onDevicePickerButtonClicked: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Row(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_medium)),
        ) {
            FullPlayer(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                windowSizeClass = windowSizeClass,
                inWideLayout = true,
                currentSong = uiState.musicState.currentSong,
                isPlaying = uiState.musicState.isPlaying,
                isLoading = uiState.musicState.isLoading,
                currentPosition = uiState.currentPosition,
                playbackMode = uiState.musicState.playbackMode,
                onPreviousButtonClicked = { viewModel.previous() },
                onNextButtonClicked = { viewModel.next() },
                onPlayButtonClicked = { viewModel.play() },
                onPauseButtonClicked = { viewModel.pause() },
                onSeek = { viewModel.seekTo(it) },
                onModeSwitchButtonClicked = { viewModel.nextMode() },
                onFavoriteButtonClicked = { viewModel.toggleFavorite() },
                onPlaylistButtonClicked = null,
                activeRouting = uiState.routing,
                activeDeviceName = activeDeviceNameFor(uiState.target),
                isDevicePickerAvailable = devicePickerState.isPickerAvailable,
                onDevicePickerButtonClicked = onDevicePickerButtonClicked,
            )

            Column(
                modifier = Modifier.weight(1f),
            ) {
                PlaylistHeader(
                    tracksCount = uiState.musicState.playlist.size,
                    onClearAllButtonClicked = { viewModel.clearPlaylist() },
                )

                Playlist(
                    modifier =
                        Modifier
                            .heightIn(max = dimensionResource(R.dimen.playlist_max_height)),
                    playlist = uiState.musicState.playlist,
                    currentSong = uiState.musicState.currentSong,
                    onItemClicked = { songId -> viewModel.playOn(songId) },
                    onItemSweepToDismiss = { songId -> viewModel.removeSongFromPlaylist(songId) },
                    onItemMoved = { from, to -> viewModel.moveSongInPlaylist(from, to) },
                )
            }
        }

        uiState.alertMessage?.let { alertMessage ->
            ShowSnackbar(alertMessage, snackbarHostState) {
                viewModel.alertMessageShown()
            }
        }
    }
}

@Composable
fun PlayerScreenCompactLayout(
    navController: NavHostController,
    snackbarHostState: SnackbarHostState,
    viewModel: PlayerViewModel,
    windowSizeClass: WindowSizeClass,
    uiState: org.blackcandy.shared.viewmodels.PlayerUiState,
    devicePickerState: DevicePickerUiState,
    onDevicePickerButtonClicked: () -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()

    val currentRoute =
        PlayerRoute.valueOf(
            backStackEntry?.destination?.route ?: PlayerRoute.FullPlayer.name,
        )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (currentRoute == PlayerRoute.Playlist) {
                PlaylistAppBar(
                    canNavigateBack = navController.previousBackStackEntry != null,
                    navigateUp = { navController.navigateUp() },
                    onClearAllButtonClicked = { viewModel.clearPlaylist() },
                )
            }
        },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = PlayerRoute.FullPlayer.name,
            modifier =
                Modifier
                    .nestedScroll(rememberNestedScrollInteropConnection()),
        ) {
            composable(route = PlayerRoute.FullPlayer.name) {
                FullPlayer(
                    modifier =
                        Modifier
                            .padding(innerPadding)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    windowSizeClass = windowSizeClass,
                    inCompactHeight =
                        windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact &&
                            windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact,
                    currentSong = uiState.musicState.currentSong,
                    isPlaying = uiState.musicState.isPlaying,
                    isLoading = uiState.musicState.isLoading,
                    currentPosition = uiState.currentPosition,
                    playbackMode = uiState.musicState.playbackMode,
                    onPreviousButtonClicked = { viewModel.previous() },
                    onNextButtonClicked = { viewModel.next() },
                    onPlayButtonClicked = { viewModel.play() },
                    onPauseButtonClicked = { viewModel.pause() },
                    onSeek = { viewModel.seekTo(it) },
                    onModeSwitchButtonClicked = { viewModel.nextMode() },
                    onFavoriteButtonClicked = { viewModel.toggleFavorite() },
                    onPlaylistButtonClicked = { navController.navigate(PlayerRoute.Playlist.name) },
                    activeRouting = uiState.routing,
                    activeDeviceName = activeDeviceNameFor(uiState.target),
                    isDevicePickerAvailable = devicePickerState.isPickerAvailable,
                    onDevicePickerButtonClicked = onDevicePickerButtonClicked,
                )
            }

            composable(route = PlayerRoute.Playlist.name) {
                Playlist(
                    modifier =
                        Modifier
                            .padding(innerPadding),
                    playlist = uiState.musicState.playlist,
                    currentSong = uiState.musicState.currentSong,
                    onItemClicked = { songId -> viewModel.playOn(songId) },
                    onItemSweepToDismiss = { songId -> viewModel.removeSongFromPlaylist(songId) },
                    onItemMoved = { from, to -> viewModel.moveSongInPlaylist(from, to) },
                )
            }
        }

        uiState.alertMessage?.let { alertMessage ->
            ShowSnackbar(alertMessage, snackbarHostState) {
                viewModel.alertMessageShown()
            }
        }
    }
}

@Composable
fun PlaylistHeader(
    tracksCount: Int,
    onClearAllButtonClicked: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
    ) {
        Text(
            text = pluralStringResource(R.plurals.tracks_count, tracksCount, tracksCount),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )

        IconButton(onClick = onClearAllButtonClicked) {
            Icon(
                painter = painterResource(R.drawable.baseline_clear_all_24),
                contentDescription = stringResource(R.string.clear_all),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistAppBar(
    canNavigateBack: Boolean,
    navigateUp: () -> Unit,
    onClearAllButtonClicked: () -> Unit,
) {
    TopAppBar(
        title = { Text(text = stringResource(R.string.playing_queue)) },
        navigationIcon = {
            if (canNavigateBack) {
                IconButton(onClick = navigateUp) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_button_description),
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onClearAllButtonClicked) {
                Icon(
                    painter = painterResource(R.drawable.baseline_clear_all_24),
                    contentDescription = stringResource(R.string.clear_all),
                )
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
            ),
    )
}
