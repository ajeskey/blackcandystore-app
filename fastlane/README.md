fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## iOS

### ios frame

```sh
[bundle exec] fastlane ios frame
```

Frame iOS screenshots with device frames and titles

### ios beta

```sh
[bundle exec] fastlane ios beta
```

Build the iOS app and upload it to TestFlight.

Authentication uses an App Store Connect API key. Copy `.env.example` to `.env` (or set the
variables as CI secrets) and provide `ASC_KEY_ID`, `ASC_ISSUER_ID`, and the `.p8` key via
`ASC_KEY_CONTENT` (inline) or `ASC_KEY_FILEPATH` (path). A local, gitignored
`iosApp/iosApp/Configuration/Config.xcconfig` must also exist with `BUNDLE_ID`, `TEAM_ID`,
and `APP_NAME` (see `Config.xcconfig.example`). Optionally set `BUILD_NUMBER` to override the
build number for the upload.

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
