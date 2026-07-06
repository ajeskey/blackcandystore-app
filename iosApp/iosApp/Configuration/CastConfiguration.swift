import Foundation

// MARK: - Chromecast client casting configuration (spec R9.1, R12.2)
//
// The GoogleCast SDK must be added to the Xcode project before this initialization becomes active.
// It is NOT yet wired into `iosApp.xcodeproj` (adding a Swift Package to the project file reliably
// requires Xcode). Until it is added, `canImport(GoogleCast)` is false and `CastConfiguration`
// compiles to a no-op, so the app keeps building. Task 17.1 (ChromecastEngine iOS actual) also
// depends on this SDK being present.
//
// ## How to add the GoogleCast SDK (one-time, in Xcode)
// Swift Package Manager (preferred):
//   1. File ▸ Add Package Dependencies…
//   2. Enter the Google Cast SDK Swift Package URL:
//        https://github.com/google-cast/google-cast-sdk-ios
//      (Use the latest 4.8.x release. If Google's SPM package is unavailable for the pinned
//       version, fall back to CocoaPods with `pod 'google-cast-sdk'` instead.)
//   3. Add the `GoogleCast` product to the `iosApp` target.
//   4. In `Info.plist` add the required Cast entries:
//        - `NSBonjourServices`:
//            `_googlecast._tcp`
//            `_<RECEIVER_APP_ID>._googlecast._tcp`
//        - `NSLocalNetworkUsageDescription` explaining local-network device discovery.
//   5. Rebuild — `canImport(GoogleCast)` becomes true and the code below activates automatically.
//
// ## Receiver application id — MUST be configured before shipping
// This uses the Default Media Receiver ("CC1AD845") to match the Android side
// (`CastOptionsProvider.RECEIVER_APPLICATION_ID`). Replace it with the project's own registered
// receiver id from https://cast.google.com/publish before release and keep both platforms in sync.

#if canImport(GoogleCast)
import GoogleCast

enum CastConfiguration {
    // TODO(cast): replace the default media receiver with the project's production receiver id.
    static let receiverApplicationID = kGCKDefaultMediaReceiverApplicationID

    /// Initializes the shared `GCKCastContext`. Safe to call once at launch.
    static func configure() {
        let criteria = GCKDiscoveryCriteria(applicationID: receiverApplicationID)
        let options = GCKCastOptions(discoveryCriteria: criteria)
        options.physicalVolumeButtonsWillControlDeviceVolume = true
        GCKCastContext.setSharedInstanceWith(options)
    }
}
#else
enum CastConfiguration {
    /// No-op until the GoogleCast SDK is added to the Xcode project (see file header).
    static func configure() {}
}
#endif
