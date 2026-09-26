# Sniptube Android — implementation handoff (26 September 2026)

## Delivered

- Native Kotlin/Compose companion with configurable Sniptube server, YouTube search and server-library browse, individual and bulk selection, one durable server-acquisition → phone-sync intent, separate server/device progress, retry and Wi-Fi-gated Media3 transfer.
- Downloads and device-local Collections screens: bulk queue and collection assignment, pause/resume/retry, local-only removal after collection membership ends, always-offline collection intent, known viewing-time totals and scoped Sync now with cellular confirmation. Background server reconciliation is bounded; phone sync waits for a legal service start when necessary.
- In-app Media3 offline playback from the persistent cache with **no HTTP upstream**, saved position, fullscreen and optional cached local VTT tracks/thumbnails. Missing media becomes retryable; source changes, server-source 404, partial resume, Wi-Fi loss and removal are handled without treating incomplete bytes as ready.
- Backend change: a database-ready video whose source file is missing appears failed in list/detail responses; posting its URL starts a repair download rather than returning `already_exists`.
- 26 September visual pass: followed the wiki's native Android app playbook with PWA-style Catppuccin/Mauve surfaces, full-width rounded 16:9 thumbnails, bottom-pinned bulk selection and image-led collections. Ready copies use locally cached artwork only. Playback opens in landscape fullscreen and retains Android navigation gestures. The v0.4 update removes the theme selector and redundant top skip controls, leaving Media3's accessible controls and cumulative left/right ten-second tap gestures.
- Player chrome: the title and Back button track Media3 controller visibility, then hide on the native controls' three-second timeout. The Android system Back gesture remains active while the overlay is hidden.
- Version 0.2.0 (build 2) adds an About screen reading packaged `app/src/main/res/raw/changelog.md`, always-offline collection intent with known viewing-time totals, explicit collection Sync now on cellular after a known-MB/unknown-size warning, and a Room-level ban on removing copies still in collections. YouTube and Downloads Search hide their nonessential summary/selection/navigation chrome while the keyboard is visible.
- Version 0.3.0 (build 3) removes the hardcoded personal server default, asks for a URL on first launch, preserves prior device settings and adds a logo splash plus an external-browser button for the configured web UI. A configured URL is saved even when the server is temporarily unreachable. The current public-facing source/docs contain no tailnet hostname or credentials matching the targeted scan; ignored private operational documents still hold infrastructure notes.
- Version 0.4.0 (build 4) adds durable viewed state for videos played beyond 90% or manually marked, grayscale collection artwork, 48dp icon actions, saved list offsets, and animated per-video sync progress with honest unknown-size indicators. Settings gains a free-space-aware offline media budget and confirmed clear-all-local-files action. Explicit cleanup keeps membership and playback history but prevents automatic collection reacquisition until Sync now.

## Verification completed

| Check | Result |
| --- | --- |
| `flock /tmp/opencode/sniptube-gradle.lock timeout --signal=TERM --kill-after=5s 180s android/scripts/gradle.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --max-workers=1 --no-daemon` | Passed: **77** JVM tests, lint, debug assembly and instrumentation-source compilation. |
| `flock /tmp/opencode/sniptube-gradle.lock timeout --signal=TERM --kill-after=5s 85s android/scripts/gradle.sh :app:testDebugUnitTest --tests 'com.sniptube.android.data.playback.OfflinePlaybackTest' --max-workers=1 --no-daemon` | Passed: **10** focused playback tests after a later test-only fixture addition. |
| From `api/`: `UV_PROJECT_ENVIRONMENT=/tmp/opencode/sniptube-api-venv uv run --group dev python -m pytest tests -q` | **100 passed**. No live API/worker restart. |
| `python3 -m unittest discover -s android/scripts -p 'test_build_safety.py' -v` | **5 passed**. |
| `git diff --check` | Passed for tracked changes. |
| `flock /tmp/opencode/sniptube-gradle.lock timeout --signal=TERM --kill-after=5s 205s android/scripts/gradle.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon` | After the design/player changes: **80 passed**, APK assembled and lint completed (0 errors, 6 pre-existing unused-resource warnings). |
| Same bounded Android test/lint/assemble command after the auto-hide follow-up | **80 passed**, lint completed, APK assembled in 2m 51s. UI controller visibility still needs phone acceptance. |
| `flock /tmp/opencode/sniptube-gradle.lock timeout --signal=TERM --kill-after=5s 245s android/scripts/gradle.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --max-workers=1 --no-daemon` | **84 Android JVM tests passed**, lint completed (0 errors, six existing unused-resource warnings), debug APK assembled and instrumentation source compiled in 2m 38s. |
| `android/.toolchain/android-sdk/build-tools/35.0.0/aapt dump badging android/artifacts/last-successful-debug.apk` | Current APK confirms package `com.sniptube.android`, versionCode **4**, versionName **0.4.0**, min API 26 and target API 35. |
| Bounded v0.4 full Android unit suite then lint and debug assembly | **92 JVM tests passed**. First lint run found an unmarked Media3 API call; after adding its opt-in, lint passed with **0 errors, 6 old unused-resource warnings** and APK assembled. Instrumented source still compiled in prior v0.3 build; no emulator/device test run. |
| `git grep` of tracked public files and targeted search of public-facing Android source/docs | No credential/private-key pattern or tailnet hostname found in working-tree public files. This is a targeted scan, not a claim that every historical artifact/share can be revoked by source edits. |

Automated fixtures cover actual Robolectric DownloadService dispatch → Media3 DownloadIndex/cache → Room publication, three queued items, pause/resume, in-flight removal, source-representation guards, server acquisition/retry, bulk/collections persistence, and cache-only reads/misses. These are not physical-device proof.

## APK

- Local debug build: `android/artifacts/last-successful-debug.apk` (also `android/app/build/outputs/apk/debug/app-debug.apk`)
- Size: **22,055,623 bytes**
- SHA-256: **`284791d85b1b81afc4c28d98328df0e6adf4e43f6c98bb55a0cf097b4fefed42`** (version 0.4.0, build 4)
- APK handoff links are distributed separately. This public repository excludes generated APKs and private server settings; obtain an APK from CI artifacts or compile locally.
- Install on a phone with `adb install -r android/artifacts/last-successful-debug.apk`. Fresh installs ask for a server URL once; updating an existing install preserves its saved URL. The public APK has no baked-in personal server. CI uploads only a debug artifact; no release signing or deployment was performed.

## Still unverified

- **No Android emulator/device run.** The scoped SDK is on a 9.8 GiB tmpfs, below the guard's required 15 GiB for emulator installation. No handset was connected. Physical airplane-mode cold launch, full-screen rendering, subtitle display, codec decode, TalkBack, large-font layout and notification denial were not witnessed.
- The automatic landscape orientation, repeated-tap dispatch, Android navigation gestures, watched-card visuals, 48dp icon targets, exact list offset restoration, animated sync rendering and storage-budget behavior need phone checks. Unit tests cover progression and persistence, not real touch dispatch or visual quality.
- The keyboard-focused YouTube/Downloads layouts and the collection Sync now cellular handover require physical-device checks. Automated tests cover per-source transfer permission, collection migration/removal protection, cellular-warning estimates and the packaged About version; no real mobile data was used in tests.
- Platform/API-specific background dataSync service starts, six-hour timeout, reboot behavior and real Wi-Fi/cellular/VPN handovers need device checks. The Wi-Fi gate checks eligibility before and during transport reads; sockets are not forcibly pinned to a Network, so an unusual route change still merits on-device inspection.
- The search → server job → phone download → player path is covered by adjacent mock-server/Room tests rather than one combined device fixture. Real YouTube acquisition and actual server-to-phone media playback have not been run in this session; avoid interpreting the debug APK as a field-tested travel-ready release.
- Optional subtitle rendering uses Media3 1.8's deprecated `SingleSampleMediaSource` legacy text-decoding opt-in; revisit when upgrading Media3. Cloudflare Access native login/service-token integration and cross-device collection sync are not in this version.

## Manual acceptance

On the intended phone, connect a reachable server; queue several short sources from Search and Library, browse while two transfers run and check actual named-video percentage/byte progress. Pause and resume one transfer and verify unknown sizes remain indeterminate. Add overlapping collections, check viewing time, play beyond 90%, mark another manually and verify muted cards. Scroll well down Browse, Downloads and a Collection, open a video, then return to the exact prior position. Repeatedly tap left/right to inspect cumulative skip; verify the native seek buttons remain accessible and system Back works while title controls hide. Set a small offline storage budget, verify new transfers wait without exceeding it, then explicitly clear all local files and confirm collections and watched state remain but require Sync now to resync. On cellular, inspect the MB/unknown-size warning and ensure unrelated queue items stay Wi-Fi-only. Test dark layout, icon targets and TalkBack at large font scale, then airplane-mode cold launch, subtitles, codec support and background/notification behavior. Keep fixtures small; do not delete real user server library items.
