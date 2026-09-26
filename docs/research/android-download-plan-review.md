# Android download plan review

Retrieved **2026-09-25**. Research only: no application, main-plan, or timer changes. Reviewed `IMPLEMENTATION_PLAN.md`, `android/app/build.gradle.kts`, and `android/app/src/main/AndroidManifest.xml`. Current app is minSdk 26 / compileSdk 35 / targetSdk 35; Media3 is not yet a dependency and the manifest currently declares only INTERNET. Context7 was unavailable; findings below use official Android documentation/API reference and RFC 9110 instead. **Tavily: 3 sequential calls (1 search, 2 batched extracts), no additional Tavily calls.** Direct browser reads filled gaps left by extracted snippets; the failed RFC extraction was replaced with the HTTP Working Group's RFC publication. No agents or coding/model APIs were invoked.

## Recommended path

Keep the existing Room intent model and small coordinator. Use **Media3 DownloadService + DownloadManager + persistent SimpleCache** for phone media bytes, and Media3 for playback. Use bounded WorkManager reconciliation for server preparation/recovery, not one long-running Worker that waits for preparation and downloads the entire batch. Explicitly acknowledge Android's deferred-background-start boundary. Do not add a second transfer engine, server redesign, editing, clips, GIFs, or audio workflows.

The existing plan already identifies most concerns. These seven changes turn them into concrete stage gates rather than optional cautions. “Verified” describes source facts; “Recommendation” is the proposed Sniptube policy, not a platform guarantee.

## 1. Commit stage 6 to Media3's progressive downloader; do not require a finished MP4 file path

**Verified:** Media3 has a `ProgressiveDownloader`; ExoPlayer supports progressive MP4, WebM and Matroska containers. Container support does not guarantee a device decoder for every contained sample format. DownloadManager persists transfer state in DownloadIndex and writes media into a Cache. [S1–S3]

**Recommendation:** Pin one compatible stable Media3 version across modules, then prove the existing `/videos/{id}/source` endpoint with tiny MP4, MKV and WebM fixtures, including extensionless/octet-stream responses. Keep server preparation separate from byte transfer. Attach a stable, server-scoped download ID and cache key to the existing Room intent. Preserve generation checks and removal exclusions. Treat Media3 cached content as the local asset: its cache spans are **not** an ordinary standalone `.mp4` to hand to `Uri.fromFile`. Adapt the local-asset mapping rather than exporting a duplicate file or replacing Room. Container/decoder failures must remain actionable and must not trigger derivative-generation features.

## 2. Specify persistent cache ownership and strictly offline playback together

**Verified:** Official guidance uses a singleton download manager/cache with `NoOpCacheEvictor`, and requires playback to use the same Cache. `DownloadRequest.toMediaItem()` preserves the download's playback configuration. The guide's sample read-only playback factory **still has an HTTP upstream**: disabling writes alone does not disable networking. `CacheDataSource.Factory.setUpstreamDataSourceFactory(null)` explicitly fails on a cache miss. [S1, S4]

**Recommendation:** Put the download cache in a dedicated persistent app-private directory (e.g. under `filesDir`, not `cacheDir`), use `NoOpCacheEvictor`, and never open competing SimpleCache instances on it. For downloaded-video playback, use the same cache/key and null upstream, with cache writes disabled. Resolve downloaded subtitles through a local-file-capable source, not the null-upstream remote-media path. Store thumbnails/metadata/subtitles persistently too; exclude bulk media from backup.

“Ready offline” requires completed DownloadIndex state plus reconciled cache availability, not Room status or existence of one span. Reconcile missing/incomplete cache after process recreation; a cache miss is unavailable/error, never hidden HTTP fallback. Validate airplane-mode cold launch, seek near the end, subtitles, and saved position. Removal must go through download/cache ownership, not arbitrary span-file deletion. Low-space handling must not silently evict another kept video. These are app policies and acceptance tests, not guarantees supplied by NoOpCacheEvictor.

## 3. Add an explicit late-server-completion/background-start decision to stages 5–6

**Verified:** Apps targeting API 31+ generally cannot start an FGS from the background without a documented exception; failure throws `ForegroundServiceStartNotAllowedException`. A Media3 Scheduler can restart its service when pending download requirements become satisfied, but that is not a blanket exemption for arbitrary application code. [S1, S5]

**Recommendation:** Persist acquisition intent on the tap. While visible, reconcile server progress and hand ready items to DownloadService individually. An application-scoped coroutine survives navigation, **not process death**, and an earlier tap is not indefinite authorization to start an FGS later. Run short, unique, backoff-controlled reconciliation work for unfinished server preparation. Do not keep an otherwise idle dataSync FGS alive solely to poll a remote yt-dlp job.

When the server becomes ready after the app is backgrounded and no eligible service is running, persist `waiting-for-allowed-start`; attempt only a launch supported by the actual platform/library path. If disallowed, surface an “Open app to continue sync” notification when permitted and resume on foreground entry. Document that automatic background handoff is best effort, not guaranteed immediate completion. Exercise this exact case on API 31/34/35/36, including service stopped/process dead and notifications denied. A scheduler or `foreground=true` flag must not be described as bypassing restrictions. Do not add exact alarms, battery-optimization exemptions, or a second UIDT engine just to mask this limitation.

## 4. Make platform lifecycle handling a manifest-and-runtime acceptance gate

**Verified:** Media3's download service example declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and `foregroundServiceType="dataSync"`. API 34+ target rules require the appropriate FGS permissions/types. On Android 15 for target 35+, background dataSync FGS time is limited to a shared six hours per 24 hours; `onTimeout(int, int)` must stop the service within a few seconds. Bringing the app foreground resets the budget. Target-35 apps cannot start dataSync FGS from BOOT_COMPLETED. Android 16 long-running WorkManager workers can exhaust job quotas even when backed by an FGS. [S1, S5–S8]

**Recommendation:** Add ACCESS_NETWORK_STATE, the two download FGS permissions, a non-exported dataSync DownloadService, and the declarations required by the selected scheduler. Use downloads notification channel/progress/cancel handling. Add POST_NOTIFICATIONS with contextual API-33+ permission UX. Notification permission is **not required to start an FGS**, but an FGS notification is still required; denial hides it from the notification drawer while notices remain in Task Manager. A download is not a media-session notification exemption. [S9]

Verify how the pinned DownloadService version handles API-35 timeout; ensure durable intent/partial bytes survive and `stopSelf()` is reached without an immediate restart loop. Do not classify downloading as `mediaPlayback` or `mediaProcessing` to evade limits. Playback confined to the app need not introduce another FGS; actual background playback would require its own justified lifecycle/type. Test reboot as recovery of intent, not guaranteed direct service launch. Keep WorkManager polling bounded and resumable on API 36; targetSdk 35 does not justify ignoring Android 16 runtime quotas. UIDT is a documented alternative, but not needed for this first implementation path. [S8]

## 5. Define Wi-Fi-only as a transport policy, including VPN and LAN-only Wi-Fi

**Verified:** Media3 Requirements exposes NETWORK and NETWORK_UNMETERED, not a literal Wi-Fi requirement. Android separates transports from meteredness; a VPN may report Wi-Fi, cellular and VPN transports together, and these can change. VALIDATED means tested public-internet access, not reachability of a LAN server. Capability changes should be handled in `onCapabilitiesChanged`, rather than racing synchronous queries in `onAvailable`. [S10, S11]

**Recommendation:** Implement one service-owned network gate shared by transfers/retries. Default literal Wi-Fi policy allows metered Wi-Fi (show a warning); optional “unmetered too” is a separate setting. Never accept unmetered cellular/Ethernet as Wi-Fi. Check the network actually used by transfer connections, not merely whether any Wi-Fi is present. Cancel/pause active requests and prevent cellular retry/failover when eligibility is lost. Where strict routing is needed, bind transfer connections to the eligible Network rather than globally rebinding browsing traffic.

For tailnet/VPN, preserve VPN routing: allow only when underlying transport is demonstrably Wi-Fi without cellular ambiguity; otherwise wait with an honest reason. Never bypass VPN by pinning physical Wi-Fi indiscriminately. This conservative policy may decline some valid VPN configurations; document that limitation. Do not require public INTERNET/VALIDATED solely to sync a reachable LAN host. Audit the pinned Media3 Requirements behavior so its network checks do not unintentionally reject LAN-only Wi-Fi; use the custom gate as the authority and avoid contradictory built-in constraints. Test Wi-Fi loss during reads, metered Wi-Fi, unmetered cellular, LAN without internet, VPN Wi-Fi-to-cellular transition, and unknown VPN transport.

## 6. Translate durable pause intent and progress correctly into Media3

**Verified:** Per-download nonzero stop reasons persist in DownloadIndex and retain partial media. Global pause/resume changes only DownloadManager runtime state and does not persist. Progress changes do not trigger DownloadManager.Listener callbacks; official guidance says to sample progress periodically. [S1]

**Recommendation:** Keep Room as the user's intent source; map user-paused, network-blocked and other blockers into an effective per-item stop reason without letting one clear another. Recompute this on restart. Bulk pause applies to selected IDs, not the manager's global pause switch. Sample bytes at a modest UI/notification cadence; keep unknown totals unknown and do not rewrite Room for every buffer. Removal invalidates ownership before cancellation, and late completion cannot resurrect it. Add tests for user pause + network recovery, restart while paused, and remove-during-transfer. Collections continue to share one copy and honor explicit local-removal exclusions.

## 7. Keep an HTTP correctness gate even when Media3 owns transfers

**Verified:** RFC 9110 requires valid Content-Range interpretation for 206; its Content-Length is the response-part length, not necessarily total resource length. If-Range must not use a weak ETag; a validator mismatch means send the full representation instead. A server can ignore Range and return 200. A 416 may supply `Content-Range: bytes */N` but is not proof that the client's bytes are valid/completed. Combining partial responses requires the same strong validator. [S12]

**Recommendation:** Test the actual deployed source behavior with disposable fixtures before claiming efficient resume. Do not assume Media3 makes mutable source URLs/versioning safe: record representation identity, and invalidate partial cached content when replacement is detected. YouTube-derived video IDs identify videos, not immutable file bytes. Verify validators/range behavior for the pinned server dependencies and transfer library; measure that a resumed transfer does not retransmit the entire prefix.

If a custom-file fallback is genuinely necessary, make this its minimum contract: stream bounded buffers into `.part`; resume from actual durable length using a stored strong ETag with Range/If-Range; enforce identity encoding; append only a valid 206 beginning exactly at that offset with consistent range/length/representation; truncate/restart on 200; treat 416 as complete only after independently confirming identity and full local length, otherwise restart/fail. Reject malformed ranges, HTML/auth responses and truncated bodies; constrain redirects to approved origins. With no trustworthy validator or immutable-source guarantee, restart rather than combine uncertain bytes. Flush/close, verify, atomically rename on the same filesystem, then generation-check Room publication; reconcile a crash between rename and DB update. Atomic publication, buffer sizing and redirect policy are recommendations, not requirements imposed by RFC 9110. Keep this as a fallback acceptance gate, not a parallel implementation.

## Exact primary-source URLs

- **S1:** https://developer.android.com/media/media3/exoplayer/downloading-media
- **S2:** https://developer.android.com/reference/androidx/media3/exoplayer/offline/ProgressiveDownloader
- **S3:** https://developer.android.com/media/media3/exoplayer/supported-formats
- **S4:** https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSource.Factory
- **S5:** https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- **S6:** https://developer.android.com/develop/background-work/services/fgs/timeout
- **S7:** https://developer.android.com/develop/background-work/services/fgs/service-types
- **S8:** https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- **S9:** https://developer.android.com/develop/ui/views/notifications/notification-permission
- **S10:** https://developer.android.com/develop/connectivity/network-ops/reading-network-state
- **S11:** https://developer.android.com/reference/androidx/media3/exoplayer/scheduler/Requirements
- **S12:** https://httpwg.org/specs/rfc9110.html#field.if-range ; https://httpwg.org/specs/rfc9110.html#field.content-range ; https://httpwg.org/specs/rfc9110.html#status.206 ; https://httpwg.org/specs/rfc9110.html#status.416

## Boundaries of this review

No APK build, emulator test, server Range probe, or pinned-library source audit was performed. In particular, selected-version timeout handling, exact scheduler restart behavior, cache-validation mechanics and strict VPN routing remain implementation verification gates, not claimed working features. Research does not authorize changes to the main plan or timer. Preserve native search, server acquisition, browsing, bulk controls, local collections/Keep offline and offline playback; no scope expansion.
