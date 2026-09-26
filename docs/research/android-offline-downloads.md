# Sniptube Android offline APK — feasibility

Research date: 2026-09-20. Scope: Android-only, personal sideloaded client for videos **already hosted on the user's Sniptube server**, with **playback inside the app**, including cold startup in airplane mode. This is a research recommendation with a limited source review, not an implementation or a server-code audit.

## Recommendation

**Feasible. Prefer a small native Kotlin app with Media3 playback and native, persistent downloads.** Keep the initial product to server connection, library, download queue, downloaded videos, player, and storage management. This avoids maintaining a JavaScript/native state bridge for a use case dominated by Android lifecycle, storage, and media behavior.

**Capacitor is a credible second choice if reusing the Alpine UI is the priority**, but only with a real Kotlin/Java plugin owning downloads, persistent state, and native playback. Capacitor explicitly supports such plugins [8]. Bundle the UI and its dependencies for offline startup, rather than loading the server website. Full UI reuse effort remains unestimated.

A pure WebView or TWA wrapper is insufficient *by itself* for the recommended architecture. TWA content is rendered by the browser and the host has no direct access to its cookies/localStorage [10]. Packaging the website does not implement a native durable download queue or native offline player. A wrapper can be extended, but then it becomes a native integration project.

## Verified platform facts and their implications

### Media3 and progressive MP4

- Media3 provides `DownloadService`, `DownloadManager`, a persistent `DownloadIndex`, and a media `Cache`; its documented service uses the `dataSync` foreground-service type [1]. These are **Media3** classes, not the platform `android.app.DownloadManager`.
- Use a singleton `SimpleCache` with `NoOpCacheEvictor` for intentional downloads; automatic playback-cache eviction is unsuitable for a user's travel library [1]. Store that cache under persistent app storage, despite its API name.
- ExoPlayer plays downloaded content using the same cache through `CacheDataSource.Factory`; the guide includes progressive playback [1]. MP4 is a supported progressive container, but contained audio/video codecs must also be supported by the device [2]. An `.mp4` extension alone is no compatibility guarantee.
- Design the downloaded-only player to require locally completed media and avoid network fallback. The guide's example retains an HTTP upstream and therefore should not be copied as proof of strictly offline operation [1]. Test cold startup and seeking with airplane mode enabled.
- Download stop reasons persist; the global pause operation only changes runtime state [1]. Use persisted user intent for pause/resume across process recreation, and persist library metadata, thumbnails, subtitles, and playback positions independently.

### Wi-Fi versus unmetered

Media3's `Requirements.NETWORK_UNMETERED` means **an unmetered network**, not a Wi-Fi transport restriction [3]. Do not label that setting “Wi-Fi only.” A literal Wi-Fi-only feature needs additional native connectivity policy, including behavior when the network changes mid-transfer. Treat Wi-Fi transport and metering as separate decisions; validate VPN/tailnet behavior on the user's phone. Default recommendation: “Unmetered only,” with an explicit metered-download override.

### Background execution: resumable, not unrestricted

| Platform mechanism | Supported fact | Design consequence |
| --- | --- | --- |
| Media3 `DownloadService` | Documented as a `dataSync` foreground service with foreground-service permissions and progress notification [1]. | Start from an explicit user action; keep a durable queue and handle lifecycle interruptions. |
| Android 15+, targeting API 35+ | Background `dataSync` services share a **six-hour budget per app per 24 hours**. `mediaProcessing` has its own separate budget. Bringing the app to foreground resets the timer; `onTimeout` must stop the service promptly [4]. | Long pre-trip batches can be interrupted. Verify timeout handling in the chosen Media3 version; do not assume the library exempts the app. |
| Android 15, targeting API 35+ | `BOOT_COMPLETED` receivers cannot launch a `dataSync` foreground service [5]. | Persisting a queue is different from guaranteeing an immediate restart after reboot. |
| Android 16, all target SDKs | Regular/expedited jobs become subject to runtime quotas even when started while visible and continued in background, or running alongside a foreground service. The release notes explicitly include WorkManager, JobScheduler, and platform DownloadManager [6]. | Wrapping a long transfer in WorkManager is not an unlimited-execution workaround. Do not confuse the platform DownloadManager statement with a claim that Media3's manager is itself JobScheduler. |
| User-initiated data transfer (UIDT), Android 14+ | Designed for long user-triggered transfers; requires a notification and scheduling while visible or under an allowed condition. Both user and system can stop the job; execution is subject to system health and constraints [7]. | Best candidate for lengthy explicitly requested batches on modern Android, but still requires checkpoint/retry and stopped-state UX. |

**Practical implementation choice:** begin with Media3's documented download-service architecture for the personal MVP, and test realistic batch sizes on the target phone. If long background batches are a core requirement, prototype UIDT-backed transfer execution before committing to that service design. UIDT is not documented here as a drop-in `DownloadService` scheduler: integrating it with Media3 cache/index ownership, or choosing a resumable-file downloader plus ExoPlayer, is additional engineering. Avoid two competing transfer owners for the same content. Android documents a foreground WorkManager fallback below API 34 and notes no Jetpack UIDT compatibility library [7].

Sideloading does not make these OS lifecycle limits disappear. Promise “downloads can resume; verify Ready before departure,” not “downloads will always finish with the app closed.” Force-stop, device policy, depleted storage, unavailable credentials, and unreachable servers need recoverable states.

### Storage

Use app-specific **persistent** storage (`filesDir` or `getExternalFilesDir()`), not `cacheDir`, for selected offline media. App-specific external storage needs no storage permission on Android 4.4+, but is deleted on uninstall and its availability must be considered [11]. Provide explicit delete, used/free-space display, and a capacity check for the selected storage volume. Media3's storage-not-low requirement refers to internal storage [3]; it is not a complete per-volume capacity check.

### Capacitor scope

The official File Transfer plugin documents transfer calls and progress reporting [9]. The reviewed documentation does **not establish** a durable process-independent queue, restart recovery, UIDT integration, or Media3 playback. Do not infer those properties from a JavaScript download call. A robust Capacitor version should expose a narrow native API: enqueue, pause/resume, remove, list persisted state, observe progress, and open native player. Reload authoritative state from native storage whenever the WebView resumes [8].

## Current Sniptube fit (limited local source review)

- `api/app/routers/videos.py` already provides library metadata including file sizes (`GET /videos`), original-file downloads (`GET /videos/{video_id}/source`), and subtitle track URLs (`GET /videos/{video_id}/subtitles`). These provide a starting point for an Android client; the server need not be replaced.
- The original-file route handles MP4, MKV, and WebM and describes sources as typically AV1/VP9. Validate actual device decoding; a smaller phone-compatible rendition may be useful.
- `ui/sw.js` caches the application shell but explicitly leaves `/videos` and `/files` network-only. The current PWA does not implement the requested offline video library. Alpine dependencies are already locally vendored in its shell list.
- `api/app/main.py` serves media with `StaticFiles`, and the original-file route uses `FileResponse`. Live range/resume behavior through the actual proxy has not been tested.
- `README.md` documents no built-in authentication and recommends a private network or authenticating proxy. Native downloader access through the user's selected route needs validation.

## Proposed travel experience

1. Browse server titles and mark selected videos “Keep offline”; optionally group them into a named trip collection.
2. Queue transfers under the selected network policy. Show bytes/percentage, waiting reason, pause/resume, errors, and storage usage.
3. Mark a video “Ready offline” only when the local media and required metadata are complete.
4. Open the app's Downloads library in airplane mode and play within the app using Media3/ExoPlayer: play/pause, seeking, fullscreen, saved position, and locally downloaded subtitles.
5. Remove local copies explicitly to reclaim space. Treat these as retained offline copies rather than a mirror that automatically deletes them when the server prunes a video.

## Server integration questions for implementation

These are acceptance requirements to verify, not claims about the current server:

1. Stable video/content identifiers and authenticated direct media URLs; byte-range responses and stable validators for efficient resume; handling when a source changes or is removed.
2. Authentication usable by native HTTP requests. Browser login cookies or a Cloudflare Access session must not be assumed to transfer automatically into a native downloader.
3. Server access over the intended HTTPS/tailnet route while downloading. Fully downloaded playback must not need that route, token refresh, or a server request.
4. Actual media codecs on the phone, file sizes, and optional phone-compatible renditions. H.264/AAC is a sensible compatibility target to validate, rather than assuming all existing files work.
5. Local copies of descriptive metadata and optional subtitles/artwork. In a Capacitor build, bundle Alpine and all startup assets so cold launch does not depend on a CDN.

## Minimum acceptance checks

- Select several existing server videos; display progress, storage usage, queue/waiting reason, failure, and a trustworthy Ready state.
- Interrupt networking, switch metering policy, kill/recreate the process, reboot, and resume without exposing partial content as complete.
- Complete download, enable airplane mode, cold-start, seek near the end, and use locally saved subtitles.
- Exercise Android 15 timeout handling and Android 16 job behavior on the target device/OS; verify system/user cancellation recovery.
- Test low space, expired authentication, changed server media, and deletion. Local deletion should not implicitly delete the server copy.

## Primary sources

1. Media3 downloading and offline playback: https://developer.android.com/media/media3/exoplayer/downloading-media
2. ExoPlayer formats and codec caveats: https://developer.android.com/media/media3/exoplayer/supported-formats
3. Media3 download requirements: https://developer.android.com/reference/androidx/media3/exoplayer/scheduler/Requirements
4. Foreground-service timeouts: https://developer.android.com/develop/background-work/services/fgs/timeout
5. Android 15 target-SDK changes, including boot restrictions: https://developer.android.com/about/versions/15/behavior-changes-15
6. Android 16 job quotas: https://developer.android.com/about/versions/16/behavior-changes-all#job-quota-opt
7. UIDT requirements, stopping, and compatibility: https://developer.android.com/develop/background-work/background-tasks/uidt
8. Capacitor Android native plugins: https://capacitorjs.com/docs/plugins/android
9. Capacitor File Transfer API: https://capacitorjs.com/docs/apis/file-transfer
10. Trusted Web Activity architecture: https://developer.chrome.com/docs/android/trusted-web-activity/overview
11. Android app-specific storage: https://developer.android.com/training/data-storage/app-specific
12. Background transfer API selection: https://developer.android.com/develop/background-work/background-tasks/data-transfer-options
13. WorkManager long-running workers: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running

Research method: **3 Tavily calls total, sequential** (one broad search, two batched official-document extractions). Search returned some third-party hits despite domain filtering; those were not used as evidence. Android 16 quota specifics were additionally verified directly in the official page using the browser after a direct fetch failed. No subagents spawned, no deep app-code inspection, and no implementation changes.
