#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_DIR="$ROOT_DIR/rust"
ANDROID_DIR="$ROOT_DIR/android"

TARGET_TRIPLE="x86_64-linux-android"
ANDROID_ABI="x86_64"
RUST_PROFILE_DIR="debug"
RUST_LIB_NAME="libcove.so"
ANDROID_LIB_NAME="libcoveffi.so"
APK_DEBUG_REL_PATH="app/build/outputs/apk/dev/debug/app-dev-debug.apk"
ANDROID_PACKAGE_NAME="org.bitcoinppl.cove.dev"
MAIN_ACTIVITY_CLASS="org.bitcoinppl.cove.MainActivity"
GRADLE_ABI_FILTER="$ANDROID_ABI"

REGEN_BINDINGS=0
SKIP_RUST=0
SKIP_INSTALL=0
NO_LAUNCH=0
KEEP_DEBUG_SYMBOLS=0

usage() {
  cat <<'EOF'
Fast Android dev loop (x86_64 emulator only).

Usage:
  ./scripts/android-dev-x86.sh [options]

Options:
  --regen-bindings  Regenerate UniFFI Kotlin bindings (use for Rust API changes)
  --skip-rust       Skip Rust JNI library build/copy
  --skip-install    Build APK only (do not install)
  --no-launch       Do not launch app after install
  --keep-symbols    Keep Rust debug symbols (larger .so / APK)
  -h, --help        Show this help

Default behavior:
  1. Build Rust for x86_64-linux-android
  2. Copy lib to android/app/src/main/jniLibs/x86_64/libcoveffi.so
  3. Build + install devDebug APK with ABI filter x86_64
  4. Resolve and launch the package's launcher activity
EOF
}

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "error: required command not found: $cmd" >&2
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --regen-bindings)
      REGEN_BINDINGS=1
      ;;
    --skip-rust)
      SKIP_RUST=1
      ;;
    --skip-install)
      SKIP_INSTALL=1
      ;;
    --no-launch)
      NO_LAUNCH=1
      ;;
    --keep-symbols)
      KEEP_DEBUG_SYMBOLS=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
  shift
done

require_cmd cargo
require_cmd rustup
require_cmd cargo-ndk
require_cmd adb

start_ts="$(date +%s)"

if [[ "$SKIP_RUST" -eq 0 ]]; then
  echo "==> Building Rust JNI lib for $TARGET_TRIPLE"
  pushd "$RUST_DIR" >/dev/null
  export CFLAGS="-D__ANDROID_MIN_SDK_VERSION__=21"

  rustup target add "$TARGET_TRIPLE" >/dev/null
  if [[ "$KEEP_DEBUG_SYMBOLS" -eq 0 ]]; then
    CARGO_PROFILE_DEV_DEBUG=0 cargo ndk --target "$TARGET_TRIPLE" build
  else
    cargo ndk --target "$TARGET_TRIPLE" build
  fi

  src_lib="$RUST_DIR/target/$TARGET_TRIPLE/$RUST_PROFILE_DIR/$RUST_LIB_NAME"
  dst_dir="$ANDROID_DIR/app/src/main/jniLibs/$ANDROID_ABI"
  dst_lib="$dst_dir/$ANDROID_LIB_NAME"

  if [[ ! -f "$src_lib" ]]; then
    echo "error: missing built library: $src_lib" >&2
    exit 1
  fi

  mkdir -p "$dst_dir"
  cp "$src_lib" "$dst_lib"

  if [[ "$KEEP_DEBUG_SYMBOLS" -eq 0 ]]; then
    if command -v llvm-strip >/dev/null 2>&1; then
      llvm-strip --strip-debug "$dst_lib"
      echo "==> Stripped debug symbols with llvm-strip"
    elif command -v strip >/dev/null 2>&1; then
      strip --strip-debug "$dst_lib"
      echo "==> Stripped debug symbols with strip"
    else
      echo "warning: no strip tool found; debug symbols were kept" >&2
    fi
  fi

  echo "==> Copied JNI library to $dst_lib"

  if [[ "$REGEN_BINDINGS" -eq 1 ]]; then
    echo "==> Regenerating UniFFI Kotlin bindings"
    rm -rf "$RUST_DIR/bindings/kotlin"
    mkdir -p "$RUST_DIR/bindings/kotlin"

    cargo run -p uniffi_cli -- generate "$src_lib" \
      --library \
      --language kotlin \
      --no-format \
      --out-dir "$RUST_DIR/bindings/kotlin"

    rm -rf "$ANDROID_DIR/app/src/main/java/org/bitcoinppl/cove_core"
    cp -R "$RUST_DIR/bindings/kotlin/." "$ANDROID_DIR/app/src/main/java/"
    echo "==> Copied generated bindings into Android sources"
  fi

  popd >/dev/null
fi

echo "==> Building Android devDebug APK (ABI filter: $GRADLE_ABI_FILTER)"
pushd "$ANDROID_DIR" >/dev/null

# avoid zipflinger-incremental holes after native library size changes
# by removing previously packaged APK artifacts before creating/installing devDebug
rm -f "$APK_DEBUG_REL_PATH"
find "app/build/intermediates/apk/dev/debug" -name "*.apk" -delete 2>/dev/null || true

if [[ "$SKIP_INSTALL" -eq 1 ]]; then
  ./gradlew :app:assembleDevDebug -PcoveAbiFilters="$GRADLE_ABI_FILTER"
else
  ./gradlew :app:installDevDebug -PcoveAbiFilters="$GRADLE_ABI_FILTER"
fi

popd >/dev/null

if [[ "$SKIP_INSTALL" -eq 0 && "$NO_LAUNCH" -eq 0 ]]; then
  echo "==> Launching app"
  resolved_component="$(
    adb shell cmd package resolve-activity --brief "$ANDROID_PACKAGE_NAME" 2>/dev/null \
      | tr -d '\r' \
      | awk 'NF{line=$0} END{print line}'
  )"

  if [[ "$resolved_component" != */* ]]; then
    resolved_component="${ANDROID_PACKAGE_NAME}/${MAIN_ACTIVITY_CLASS}"
  fi

  adb shell am start -n "$resolved_component" >/dev/null
fi

end_ts="$(date +%s)"
echo "==> Done in $((end_ts - start_ts))s"
