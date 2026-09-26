# Sniptube Android — Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Status and Scope

Native Android sync/offline-playback client implemented inside the existing Sniptube repository. Search, bulk selection, server acquisition and Wi-Fi-gated device sync, device-local collections, Downloads management and cache-only Media3 playback are wired. JVM integration and backend tests pass; actual airplane-mode/device playback, codec support and Android background policy still need acceptance on a device. The server and web/PWA remain separate application surfaces.

## Stack

Kotlin 2.0.21, Jetpack Compose Material 3, OkHttp/kotlinx.serialization, Room 2.7.2 and Media3 1.8.0 are in a single app module with minimum Android 8.0 (API 26) and target API 35.

## Users

Initially Luis, using an Android device to discover videos, prepare them for travel, and watch them on a plane without connectivity.

## Product Purpose

Search YouTube or browse videos already hosted by Sniptube, add individual or multiple titles to one end-to-end queue, and have them downloaded to the server and then transferred to the device automatically. The user can continue browsing and adding videos throughout. Play completed local copies inside the app. Success includes cold-starting the app in airplane mode and watching downloaded videos without contacting the server.

## Operating Context

- Preparation happens while the device can reach the server, with Wi-Fi-restricted downloading desired.
- Playback must work offline inside the app.
- Installation is through an Android APK; the Android project lives alongside the existing backend and web client.

## Confirmed Capabilities and Constraints

- The APK is a lightweight sync and offline-playback companion between the Sniptube server and the device, not a port of the full web app. No clipping, GIF generation, audio extraction, video editing, crop controls, or derivative-generation UI/workflows. Those existing capabilities stay in the server/web app. Retain the previously agreed search-to-server-to-device queue and bulk offline operations.
- High-quality user experience and bulk operations are core requirements.
- Search YouTube inside the app and enqueue individual or multiple results for offline viewing through one action. Already-hosted videos enter the same flow without a redundant server download.
- A queued item retains the intent to reach this device: YouTube download to server, then automatic device transfer when ready and network policy permits. No second manual download action is required.
- Browsing, searching, navigation, and additional selections remain usable during server preparation and device transfers. Work is owned by persistent queue/service state, not by the lifetime of a screen.
- Initial bulk operations include end-to-end acquisition plus offline-copy management: enqueue, pause/resume device transfers, retry failed stages, add to collections, and remove from the device. Server-job cancellation semantics remain to be defined separately.
- Bulk server protection and server deletion are outside this initial scope.
- Collections always maintain offline intent, show total known viewing time and queue newly added members automatically over eligible Wi-Fi. **Sync now** can allow that collection's unfinished media to use cellular after an explicit known-MB and unknown-size warning.
- A phone copy cannot be removed while the video belongs to any collection. Remove its memberships first; existing downloaded copies are otherwise retained.
- Android can defer or interrupt background work. Persistent intent, resumable transfers, and truthful readiness states are required; immediate unattended completion is not guaranteed.
- Wi-Fi transport and unmetered connectivity are different conditions and must not be mislabeled.
- The About screen displays an incrementing build version and a packaged changelog. YouTube and Downloads search hide nonessential summary/selection/navigation chrome while the virtual keyboard is open.

## Product Principles

- Trustworthy offline readiness: queued or partial files are never represented as ready to watch.
- Use the web PWA's Catppuccin/Mauve, thumbnail-led visual identity in native Material 3 screens, with modern selection ergonomics rather than a smaller copy of the web layout.
- Offline playback opens in landscape fullscreen with Android navigation gestures intact. Double-tap the left/right video area for ten-second skip; visible accessible controls do the same.
- Bulk actions are first-class workflows with clear selection scope and per-item results.
- Preserve travel content until the user deliberately removes it.
- Clearly distinguish server availability, transfer state, and locally playable content.

## End-to-End Queue Requirements

- Present one user-facing queue with distinct stages: queued for server, downloading to server, waiting for permitted device connectivity, transferring to device, and ready offline. Failures identify the stage and provide an appropriate retry.
- Start each device transfer as its server file becomes ready; do not wait for the entire batch to finish server processing.
- Expose progress while browsing through a persistent queue summary and a detailed queue screen. Server progress and device-transfer progress must not be conflated into a misleading completion percentage.
- Persist intended work before submitting it, reconcile server job IDs after interruptions, and deduplicate repeated selections. Reopening the app restores the queue rather than starting duplicate work.
- A failure in one item does not block unrelated items. Retrying device transfer reuses a ready server copy.
- Queue orchestration must respect server concurrency limits and bound device transfers so bulk work does not overwhelm browsing.
- Ongoing execution while the app is backgrounded remains subject to Android scheduling; continuing work while navigating within the app is a core requirement.

## Evidence on Hand

- `../docs/research/android-download-plan-review.md`: 25 September primary-source implementation corrections for Media3 cache/playback, background starts/timeouts, Wi-Fi policy and resume correctness. The implementation plan converts these into stage acceptance gates.
- `../docs/research/android-offline-downloads.md`: primary-source Android feasibility research and limited server review.
- `../api/app/routers/videos.py`: existing library, source-file, and subtitle endpoints.
- `../api/app/main.py`: existing search, video acquisition, and job routers that form the starting point for the acquisition pipeline.
- `../ui/style.css` and `../ui/logo.svg`: existing Sniptube dark/Mauve visual identity adapted into native Material components. The theme selector was removed at the user's request.

## Open Decisions

- Collections are device-local in this version; a future server-backed editor would need a separate contract.
- Server URL is user-configurable and persisted; a first install asks once instead of shipping a personal hostname. Earlier saved URLs survive the update. The device-local maximum offline media budget, live free-space display and explicit clear-all-local-files flow are implemented; native authentication/Cloudflare Access integration and target physical phone/OS remain open.
- Device checks for native layout, TalkBack, large text, landscape, subtitles and airplane-mode playback remain open.
- Phone-compatible download quality and handling of unsupported source codecs.
- Local removal of nonmembers creates one server-scoped exclusion. Adding the video to an offline collection or explicitly downloading it clears the exclusion.
