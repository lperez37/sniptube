# Native offline playback integration

## Public entry points

```kotlin
com.sniptube.android.ui.player.OfflinePlayerScreen(
    container: AppContainer,
    serverIdentity: String,
    youtubeId: String,
    onBack: () -> Unit,
)

com.sniptube.android.data.playback.OfflineExtras(
    context: Context,
    dao: OfflineDao,
    apiClient: (ServerConnection) -> SniptubeApiClient,
).sync(serverIdentity: String, youtubeId: String) // suspend
```

Parent integration owns navigation, `androidx.media3:media3-ui:1.8.0`, the completion callback,
and `OfflineDao.publishAuxiliaryFile(LocalFileEntity): Boolean`. Auxiliary publication must be
transactional, require a non-media File asset, positive size, matching generation, and Ready intent.
Construct one extras helper in the container and call sync after successful completed-media publication.
Do not await enrichment before displaying Ready; failed/missing optional tracks never gate media.
Do not call sync from player entry or offline cold launch. Parent owns root README/SKILL/plan updates.

## Behavior

- Open reads Room and the existing singleton Media3 DownloadIndex/cache only. Ready intent,
  matching generation, completed index and full cache coverage are required.
- `media3:<id>` is an index marker, never a filename. The original `DownloadRequest.toMediaItem()`
  preserves source URI and custom cache key. Playback uses `MediaDownloads.offlineDataSource()`
  with null upstream and no cache writes. Missing bytes fail locally, never fall back to HTTP.
- Local VTT tracks use a separate `FileDataSource`/`SingleSampleMediaSource`, merged with the
  progressive media source. Missing tracks are skipped; subtitle read errors are treated as EOS.
  The pinned 1.8.0 TextRenderer explicitly enables render-time decoding for SingleSample VTT;
  its default is disabled. This deprecated Media3 path must be revisited when upgrading Media3.
  Verified against the pinned sources:
  https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/text/TextRenderer.java
- Native PlayerView supplies play/pause, seeking, subtitle selection and settings/speed controls.
  The screen pauses on lifecycle pause/stop, releases on disposal, writes position every five
  seconds, on seeks, on background, and on exit. A draining ordered writer survives navigation.
  Completed titles restart at zero; incomplete titles resume saved position.
- Fullscreen requests sensor landscape and transient system bars; exit/Back restores prior
  orientation and bar visibility. Back first exits fullscreen, then returns to the caller.
  Chrome follows existing Material roles, honors system insets, and error content scrolls at large text.
- Queue removal or generation change stops the player and presents a recovery action.
- Extras use SHA-256 directory components, generation-specific destinations, sibling temporary
  files and rename before generation-checked publication. VTT streams are capped at 2 MiB,
  thumbnails at 4 MiB, tracks at eight; calls have connection/read/overall timeouts and sync has
  a 90-second coroutine budget. HTTP calls disable redirects. The injected API client's subtitle
  discovery retains that client's JSON parsing behavior; file payloads use bounded buffers.
- Extras do not implement a second connectivity policy. The completion caller must invoke them
  under the permitted preparation/sync policy; the player never invokes them.

## Verification

Focused suite: `com.sniptube.android.data.playback.OfflinePlaybackTest`.
Fixtures cover no HTTP on cache miss, local VTT reads, cache-marker resolution and cached-tail seek,
missing cache after readiness, final-position persistence across database recreation, bounded streams,
deterministic server/generation-scoped paths, successful optional publication/reuse, and removal
during subtitle transfer. All media/HTTP inputs are disposable synthetic fixtures.

First command:

```sh
flock /tmp/opencode/sniptube-gradle.lock android/scripts/gradle.sh \
  :app:testDebugUnitTest --tests 'com.sniptube.android.data.playback.*'
```

Initial result: compilation blocked before test execution by missing parent-owned `media3-ui`
dependency and concurrent `ui/library/CollectionsScreen.kt` unresolved `semantics` /
`contentDescription` imports. No test is claimed passed from that attempt.

A second identical command reached only the missing `media3-ui` dependency and a nullable
`rememberSaveable` orientation inference error; the latter was corrected to a non-null saved
orientation with `SCREEN_ORIENTATION_UNSPECIFIED` fallback. Collections imports had been fixed
by their owner. Tests still have not executed. `git diff --check` passed; available disk after
these attempts was 30,947,606,528 bytes, above the 15 GiB reserve.

Third identical command confirmed the orientation fix: only unresolved Media3 UI/PlayerView
and its consequent type-inference errors remain. Test execution is blocked on the parent's
dependency addition; six test methods are present but none is reported as passed.

### Verified follow-up — 26 September 2026

After parent integration added Media3 UI and Android unit-test resources, the same serialized
command passed: **6 tests, 0 failures, 0 errors, 0 skipped**, Gradle `BUILD SUCCESSFUL in 11s`.
Final XML timestamp: `2026-09-26T05:36:58`, test runtime 6.772 seconds, empty stdout/stderr.
Report: `android/app/build/test-results/testDebugUnitTest/TEST-com.sniptube.android.data.playback.OfflinePlaybackTest.xml`.

Fixed test compatibility and fixture lifetime issues without weakening assertions:
- Replaced unavailable Robolectric 4.14 `buildApplication` with `Instrumentation.newApplication`,
  attaching a real application context without invoking production reconciliation startup.
- Explicitly declared the extras reuse test's return type as `Unit`; its final file deletion had
  inferred Boolean, which JUnit rejects as a non-void test.
- Closed the fixture-owned Media3 database provider after releasing the manager/cache; final
  test output contains no SQLite CloseGuard warnings.

The compiled player retains non-null `Int` saved orientation with
`SCREEN_ORIENTATION_UNSPECIFIED` fallback and restores it on exit. No production playback
defect was uncovered in this follow-up; hardware/UI verification below remains outstanding.

Physical/emulator verification remains required for actual decoder output, subtitle rendering,
airplane-mode cold start, rotation/fullscreen restoration, large font/TalkBack, audio focus and
background pause. JVM cached-byte tests are not a claim of hardware decoding or device UX proof.
