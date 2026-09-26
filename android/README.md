# Sniptube Android

Native Android client for Sniptube. The project uses Kotlin, Jetpack Compose Material 3, and a single `app` module. It currently targets Android 15 (API 35) and supports Android 8.0+ (API 26).

## Server connection

On first launch the app asks for a Sniptube server URL and saves it in private device preferences. Future launches open Downloads directly, including while offline. Settings lets you change the URL and offers **Open web version in browser**, which launches that saved URL in the phone's external browser. A branded splash screen displays the app logo during startup. No personal hostname ships as a default; an existing saved URL remains unchanged after an update. For local tests enter `http://10.0.2.2:8030/` in the emulator or `http://<server-LAN-IP>:8030/` on trusted Wi-Fi. A reverse-proxy path is supported and normalized with a trailing slash.

HTTP is enabled for an explicit trusted LAN or tailnet deployment. Prefer HTTPS whenever it is available; the client uses Android/OkHttp's normal certificate and hostname verification and does not install a permissive trust manager. URLs containing credentials are rejected, and media/subtitle URLs returned by the server must remain on the configured origin.

The typed client currently covers bounded YouTube search, server library/detail, video acquisition, active/individual jobs, resumable source requests, and subtitle listings. Connection timeout, unreachable-server, malformed-response, HTTP, and 401/403 authentication failures are distinct user-facing errors. Cloudflare Access browser login is not integrated: an Access-protected route that does not return the JSON API contract will fail explicitly rather than bypassing Access or embedding a service token.

## Browse and queue intent

Browse has YouTube search and server-library tabs. Search waits briefly after typing, cancels old requests when the query changes, and offers Load more for server-supported pages (up to five). Results show server preparation status when known; “on server” is not the same as ready on this phone. Both tabs support single or bulk **Download offline**, explicit selection/loaded-results Select all and bulk **Add to collection**. Queue intent is deduplicated in Room and can explicitly restore a removed copy. Changing server identities keeps those intents separate. Navigation remains usable while syncing. A compact Browse summary shows unfinished work; Downloads distinguishes server and phone stages and actual byte progress.

The native visual language follows Sniptube's PWA (`ui/style.css`): Catppuccin dark surfaces, Mauve controls, rounded 16:9 artwork and image-led video rows. Per-video actions use 48dp icons with explicit TalkBack labels; selection actions remain in a pinned toolbar. Collection rows are visually muted once a video has played beyond 90% or is marked watched manually. The viewed mark persists across replays; marking unwatched resets saved position to the beginning. Browse, Downloads and Collection lists retain per-filter scroll positions when returning from playback. Dark is always used as requested; there is no theme selector. This design uses Material 3 and Caselia's `tech/native-android-app-playbook` for local state and accessible action alternatives.

When the keyboard opens in YouTube Search or Downloads Search, queue/storage summaries, bottom selection controls and bottom navigation collapse until typing is done. Settings includes an **About** screen with the packaged changelog and exact build number. `app/src/main/res/raw/changelog.md` is the displayed release-history source of truth; bump `versionCode` and `versionName` in `app/build.gradle.kts` alongside its first entry for each shared APK.

Server acquisition runs independently of the Browse screen. A short, at-most-two-item pass checks library and active jobs before posting a new download request; lost responses are reconciled against the server's deterministic video ID and job status. Already-ready sources skip the POST. A visible-app loop polls while browsing; unique WorkManager jobs restore pending preparation after process death and back off when server work is unfinished or the server cannot be reached. A ready server video moves to the device queue individually; in the background it waits for an allowed foreground start and can show an **Open app to continue sync** notification if notifications are permitted. Background completion is best effort, subject to Android scheduling. Downloads offers per-item/bulk pause, resume, retry, local-only removal and in-app playback. Collections can be created, renamed and deleted and can overlap, but every collection maintains offline intent: adding a member queues it automatically. Each collection shows known total viewing time, unknown-duration count and ready count. Remove membership before removing the video's phone copy.

## Durable local state

Room 2.7.2 owns the offline database. A video is uniquely scoped by the normalized server identity plus its YouTube ID, so changing servers cannot attach new jobs to another server's records. Separate server and device stages preserve the end-to-end intent, while server-job and device-transfer bindings remain recoverable attachments. Retry stage/count/backoff, per-item pause state, transfer validators and byte counts, completed media/subtitle/thumbnail paths, and playback position survive process recreation.

Server reconciliation holds a process-wide mutex and generation-checks each Room publication. A failed server job records a per-item error without blocking other items; transient connection/5xx/429 failures use persisted exponential backoff. Permanent authentication, malformed-response, and server-job errors wait for explicit Retry. Retrying a failed server job detaches its terminal binding; a confirmed missing server source requeues server preparation. A user-paused **device** transfer does not cancel server preparation. WorkManager workers perform only bounded reconciliation and do not run a foreground service or claim a background-service-start exemption; device bytes use a separate Media3 dataSync DownloadService.

Collections are device-local. Membership is many-to-many and overlapping collections still yield one device candidate. Startup converts older optional-keep-offline collections to always-on offline intent and restores previously excluded members. Room blocks local removal while any collection membership remains; removing the last membership permits removal from Downloads. A removed nonmember stays excluded from automatic redownload. Transfer generations guard completion publication so a late callback cannot mark a removed copy ready.

**Sync now:** automatic phone transfers require Wi-Fi. If the default route is cellular or a stable VPN using cellular, tapping Sync now in a collection presents an estimated remaining MB total for known sizes, explicitly counts unknown-size videos and asks for confirmation before authorizing only that collection's copies. The authorization is in memory for this process, does not transfer other queued videos over cellular, and is revoked after completion, failure or service timeout. A restart requires a new confirmation. Sync now on Wi-Fi immediately reconciles and starts eligible work without cellular authorization. Previously paused or failed collection items are explicitly resumed/retried by Sync now.

The generated Room schema is checked in under `app/schemas/`. Production database construction registers every migration in `SniptubeDatabase.MIGRATIONS` and intentionally has no destructive fallback. Version 2 adds a cache-asset representation via the tested 1→2 migration; version 3 adds a durable viewed flag to playback progress via a tested 2→3 migration. Existing positions survive migration. JVM/Robolectric DAO tests cover deduplication, server scoping, pause/resume, playback/viewed persistence, collection overlap, exclusions and stale transfer completion.

## Native transfer status

Media3 DownloadService/DownloadManager write to a singleton `SimpleCache` under app-private `filesDir/offline-media` with no eviction; device bytes are excluded from backup. Stable SHA-256 transfer IDs scope each copy to its server and YouTube ID. Room stores the cache-backed asset, not a fictional video filename. Ready offline requires completed DownloadIndex state plus full cache coverage. A missing cache on restart or on player open becomes retryable. Local removal invalidates ownership before removing Media3 bytes, deletes associated optional files and suppresses automatic Keep-offline redownload until an explicit user action.

The active default route controls transfers: metered Wi-Fi and LAN-only Wi-Fi qualify, cellular and ambiguous VPN routes wait unless a specific collection's Sync now is confirmed, and a Wi-Fi-only VPN stays on the VPN rather than bypassing it. Network callbacks stop active downloads if the route becomes ineligible. A bounded byte-range probe requires strong ETag, known size, consistent partial length and MP4/MKV/WebM signature. Restored downloads cannot accept network bytes before the new process probes the source; resumed requests use If-Range and reject changed validators, invalid ranges, HTML/login pages and redirects. A low-space check always reserves 128 MiB. A configured media budget additionally compares actual Media3 cache bytes with projected unfinished downloads, blocks new requests when over limit and stops existing downloads when the budget is lowered; changing the budget in Settings rechecks the queue. Settings displays current free space and disables choices beyond safe capacity. Media3's UNMETERED requirement would reject metered/LAN-only Wi-Fi, so a separate literal transport gate is used. Denied notifications do not by themselves block a legal download. A dataSync timeout retains intent/partial bytes until foreground re-entry; reboot does not launch an FGS.

**Verification boundary:** JVM/MockWebServer fixtures exercise MP4/MKV/WebM signatures, interrupted resume/If-Range, representation changes and the app-owned service/Room path including pause/resume, more than two queued items and in-flight removal. Playback tests prove null-upstream cache reads/misses, saved position and generation-safe optional files. The backend suite covers source ranges and missing-ready-source repair. Physical Wi-Fi/VPN transitions, denied notifications, background-start restrictions/timeouts, subtitle rendering, decoder support and airplane-mode device playback remain unverified. See [IMPLEMENTATION_REPORT.md](IMPLEMENTATION_REPORT.md).

## Offline playback and storage

Downloads cold-starts without a working server. Open a verified **Ready offline** item to play it in-app. The player enters horizontal fullscreen automatically, retains the Android navigation bar/Back gesture and lets you double-tap the left/right side of the video for a ten-second skip. Continue tapping on that side to accumulate 20, 30, 40 seconds and so on. Only the Back button and video title appear with Media3's controls and hide on their three-second timeout; Media3 itself provides accessible rewind and fast-forward controls. The player reads only Media3's persistent cache, saves position and releases the player when leaving. On end-of-playback the saved progress is committed, so a video beyond 90% becomes watched even if a later replay starts at zero. Optional local VTT tracks and thumbnails never determine media readiness. Ready offline rows never fetch network artwork. Cache misses do not fall back to HTTP and become retryable. Keep enough phone storage for the video plus a 128 MiB reserve; unknown sizes stay unknown. Source MP4/MKV/WebM codec support depends on the device. Cloudflare Access browser login is not supported by the native client.

Settings includes a confirmed **Clear all phone copies** action. It stops/deletes local Media3 downloads and optional artwork/subtitle files while preserving collection membership, server metadata and watched positions. Cleared collection videos stay excluded from automatic re-download until the user selects Sync now or explicitly downloads them again; free space may take a moment to update while Media3 processes removals. The offline media budget applies to the Media3 cache, with a separate permanent OS free-space reserve.

Manual acceptance on a phone: connect the server, download a short video over Wi-Fi, wait for **Ready offline**, enable airplane mode, force-stop and reopen Sniptube, then check local artwork, automatic landscape rotation, repeated left/right taps (10, 20, 30 seconds), transient player chrome, Back gesture, seek/resume and subtitles. Watch beyond 90% or mark a collection item watched manually; verify muted styling and that an unwatch reset starts from zero. Open a video after scrolling in each screen and check the exact list/offset on return. Inspect the title, measured percentage or indeterminate animation for active server/phone transfers; no unknown sizes should show invented percentages. Check icons at large font scale with TalkBack and comfortable touch targets in dark mode. Confirm YouTube and Downloads search do not show displaced storage/selection/navigation chrome when the keyboard opens. Verify collection auto-sync on Wi-Fi; on cellular, use Sync now only after inspecting the MB/unknown-size warning and confirm unrelated queued media does not transfer. Lower the storage budget during an active transfer, inspect the waiting state, then confirm the explicit clear-all-local-files dialog, retained collections/progress and manual Sync now restore. Check notification denial and background/foreground transfer on the intended Android version. No device result has been claimed for these checks.

Host backend regression command when the old `.venv` symlink is unavailable: from `api/`, `UV_PROJECT_ENVIRONMENT=/tmp/opencode/sniptube-api-venv uv run --group dev python -m pytest tests -q`. No live service restart is needed for the host tests.

## Reproducible build environment

The host only needs Nix with flakes enabled and Python 3. `nix/flake.lock` pins the Linux x86_64 build shell and provides JDK 17, curl, and unzip without changing the host NixOS configuration. The configuration-only `nix/` directory is deliberately separate from SDK, Gradle and app files: never use `nix develop path:android`, which snapshots changing build caches into the Nix store. The SDK bootstrap downloads Google's official Android command-line tools 12.0, verifies the SHA-1 published in Google's SDK repository, accepts the Android SDK licenses, and installs only these pinned packages:

- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0
- Android SDK Platform-Tools

The local SDK, Android user state and debug key under `.toolchain/`, Gradle state under `.gradle/`, generated `local.properties`, and build outputs are ignored by Git. The helper script does not write build state into home-directory Android or Gradle caches.

From the repository root:

```bash
android/scripts/bootstrap-sdk.sh
android/scripts/gradle.sh :app:assembleDebug
android/scripts/gradle.sh :app:testDebugUnitTest
android/scripts/gradle.sh :app:lintDebug
android/scripts/gradle.sh :app:compileDebugAndroidTestKotlin
```

The scripts automatically enter the pinned `android/nix/` shell when Java is not already available. Both refuse to start with less than **15 GiB free** and check free space every five seconds while work runs. They terminate the command group on low space without deleting caches, files or prior APKs.

The debug APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk`. After a successful Gradle invocation, a valid APK is atomically retained at `android/artifacts/last-successful-debug.apk`, with checksum and metadata beside it. This retained copy survives `clean` or deletion of `app/build`; its existence alone does not imply that all app features or device checks are complete.

Install on a connected phone with `adb install -r android/artifacts/last-successful-debug.apk` and configure a server URL the phone can reach. `.github/workflows/android.yml` tests, lints, compiles instrumentation source and uploads a debug APK artifact; it does not create a public release or release-signed binary.

Run the disk-guard and artifact-retention regression tests with:

```bash
python3 -m unittest discover -s android/scripts -p 'test_build_safety.py' -v
```

## Direct Gradle use

If JDK 17 is already active, set `ANDROID_HOME` to `android/.toolchain/android-sdk` and use the checked-in wrapper:

```bash
ANDROID_HOME="$PWD/android/.toolchain/android-sdk" android/gradlew -p android :app:assembleDebug
```

Gradle 8.9 and its distribution SHA-256 are pinned in `gradle/wrapper/gradle-wrapper.properties`. Gradle is limited to two workers and a 1536 MiB heap to keep unattended builds bounded.

## IDE

Open the `android/` directory in Android Studio. Run `scripts/bootstrap-sdk.sh` first or select an installed API 35 SDK. Do not commit `local.properties` or IDE-generated files.
