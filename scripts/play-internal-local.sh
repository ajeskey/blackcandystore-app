#!/usr/bin/env bash
#
# Build a signed Android App Bundle and upload it to the Google Play INTERNAL
# testing track ("dev mode") from your Mac.
#
# It reads the keystore password from AWS Secrets Manager (lifesavor-dev) at runtime.
# You must supply the upload keystore file, key alias/password, and a Play service
# account JSON yourself (these are not all in Secrets Manager) — see .env / .env.example.
#
# Prereqs (one-time):
#   - Fastlane installed (e.g. `brew install fastlane`), JDK 17, Android SDK.
#   - A valid `lifesavor-dev` AWS session: `aws sts get-caller-identity --profile lifesavor-dev`.
#   - The app already created in the Play Console as `io.blackcandystore.app`, with the
#     FIRST build uploaded manually (Google's API cannot create the app or accept the very
#     first upload for a brand-new package).
#   - A Play service account JSON with the "Release manager" permission.
#   - Env (in .env or exported): ANDROID_KEYSTORE_PATH, ANDROID_KEY_ALIAS,
#     ANDROID_KEY_PASSWORD, and GOOGLE_PLAY_JSON_KEY (path) or GOOGLE_PLAY_JSON_KEY_DATA.
#
set -euo pipefail

AWS_PROFILE="${AWS_PROFILE:-lifesavor-dev}"; export AWS_PROFILE
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$REPO_ROOT"

# Load .env if present (keystore/key/service-account values live here).
if [ -f .env ]; then set -a; . ./.env; set +a; fi

# JDK for the Gradle build.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
  if _jh="$(/usr/libexec/java_home -v 17 2>/dev/null)" && [ -x "$_jh/bin/java" ]; then
    export JAVA_HOME="$_jh"
  elif [ -x /opt/homebrew/opt/openjdk@17/bin/java ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17
  fi
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"

echo "==> Verifying AWS session ($AWS_PROFILE)"
aws sts get-caller-identity >/dev/null

echo "==> Fetching keystore password from Secrets Manager"
ANDROID_KEYSTORE_PASSWORD="$(aws secretsmanager get-secret-value --secret-id /lifesavor/dev/google-play-keystore-password --query SecretString --output text)"
export ANDROID_KEYSTORE_PASSWORD

: "${ANDROID_KEYSTORE_PATH:?set ANDROID_KEYSTORE_PATH (path to your upload keystore) in .env}"
: "${ANDROID_KEY_ALIAS:?set ANDROID_KEY_ALIAS in .env}"
: "${ANDROID_KEY_PASSWORD:?set ANDROID_KEY_PASSWORD in .env}"
if [ -z "${GOOGLE_PLAY_JSON_KEY:-}" ] && [ -z "${GOOGLE_PLAY_JSON_KEY_DATA:-}" ]; then
  echo "ERROR: provide GOOGLE_PLAY_JSON_KEY (path) or GOOGLE_PLAY_JSON_KEY_DATA (contents) in .env" >&2
  exit 1
fi

echo "==> Running Fastlane android internal (build AAB + upload to Play internal track)"
if command -v bundle >/dev/null 2>&1 && bundle check >/dev/null 2>&1; then
  bundle exec fastlane android internal
else
  fastlane android internal
fi

echo "==> Done. Check Play Console > Testing > Internal testing for the draft release."
