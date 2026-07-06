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

Build the iOS app and upload it to TestFlight

Auth uses an App Store Connect API key. Provide these env vars (see .env.example):

  ASC_KEY_ID, ASC_ISSUER_ID, and either ASC_KEY_CONTENT (base64 or raw .p8) or ASC_KEY_FILEPATH.

Optionally set BUILD_NUMBER to override the build number (defaults to the project value).

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
