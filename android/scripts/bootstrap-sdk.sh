#!/usr/bin/env bash
set -eu

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

if [ "${SNIPTUBE_DISK_GUARDED:-}" != "1" ]; then
    exec python3 "$ROOT/scripts/disk_guard.py" --path "$ROOT" -- \
        env SNIPTUBE_DISK_GUARDED=1 bash "$0" "$@"
fi

if ! command -v java >/dev/null || ! command -v unzip >/dev/null; then
    exec nix develop "path:$ROOT/nix" --command bash "$0" "$@"
fi

SDK_ROOT="$ROOT/.toolchain/android-sdk"
export ANDROID_USER_HOME="$ROOT/.toolchain/android-user"
TOOLS_VERSION="12.0"
TOOLS_ARCHIVE="commandlinetools-linux-11076708_latest.zip"
TOOLS_SHA1="d313adb7aedccf6cf0cfca51ec180f0059f5f8f8"
TOOLS_URL="https://dl.google.com/android/repository/$TOOLS_ARCHIVE"
TOOLS_DIR="$SDK_ROOT/cmdline-tools/$TOOLS_VERSION"

mkdir -p "$ROOT/.toolchain" "$SDK_ROOT/cmdline-tools"

if [ ! -x "$TOOLS_DIR/bin/sdkmanager" ]; then
    archive="$ROOT/.toolchain/$TOOLS_ARCHIVE"
    if [ ! -f "$archive" ]; then
        curl --fail --location --retry 3 --output "$archive" "$TOOLS_URL"
    fi
    printf '%s  %s\n' "$TOOLS_SHA1" "$archive" | sha1sum --check --status || {
        rm -f "$archive"
        echo "Android command-line tools checksum mismatch" >&2
        exit 1
    }
    extract_dir="$ROOT/.toolchain/cmdline-tools-extract"
    rm -rf "$extract_dir"
    mkdir -p "$extract_dir"
    unzip -q "$archive" -d "$extract_dir"
    rm -rf "$TOOLS_DIR"
    mv "$extract_dir/cmdline-tools" "$TOOLS_DIR"
    rmdir "$extract_dir"
fi

SDKMANAGER="$TOOLS_DIR/bin/sdkmanager"
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
    "build-tools;35.0.0" \
    "platforms;android-35" \
    "platform-tools"

printf 'sdk.dir=%s\n' "$SDK_ROOT" > "$ROOT/local.properties"
echo "Android SDK ready at $SDK_ROOT"
