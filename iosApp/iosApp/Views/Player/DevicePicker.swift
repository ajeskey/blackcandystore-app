import AVKit
import SwiftUI
import sharedKit

/// The Device_Picker surface (spec R6, R16.1-R16.3), presented as a SwiftUI sheet from the
/// player's cast entry point. Lists the two Output_Device namespaces separately (R6.1, R6.7)
/// plus the "this device" local-playback option, and marks the currently active target so the
/// user can see where audio is going (R16.2, R16.3). Selecting an item drives the
/// `PlaybackRouting` switch through the ViewModel callbacks.
///
/// AirPlay is special-cased in the local (client_cast) section: iOS does not expose an enumerable
/// list of named AirPlay devices, so instead of listing rows the picker shows a single "AirPlay"
/// entry that presents the system `AVRoutePickerView` (see `RoutePicker`). The system UI owns the
/// device selection and any password entry — the app never collects the AirPlay password (R8.3).
struct DevicePicker: View {
    let state: DevicePickerUiState
    let devicePickerViewModel: DevicePickerViewModel
    let onServerDeviceSelected: (OutputDevice) -> Void
    let onLocalDeviceSelected: (OutputDevice) -> Void
    let onLocalPlaybackSelected: () -> Void
    let onAirPlaySelected: () -> Void
    let onDismiss: () -> Void

    /// Flipped true to programmatically present the system route picker when the AirPlay entry is
    /// tapped. `RoutePicker` observes this and forwards a tap to the underlying `AVRoutePickerView`.
    @State private var triggerRoutePicker = false

    /// The synthetic Local_Output_Device that represents "AirPlay" in the client_cast section.
    /// iOS has no enumerable AirPlay list, so this stand-in drives the coordinator into
    /// `client_cast` via the AirPlay engine while the system `AVRoutePickerView` handles the actual
    /// device + password selection (R8.3, R12.1).
    private var airPlayDevice: OutputDevice { DeviceRouting.airPlayLocalDevice() }

    private var activeDeviceId: String? {
        DeviceRouting.activeDeviceId(state.activeTarget)
    }

    private var isExternalRouting: Bool {
        state.activeRouting != .local
    }

    var body: some View {
        NavigationStack {
            List {
                // Local App_Player playback option (R7.7).
                Section {
                    deviceRow(
                        title: String(localized: "label.this_device"),
                        subtitle: String(localized: "label.local_playback_description"),
                        systemImage: "iphone",
                        isActive: state.activeRouting == .local,
                        action: onLocalPlaybackSelected
                    )
                }

                // A refresh in progress is surfaced as a non-blocking loading row (R6.4).
                if state.isLoading {
                    Section {
                        HStack(spacing: CustomStyle.spacing(.medium)) {
                            ProgressView()
                            Text("label.searching_for_devices")
                                .foregroundColor(.secondary)
                        }
                    }
                }

                // Cast/routing errors from the coordinator status (not-reachable, disconnected,
                // timeout, …) shown as a non-blocking banner rather than dismissing the sheet.
                if let error = state.error {
                    Section {
                        errorRow(error)
                    }
                }

                // A single volume control that drives whichever routing is active (R10.4, R14.2).
                // Only shown for external routing so it never fights the phone's own volume.
                if isExternalRouting {
                    Section {
                        volumeSlider
                    }
                }

                // Local_Output_Device namespace (client_cast) — only when supported by platform.
                if state.localSection.isSupported {
                    Section(header: Text("label.local_devices_section")) {
                        localSectionContent
                    }
                }

                // Server_Output_Device namespace (server_playback) — only when the server reports it.
                if state.serverSection.isSupported {
                    Section(header: Text("label.server_devices_section")) {
                        deviceSection(state.serverSection, onSelected: onServerDeviceSelected)
                    }
                }
            }
            .navigationTitle("label.device_picker_title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(action: onDismiss) {
                        Text("label.done")
                    }
                }
            }
            // Hidden host for the system route picker; kept in the hierarchy so it can be
            // triggered programmatically from the AirPlay entry (R8.1, R8.3).
            .background(
                RoutePicker(trigger: $triggerRoutePicker)
                    .frame(width: 0, height: 0)
                    .accessibilityHidden(true)
            )
        }
    }

    // The client_cast section lists any real local devices (Chromecast; none on iOS yet) plus an
    // explicit AirPlay entry that opens the system route picker (R8.3, R12.1).
    @ViewBuilder
    private var localSectionContent: some View {
        airPlayRow

        // Any discovered local devices (e.g. Chromecast) still list normally.
        ForEach(state.localSection.devices, id: \.id) { device in
            deviceRow(
                title: device.name,
                subtitle: device.requiresPassword ? String(localized: "label.requires_password") : nil,
                systemImage: deviceSystemImage(device.`protocol`),
                isActive: device.id == activeDeviceId,
                action: { onLocalDeviceSelected(device) }
            )
        }
    }

    // Tapping AirPlay both routes the coordinator into client_cast (so routing state/indicator
    // stay consistent) and presents the system `AVRoutePickerView` for device + password (R8.3).
    private var airPlayRow: some View {
        deviceRow(
            title: String(localized: "label.airplay"),
            subtitle: String(localized: "label.airplay_description"),
            systemImage: "airplayaudio",
            isActive: activeDeviceId == airPlayDevice.id,
            action: {
                onAirPlaySelected()
                triggerRoutePicker = true
            }
        )
    }

    private var volumeSlider: some View {
        HStack(spacing: CustomStyle.spacing(.medium)) {
            Image(systemName: "speaker.fill")
                .foregroundColor(.secondary)
            Slider(
                value: Binding(
                    get: { state.volume },
                    set: { devicePickerViewModel.setVolume(level: $0) }
                ),
                in: 0.0 ... 1.0
            )
            .accessibilityLabel(Text("label.cast_volume"))
            Image(systemName: "speaker.wave.3.fill")
                .foregroundColor(.secondary)
        }
    }

    @ViewBuilder
    private func errorRow(_ error: PlaybackError) -> some View {
        HStack(spacing: CustomStyle.spacing(.medium)) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(.orange)
            Text(errorMessage(error))
                .font(.callout)
                .foregroundColor(.primary)
        }
    }

    // Map the shared `PlaybackError` sealed type to a user-facing message, matching how
    // PlayerScreen handles `.authentication` (R10.5, R10.6, R10.7).
    private func errorMessage(_ error: PlaybackError) -> String {
        switch onEnum(of: error) {
        case .notReachable:
            return String(localized: "text.cast_error_not_reachable")
        case .deviceDisconnected:
            return String(localized: "text.cast_error_device_disconnected")
        case .timeout:
            return String(localized: "text.cast_error_timeout")
        case .songUnavailable:
            return String(localized: "text.song_unavailable")
        case .authentication(let auth):
            return auth.message ?? String(localized: "label.device_password_incorrect")
        case .message(let msg):
            return msg.message
        }
    }

    @ViewBuilder
    private func deviceSection(_ section: DeviceSection, onSelected: @escaping (OutputDevice) -> Void) -> some View {
        // Empty-but-supported is a valid "no devices" state, not an error (R6.5).
        if section.isEmpty {
            Text("label.no_devices_found")
                .foregroundColor(.secondary)
        } else {
            ForEach(section.devices, id: \.id) { device in
                deviceRow(
                    title: device.name,
                    subtitle: device.requiresPassword ? String(localized: "label.requires_password") : nil,
                    systemImage: deviceSystemImage(device.`protocol`),
                    isActive: device.id == activeDeviceId,
                    action: { onSelected(device) }
                )
            }
        }
    }

    private func deviceRow(
        title: String,
        subtitle: String?,
        systemImage: String,
        isActive: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack {
                Image(systemName: systemImage)
                    .frame(width: 24)

                VStack(alignment: .leading) {
                    Text(title)
                        .foregroundColor(.primary)
                    if let subtitle {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }

                Spacer()

                if isActive {
                    Image(systemName: "checkmark")
                        .foregroundColor(.accentColor)
                }
            }
        }
    }

    private func deviceSystemImage(_ proto: OutputDeviceProtocol) -> String {
        switch proto {
        case .airplay:
            return "airplayaudio"
        case .chromecast:
            return "tv.and.hifispeaker.fill"
        }
    }
}

/// A SwiftUI wrapper over `AVKit.AVRoutePickerView` (spec R8.1, R8.3). The view itself is the
/// system AirPlay button; it is hosted invisibly and driven programmatically so the app's own
/// "AirPlay" entry can present the system route picker. When `trigger` flips true we forward a
/// touch-up to the picker's internal button, which presents the system device list and any
/// password prompt — the app never collects the AirPlay password itself (R8.3).
struct RoutePicker: UIViewRepresentable {
    @Binding var trigger: Bool

    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.prioritizesVideoDevices = false
        return picker
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {
        guard trigger else { return }

        // Present the system route picker by forwarding a tap to the underlying UIButton.
        for subview in uiView.subviews {
            if let button = subview as? UIButton {
                button.sendActions(for: .touchUpInside)
                break
            }
        }

        // Reset asynchronously so we don't mutate state during a view update.
        DispatchQueue.main.async {
            trigger = false
        }
    }
}

/// Pure mapping helpers from the shared `PlaybackTarget` to display values (spec R16.2, R16.3).
enum DeviceRouting {
    /// Stable id used for the synthetic AirPlay Local_Output_Device so the active-row check can
    /// match it back after the coordinator adopts it as the client_cast target.
    static let airPlayDeviceId = "airplay"

    /// Build the synthetic AirPlay Local_Output_Device that the client_cast section offers. It has
    /// no password (the system route picker collects any password itself, R8.3) and a LOCAL origin
    /// so it maps to `PlaybackTarget.LocalCastDevice` → the AirPlay engine (R7.2, R12.1).
    static func airPlayLocalDevice() -> OutputDevice {
        OutputDevice(
            id: airPlayDeviceId,
            name: "AirPlay",
            protocol: .airplay,
            requiresPassword: false,
            origin: .local
        )
    }

    /// The id of the device the active target points at, used to mark the active row. Nil when
    /// playback is local or no device is selected.
    static func activeDeviceId(_ target: PlaybackTarget?) -> String? {
        guard let target else { return nil }
        switch onEnum(of: target) {
        case .localCastDevice(let t):
            return t.device.id
        case .serverDevice(let t):
            return t.devices.first?.id
        case .localDevice:
            return nil
        }
    }

    /// The human-readable name of the active target device, or nil when playback is local.
    static func activeDeviceName(_ target: PlaybackTarget?) -> String? {
        guard let target else { return nil }
        switch onEnum(of: target) {
        case .localCastDevice(let t):
            return t.device.name
        case .serverDevice(let t):
            let names = t.devices.map { $0.name }.joined(separator: ", ")
            return names.isEmpty ? nil : names
        case .localDevice:
            return nil
        }
    }
}
