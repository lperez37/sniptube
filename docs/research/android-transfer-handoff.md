# Stage 6 transfer integration handoff

## Parent-owned changes required

- App Gradle: enable `android.testOptions.unitTests.isIncludeAndroidResources = true`.
  The integration test now drives the actual DownloadService lifecycle; its channel
  creation needs compiled Android string resources. Without this configuration it
  fails in `DownloadService.onCreate` with `Resources.NotFoundException`.
- DAO: `publishCompletedMedia` must reject `intent.userPaused` in the same Room
  transaction as publication. Transfer prechecks alone cannot serialize another
  caller's persisted pause with publication. Keep the generation/removal checks.
- AppContainer may wire the new optional `DeviceTransfers(..., onCompleted = ...)`
  callback to local sidecar sync. It executes only after successful publication.
- Root README/SKILL/implementation plan updates remain parent-owned.

## Initial reproduction

`flock /tmp/opencode/sniptube-gradle.lock android/scripts/gradle.sh :app:testDebugUnitTest --tests '*DeviceTransfersIntegrationTest'`

Failed with Room `deviceStage=Failed`, `errorMessage=Not enough device space`.
Robolectric's StatFs was unset (zero available space). The fixture now models free
space explicitly; production low-space enforcement is unchanged. Service start
intents are explicitly delivered through a Robolectric ServiceController instead
of treating enqueueing an intent as proof of service execution.

This is JVM lifecycle coverage, not proof of Android platform background-start,
notification-permission, process-death, or API-35 timeout behavior on hardware.

## Implemented transfer changes

- Propagate coroutine cancellation rather than converting it into durable failure.
- Process every eligible candidate (Media3 still limits active transfers to two),
  preventing an arbitrary first-two candidate slice from starving later items.
- Bind Media3 request data to Room generation + ETag; reject stale request callbacks,
  old-generation resumes, replaced URLs, and unowned index entries.
- Recheck Room after a suspended probe; serialize local removal with pump/publication;
  never restart a removing/restarting index entry.
- Force revalidation after effective stop, not service-resume before probe.
- Clear orphan cache spans lacking an index; measure actual cached bytes for storage;
  reserve outstanding queued/running bytes when admitting additional sources.
- Check Wi-Fi/representation ownership during every upstream body read, both before
  and after a blocking read. Reject inconsistent partial response lengths/encodings.
- Reconcile default-network callbacks with synchronous queries conservatively. Unknown
  capability, changed default, lost network, and cellular/VPN ambiguity fail closed.
- Optional completion callback runs separately after successful Room publication;
  sidecar errors cannot mark already-verified media as failed.

## Test coverage added / commands

Integration fixture drives real service `onCreate` / `onStartCommand` / `onDestroy`,
models storage, dispatches default-route callbacks, waits for actual bytes before
pause/remove, checks pause survives Wi-Fi loss/recovery, resumes, requires three
queued sources to become ready, and checks in-flight removal clears index/cache
without republishing Room rows. New TransferPolicyTest covers already-open response
revocation and callback-versus-synchronous-network races.

1. Original integration command: **FAIL**, unset StatFs (reproduced).
2. `flock /tmp/opencode/sniptube-gradle.lock android/scripts/gradle.sh :app:testDebugUnitTest --tests '*data.transfer.*'`:
   **7 passed, 1 failed**, service resource blocker noted above. This was before the
   final additional policy/race tests and final hardening edits.
3. `flock /tmp/opencode/sniptube-gradle.lock android/scripts/gradle.sh :app:testDebugUnitTest --tests '*MediaCacheTest' --tests '*TransferPolicyTest'`:
   **BLOCKED at compileDebugKotlin**, concurrent parent-owned OfflinePlayerScreen.kt
   unresolved Media3 PlayerView/UI dependency and nullable Int typing errors.
   No tests executed in this run. Do not call final edits verified until rerun.

Parent should fix owned build integration, then rerun the full `*data.transfer.*`
command. No repeated blocked builds, Gradle/root/UI changes, commits, or APK deletion
performed by this transfer work. Last disk check: **29 GiB available**.

## Remaining acceptance gaps

Actual-device API 31/34/35/36 foreground-start, notification denial, timeout/restart,
process-death recovery and VPN handover remain acceptance work. Policy checks stop
reads on observed default-route changes but sockets are not explicitly bound to a
captured Android Network; strict route-pinning behavior across handover requires
additional device validation (never bind around the effective VPN).
