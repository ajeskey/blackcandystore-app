import SwiftUI
import sharedKit

struct PlayerActions: View {
    let playbackMode: PlaybackMode
    let isFavorited: Bool
    let onModeSwitchButtonClicked: (() -> Void)
    let onFavoriteButtonClicked: (() -> Void)
    let onPlaylistButtonClicked: (() -> Void)?
    var activeRouting: PlaybackRouting = .local
    var isDevicePickerAvailable: Bool = false
    var onDevicePickerButtonClicked: (() -> Void)?

    var body: some View {
        HStack {
            // Repeat/shuffle control — independent of the Playback_Routing selection (R16.6).
            Button(
                action: {
                    onModeSwitchButtonClicked()
                },
                label: {
                    playbackModeIcon(playbackMode)
                }
            )
            .padding(CustomStyle.spacing(.narrow))
            .background(playbackMode == .noRepeat ? .clear : .accentColor)
            .cornerRadius(CustomStyle.cornerRadius(.medium))

            // Device_Picker entry point — shown only when a server reports output devices or the
            // platform can client-cast (R16.1).
            if isDevicePickerAvailable, let onDevicePickerButtonClicked {
                Spacer()

                Button(
                    action: {
                        onDevicePickerButtonClicked()
                    },
                    label: {
                        Image(systemName: activeRouting == .local ? "airplayaudio" : "airplayaudio.circle.fill")
                            .tint(activeRouting == .local ? .primary : .accentColor)
                    }
                )
                .padding(CustomStyle.spacing(.narrow))
            }

            Spacer()

            Button(
                action: {
                    onFavoriteButtonClicked()
                },
                label: {
                    if isFavorited {
                        Image(systemName: "heart.fill")
                            .tint(.red)
                    } else {
                        Image(systemName: "heart")
                            .tint(.primary)
                    }
                }
            )
            .padding(CustomStyle.spacing(.narrow))

            if let onPlaylistButtonClicked {
                Spacer()

                Button(
                    action: {
                        onPlaylistButtonClicked()
                    },
                    label: {
                        Image(systemName: "list.bullet")
                    }
                )
                .padding(CustomStyle.spacing(.narrow))
                .cornerRadius(CustomStyle.cornerRadius(.medium))
            }
        }
    }

    func playbackModeIcon(_ mode: PlaybackMode) -> some View {
        let iconName = switch mode {
        case .noRepeat:
            "repeat"
        case .repeat:
            "repeat"
        case .repeatOne:
            "repeat.1"
        case .shuffle:
            "shuffle"
        }

        return Image(systemName: iconName)
            .tint(mode == .noRepeat ? .primary : .white)
    }
}
