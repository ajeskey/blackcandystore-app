import SwiftUI
import sharedKit

struct FullPlayer: View {
    let isCompactMode: Bool
    let currentSong: Song?
    let currentPosition: Double
    let playbackMode: PlaybackMode
    let isPlaying: Bool
    let isLoading: Bool
    let onPreviousButtonClicked: (() -> Void)
    let onNextButtonClicked: (() -> Void)
    let onPlayButtonClicked: (() -> Void)
    let onPauseButtonClicked: (() -> Void)
    let onPlaylistButtonClicked: (() -> Void)?
    let onModeSwitchButtonClicked: (() -> Void)
    let onFavoriteButtonClicked: (() -> Void)
    let onSeek: ((Double) -> Void)
    var activeRouting: PlaybackRouting = .local
    var activeDeviceName: String?
    var isDevicePickerAvailable: Bool = false
    var onDevicePickerButtonClicked: (() -> Void)?

    var body: some View {
        VStack {
            if isCompactMode {
                Spacer()
            }

            PlayerArt(imageURL: currentSong?.artworkUrl)
                .padding(.bottom, CustomStyle.spacing(.extraWide))

            PlayerInfo(currentSong: currentSong)

            RoutingIndicator(activeRouting: activeRouting, deviceName: activeDeviceName)

            PlayerControl(
                isPlaying: isPlaying,
                isLoading: isLoading,
                currentPosition: currentPosition,
                duration: currentSong?.duration ?? 0,
                onPreviousButtonClicked: onPreviousButtonClicked,
                onNextButtonClicked: onNextButtonClicked,
                onPlayButtonClicked: onPlayButtonClicked,
                onPauseButtonClicked: onPauseButtonClicked,
                onSeek: onSeek
            )
            .padding(.horizontal, CustomStyle.spacing(.large))

            if isCompactMode {
                Spacer()
            }

            PlayerActions(
                playbackMode: playbackMode,
                isFavorited: currentSong?.isFavorited ?? false,
                onModeSwitchButtonClicked: onModeSwitchButtonClicked,
                onFavoriteButtonClicked: onFavoriteButtonClicked,
                onPlaylistButtonClicked: onPlaylistButtonClicked,
                activeRouting: activeRouting,
                isDevicePickerAvailable: isDevicePickerAvailable,
                onDevicePickerButtonClicked: onDevicePickerButtonClicked
            )
            .padding(.vertical, CustomStyle.spacing(.medium))
            .padding(.horizontal, CustomStyle.spacing(.large))
        }
    }
}

/// A compact indication of the active `PlaybackRouting` and the target device name (spec R16.2,
/// R16.3). Shown when audio is routed to an external device; hidden for local playback so the
/// default player looks unchanged. Reflects state changes reactively (R16.5).
struct RoutingIndicator: View {
    let activeRouting: PlaybackRouting
    let deviceName: String?

    var body: some View {
        if activeRouting != .local, let deviceName {
            HStack(spacing: CustomStyle.spacing(.narrow)) {
                Image(systemName: "airplayaudio")
                Text(routingLabel(deviceName))
                    .font(.caption)
                    .lineLimit(1)
            }
            .foregroundColor(.accentColor)
            .padding(.top, CustomStyle.spacing(.narrow))
        }
    }

    private func routingLabel(_ name: String) -> String {
        switch activeRouting {
        case .clientCast:
            return String(localized: "text.routing_client_cast \(name)")
        case .serverPlayback:
            return String(localized: "text.routing_server_playback \(name)")
        default:
            return name
        }
    }
}
