#!/usr/bin/env bash
set -eu

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# One guard owns the entire Nix/Gradle process tree, including bootstrap.
if [ "${SNIPTUBE_DISK_GUARDED:-}" != "1" ]; then
    exec python3 "$ROOT/scripts/disk_guard.py" --path "$ROOT" -- \
        env SNIPTUBE_DISK_GUARDED=1 bash "$0" "$@"
fi

if ! command -v java >/dev/null; then
    exec nix develop "path:$ROOT/nix" --command bash "$0" "$@"
fi

export ANDROID_HOME="$ROOT/.toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$ROOT/.toolchain/android-user"
export GRADLE_USER_HOME="$ROOT/.gradle"
# Development-only signing identity. Unlike SDK caches, this must survive reboots.
if [ ! -f "$ROOT/.signing/debug.keystore" ]; then
    mkdir -p "$ROOT/.signing"
    keytool -genkeypair -keystore "$ROOT/.signing/debug.keystore" \
        -storepass android -keypass android -alias androiddebugkey \
        -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000
fi
"$ROOT/gradlew" -p "$ROOT" "$@"
# Keep a completed artifact outside app/build so clean/recovery cannot erase it.
python3 "$ROOT/scripts/preserve_apk.py"
