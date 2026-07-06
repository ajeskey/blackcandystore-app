import SwiftUI
import LNPopupUI
import sharedKit

struct PlayerScreen: View {
    private let viewModel: PlayerViewModel = KoinHelper().getPlayerViewModel()
    private let devicePickerViewModel: DevicePickerViewModel = KoinHelper().getDevicePickerViewModel()

    @Environment(\.horizontalSizeClass) var horizontalSizeClass
    @State private var path = NavigationPath()
    @State private var albumImage: UIImage?
    @State private var currentSong: Song?
    @State private var isPlaying = false
    @State private var devicePickerState: DevicePickerUiState?
    @State private var showDevicePicker = false

    // Password collection for a protected AirPlay Server_Output_Device (R14.7). `passwordDevice`
    // drives the prompt (shown when non-nil); `passwordAttemptDevice` is the last device a password
    // was submitted for, kept so a server auth rejection can re-open the prompt.
    @State private var passwordDevice: OutputDevice?
    @State private var passwordAttemptDevice: OutputDevice?
    @State private var passwordInput = ""
    @State private var passwordErrorMessage: String?

    // Opening the picker refreshes both device namespaces before presenting them (R6.4).
    private func openDevicePicker() {
        devicePickerViewModel.refreshDevices()
        showDevicePicker = true
    }

    // A protected AirPlay device collects a password before entering server_playback (R14.7); any
    // other device is selected directly.
    private func selectServerDevice(_ device: OutputDevice) {
        showDevicePicker = false
        if DevicePickerViewModelKt.requiresDevicePassword(device: device) {
            passwordErrorMessage = nil
            passwordInput = ""
            passwordDevice = device
        } else {
            devicePickerViewModel.selectDevice(device: device)
        }
    }

    private func submitPassword(for device: OutputDevice) {
        passwordAttemptDevice = device
        passwordErrorMessage = nil
        devicePickerViewModel.selectDeviceWithPassword(device: device, password: passwordInput)
        passwordDevice = nil
        passwordInput = ""
    }

    private func cancelPasswordPrompt() {
        passwordDevice = nil
        passwordAttemptDevice = nil
        passwordErrorMessage = nil
        passwordInput = ""
    }

    // Re-open the password prompt when the Server rejects a missing/incorrect password (R14.7).
    private func handlePickerState(_ state: DevicePickerUiState) {
        devicePickerState = state
        guard let attempted = passwordAttemptDevice, let error = state.error else { return }
        switch onEnum(of: error) {
        case .authentication(let auth):
            passwordErrorMessage = auth.message ?? String(localized: "label.device_password_incorrect")
            passwordDevice = attempted
        default:
            break
        }
    }

    var body: some View {
        Observing(viewModel.uiState) { uiState in
            if horizontalSizeClass == .regular {
                HStack(spacing: CustomStyle.spacing(.ultraWide)) {
                    FullPlayer(
                        isCompactMode: false,
                        currentSong: uiState.musicState.currentSong,
                        currentPosition: uiState.currentPosition,
                        playbackMode: uiState.musicState.playbackMode,
                        isPlaying: uiState.musicState.isPlaying,
                        isLoading: uiState.musicState.isLoading,
                        onPreviousButtonClicked: { viewModel.previous() },
                        onNextButtonClicked: { viewModel.next() },
                        onPlayButtonClicked: { viewModel.play() },
                        onPauseButtonClicked: { viewModel.pause() },
                        onPlaylistButtonClicked: nil,
                        onModeSwitchButtonClicked: { viewModel.nextMode() },
                        onFavoriteButtonClicked: { viewModel.toggleFavorite() },
                        onSeek: { viewModel.seekToRatio(ratio: $0) },
                        activeRouting: uiState.routing,
                        activeDeviceName: DeviceRouting.activeDeviceName(uiState.target),
                        isDevicePickerAvailable: devicePickerState?.isPickerAvailable ?? false,
                        onDevicePickerButtonClicked: { openDevicePicker() }
                    )

                    VStack {
                        HStack {
                            Text("label.tracks(\(uiState.musicState.playlist.count))")
                            Spacer()
                            EditButton()
                        }
                        .padding(CustomStyle.spacing(.medium))
                        .cornerRadius(CustomStyle.cornerRadius(.large))

                        PlayerPlaylist(
                            playlist: uiState.musicState.playlist,
                            currentSong: uiState.musicState.currentSong,
                            onItemClicked: { viewModel.playOn(songId: $0) },
                            onItemSweepToDismiss: { viewModel.removeSongFromPlaylist(songId: $0) },
                            onItemMoved: { from, to in viewModel.moveSongInPlaylist(from: Int32(from), to: Int32(to)) }
                        )

                    }

                    .frame(maxHeight: CustomStyle.playlistMaxHeight)
                }
            } else {
                NavigationStack(path: $path) {
                    FullPlayer(
                        isCompactMode: true,
                        currentSong: uiState.musicState.currentSong,
                        currentPosition: uiState.currentPosition,
                        playbackMode: uiState.musicState.playbackMode,
                        isPlaying: uiState.musicState.isPlaying,
                        isLoading: uiState.musicState.isLoading,
                        onPreviousButtonClicked: { viewModel.previous() },
                        onNextButtonClicked: { viewModel.next() },
                        onPlayButtonClicked: { viewModel.play() },
                        onPauseButtonClicked: { viewModel.pause() },
                        onPlaylistButtonClicked: { path.append(Route.playlist) },
                        onModeSwitchButtonClicked: { viewModel.nextMode() },
                        onFavoriteButtonClicked: { viewModel.toggleFavorite() },
                        onSeek: { viewModel.seekToRatio(ratio: $0) },
                        activeRouting: uiState.routing,
                        activeDeviceName: DeviceRouting.activeDeviceName(uiState.target),
                        isDevicePickerAvailable: devicePickerState?.isPickerAvailable ?? false,
                        onDevicePickerButtonClicked: { openDevicePicker() }
                    )
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .playlist:
                            PlayerPlaylist(
                                playlist: uiState.musicState.playlist,
                                currentSong: uiState.musicState.currentSong,
                                onItemClicked: { viewModel.playOn(songId: $0) },
                                onItemSweepToDismiss: { viewModel.removeSongFromPlaylist(songId: $0) },
                                onItemMoved: { from, to in viewModel.moveSongInPlaylist(from: Int32(from), to: Int32(to)) }
                            )
                        }
                    }
                }
            }
        }
        .popupTitle(currentSong?.name ?? String(localized: "label.not_playing"))
        .popupImage(albumImage != nil ? Image(uiImage: albumImage!) : nil)
        .popupBarButtons {
            ToolbarItemGroup(placement: .popupBar) {
                if horizontalSizeClass == .regular {
                    Button(
                        action: {
                            viewModel.previous()
                        },
                        label: {
                            Image(systemName: "backward.fill")
                                .tint(.primary)
                        }
                    )
                }
                Button(
                    action: {
                        if isPlaying {
                            viewModel.pause()
                        } else {
                            viewModel.play()
                        }
                    },
                    label: {
                        if isPlaying {
                            Image(systemName: "pause.fill")
                                .tint(.primary)
                        } else {
                            Image(systemName: "play.fill")
                                .tint(.primary)
                        }
                    }
                )

                Button(
                    action: {
                        viewModel.next()
                    },
                    label: {
                        Image(systemName: "forward.fill")
                            .tint(.primary)
                    }
                )
            }
        }
        .task(id: currentSong?.id) {
            guard let urlString = currentSong?.artworkUrlSmall,
                  let url = URL(string: urlString) else {
                albumImage = nil
                return
            }

            do {
                let (data, _) = try await URLSession.shared.data(from: url)
                if let image = UIImage(data: data) {
                    albumImage = image
                }
            } catch {
                albumImage = nil
            }
        }
        .collect(flow: viewModel.uiState) { state in
            currentSong = state.musicState.currentSong
            isPlaying = state.musicState.isPlaying
        }
        .collect(flow: devicePickerViewModel.uiState) { state in
            handlePickerState(state)
        }
        .sheet(isPresented: $showDevicePicker) {
            if let devicePickerState {
                DevicePicker(
                    state: devicePickerState,
                    devicePickerViewModel: devicePickerViewModel,
                    onServerDeviceSelected: { device in
                        selectServerDevice(device)
                    },
                    onLocalDeviceSelected: { device in
                        devicePickerViewModel.selectDevice(device: device)
                        showDevicePicker = false
                    },
                    onLocalPlaybackSelected: {
                        devicePickerViewModel.selectLocalPlayback()
                        showDevicePicker = false
                    },
                    onAirPlaySelected: {
                        // Enter client_cast via the AirPlay engine so routing/state stay
                        // consistent; the system AVRoutePickerView (presented by DevicePicker)
                        // handles the actual device + password selection (R8.3). Keep the sheet
                        // open so the hosted route picker can present over it.
                        devicePickerViewModel.selectDevice(device: DeviceRouting.airPlayLocalDevice())
                    },
                    onDismiss: { showDevicePicker = false }
                )
            }
        }
        .alert(
            "label.device_password_title",
            isPresented: Binding(
                get: { passwordDevice != nil },
                set: { presented in if !presented { passwordDevice = nil } }
            ),
            presenting: passwordDevice
        ) { device in
            SecureField("label.device_password_label", text: $passwordInput)
            Button("label.device_password_confirm") {
                submitPassword(for: device)
            }
            Button("label.cancel", role: .cancel) {
                cancelPasswordPrompt()
            }
        } message: { device in
            if let passwordErrorMessage {
                Text(passwordErrorMessage)
            } else {
                Text(device.name)
            }
        }
    }
}

extension PlayerScreen {
    enum Route: Hashable {
        case playlist
    }
}
