#!/bin/sh

# android
export ANDROID_HOME="/opt/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.1.12297006"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"

# upload signing (used to sign AAB before uploading to Google Play)
export COVE_KEYSTORE_PATH="../cove-release.keystore"
export COVE_KEYSTORE_PASSWORD="changeit"
export COVE_KEY_ALIAS="cove"
export COVE_KEY_PASSWORD="changeit"


cd android
./gradlew  :app:assembleRelease
# adb install app/build/outputs/apk/dev/debug/app-dev-debug.apk