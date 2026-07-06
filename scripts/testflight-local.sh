#!/usr/bin/env bash
#
# Build the iOS app and upload it to TestFlight from your Mac.
#
# This pulls the App Store Connect API key from AWS Secrets Manager (lifesavor-dev)
# at runtime, hands it to the Fastlane `beta` lane, and shreds the local key file
# afterwards. Run this on your own machine (not in the agent sandbox) — signing keys
# must live in your login keychain and Xcode toolchain.
#
# Prereqs (one-time):
#   - Ruby 3.x + Bundler, then `bundle install` in the repo root (Fastlane).
#   - Xcode installed and signed in to the Apple ID for the target team.
#   - A valid `lifesavor-dev` AWS session: `aws sts get-caller-identity --profile lifesavor-dev`.
#   - iosApp/iosApp/Configuration/Config.xcconfig has the correct BUNDLE_ID + TEAM_ID.
#   - First App Store distribution: open the project in Xcode once so automatic signing
#     can create the distribution certificate in your keychain (then this script works
#     unattended on subsequent runs).
#
set -euo pipefail

AWS_PROFILE="${AWS_PROFILE:-lifesavor-dev}"
export AWS_PROFILE
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# The Xcode "Run Script" phase that builds the KMP shared framework runs Gradle, which
# needs a valid JAVA_HOME. Pick one so the archive doesn't inherit a stale/invalid value
# (e.g. an Android Studio JBR path that no longer exists).
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
  if _jh="$(/usr/libexec/java_home -v 17 2>/dev/null)" && [ -x "$_jh/bin/java" ]; then
    export JAVA_HOME="$_jh"
  elif [ -x /opt/homebrew/opt/openjdk@17/bin/java ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17
  fi
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
echo "==> Using JAVA_HOME=${JAVA_HOME:-<default>}"

echo "==> Verifying AWS session ($AWS_PROFILE)"
aws sts get-caller-identity >/dev/null

TMP_KEY="$(mktemp -t asc_key).p8"
cleanup() { rm -f "$TMP_KEY"; }
trap cleanup EXIT

echo "==> Fetching App Store Connect credentials from Secrets Manager"
aws secretsmanager get-secret-value --secret-id /lifesavor/dev/apple-app-store-connect-api-key   --query SecretString --output text > "$TMP_KEY"
ASC_KEY_ID="$(aws secretsmanager get-secret-value   --secret-id /lifesavor/dev/apple-key-id                        --query SecretString --output text)"
ASC_ISSUER_ID="$(aws secretsmanager get-secret-value --secret-id /lifesavor/dev/apple-app-store-connect-issuer-id --query SecretString --output text)"

export ASC_KEY_ID ASC_ISSUER_ID
export ASC_KEY_FILEPATH="$TMP_KEY"
# Bundle id is read by Fastlane from the Appfile/Config; override here if you prefer.
export BUNDLE_ID="${BUNDLE_ID:-$(grep '^BUNDLE_ID' iosApp/iosApp/Configuration/Config.xcconfig | sed 's/BUNDLE_ID *= *//')}"

echo "==> Running Fastlane beta lane (build + upload to TestFlight)"
# Prefer a Bundler-managed Fastlane if it's set up; otherwise use a standalone
# (e.g. Homebrew) install.
if command -v bundle >/dev/null 2>&1 && bundle check >/dev/null 2>&1; then
  bundle exec fastlane ios beta
else
  fastlane ios beta
fi

echo "==> Done. Check App Store Connect ▸ TestFlight for the processing build."
