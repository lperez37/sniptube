package com.sniptube.android.ui.player

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.sniptube.android.AppContainer
import com.sniptube.android.data.local.DeviceStage
import com.sniptube.android.data.local.PlaybackProgressEntity
import com.sniptube.android.data.playback.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val SKIP_MS = 10_000L

internal fun skipPosition(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
    val target = (positionMs.coerceAtLeast(0) + deltaMs).coerceAtLeast(0)
    return if (durationMs > 0) target.coerceAtMost(durationMs) else target
}

internal fun nextSkipAmount(previousMs: Long, elapsedMs: Long, sameSide: Boolean): Long =
    if (sameSide && elapsedMs in 0..500) previousMs + SKIP_MS else SKIP_MS

/** Inherits Sniptube Material roles; native controls keep video and accessible transport foremost. */
@OptIn(UnstableApi::class)
@SuppressLint("ClickableViewAccessibility") // Native seek buttons provide accessible equivalents to the optional double-tap gesture.
@Composable
fun OfflinePlayerScreen(container: AppContainer, serverIdentity: String, youtubeId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dao = remember(container) { container.database.offlineDao() }
    val repository = remember(container) { OfflinePlayback(dao, container.mediaDownloads) }
    var asset by remember(serverIdentity, youtubeId) { mutableStateOf<OfflinePlaybackAsset?>(null) }
    var error by remember(serverIdentity, youtubeId) { mutableStateOf<String?>(null) }
    var skipNotice by remember { mutableStateOf<String?>(null) }
    var showChrome by remember(serverIdentity, youtubeId) { mutableStateOf(true) }
    val player = remember(context, serverIdentity, youtubeId) {
        ExoPlayer.Builder(context, offlineRenderersFactory(context)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
    }
    val writer = remember(player) { PlaybackProgressWriter(dao) }
    fun savePosition() {
        if (asset != null) writer.save(PlaybackProgressEntity(serverIdentity, youtubeId,
            player.currentPosition.coerceAtLeast(0), player.duration.takeIf { it > 0 },
            System.currentTimeMillis()))
    }
    val latestSave by rememberUpdatedState(newValue = { savePosition() })
    BackHandler { onBack() }
    FullscreenWindow(asset != null && error == null)
    LaunchedEffect(skipNotice) { if (skipNotice != null) { delay(900); skipNotice = null } }

    DisposableEffect(player, lifecycle) {
        var resumeAfterPause = true
        val listener = object : Player.Listener {
            override fun onPlayerError(failure: PlaybackException) {
                player.pause()
                error = "Cannot play this offline copy. Return to Downloads and retry device sync. " +
                    "If it still fails, this device may not support the source video codec."
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                                                 newPosition: Player.PositionInfo, reason: Int) {
                latestSave()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) latestSave()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                resumeAfterPause = player.playWhenReady
                player.pause()
                latestSave()
            } else if (event == Lifecycle.Event.ON_STOP) {
                // ON_STOP follows ON_PAUSE: do not overwrite the pre-pause playback intent.
                player.pause()
                latestSave()
            } else if (event == Lifecycle.Event.ON_RESUME && resumeAfterPause && asset != null && error == null) {
                player.play()
            }
        }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            latestSave()
            writer.close()
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        try {
            val loaded = repository.open(serverIdentity, youtubeId)
            player.setMediaSource(repository.source(loaded))
            player.seekTo(loaded.positionMs)
            asset = loaded
            player.prepare()
            player.playWhenReady = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
            error = "This offline copy is missing or incomplete. Return to Downloads and retry device sync while connected."
        }
    }
    LaunchedEffect(player, asset) {
        if (asset != null) while (true) { delay(5_000); savePosition() }
    }
    LaunchedEffect(player, asset?.generation) {
        val generation = asset?.generation ?: return@LaunchedEffect
        dao.observeQueue().collect { queue ->
            val current = queue.find { it.serverIdentity == serverIdentity && it.youtubeId == youtubeId }
            if (current?.generation != generation || current.deviceStage != DeviceStage.Ready) {
                savePosition()
                player.stop()
                error = "This offline copy was removed or became unavailable. Return to Downloads to sync it again."
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            when {
                error != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Offline playback unavailable", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(error!!, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    Button(onClick = onBack) { Text("Back to Downloads") }
                }
                asset == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    factory = { viewContext -> PlayerView(viewContext).apply {
                        this.player = player
                        keepScreenOn = true
                        setShowSubtitleButton(true)
                        setShowRewindButton(true)
                        setShowFastForwardButton(true)
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setControllerShowTimeoutMs(3_000)
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                            showChrome = visibility == View.VISIBLE
                        })
                        var doubleTapActive = false
                        var lastTapDownTime = -1L
                        var lastSkipTime = -1L
                        var lastSide = 0
                        var cumulativeSkip = 0L
                        fun skipTap(e: MotionEvent): Boolean {
                            if (e.y < height * 0.15f || e.y > height * 0.85f) return false
                            val side = when {
                                e.x < width * 0.4f -> -1
                                e.x > width * 0.6f -> 1
                                else -> return false
                            }
                            cumulativeSkip = nextSkipAmount(cumulativeSkip, e.eventTime - lastSkipTime, side == lastSide)
                            lastSkipTime = e.eventTime
                            lastSide = side
                            player.seekTo(skipPosition(player.currentPosition, player.duration, side * SKIP_MS))
                            skipNotice = "${if (side > 0) "+" else "-"}${cumulativeSkip / 1000} seconds"
                            return true
                        }
                        val detector = GestureDetector(viewContext, object : GestureDetector.SimpleOnGestureListener() {
                            override fun onDown(e: MotionEvent): Boolean = true
                            override fun onDoubleTap(e: MotionEvent): Boolean {
                                if (!skipTap(e)) return false
                                lastTapDownTime = e.downTime
                                doubleTapActive = true
                                return true
                            }
                            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                                if (e.actionMasked == MotionEvent.ACTION_DOWN && e.downTime != lastTapDownTime && skipTap(e)) {
                                    lastTapDownTime = e.downTime
                                    doubleTapActive = true
                                }
                                return doubleTapActive
                            }
                        })
                        setOnTouchListener { _, event ->
                            detector.onTouchEvent(event)
                            val consume = doubleTapActive
                            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                                doubleTapActive = false
                            }
                            consume
                        }
                    } },
                    update = { it.player = player },
                    onRelease = {
                        it.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?)
                        it.player = null
                        it.keepScreenOn = false
                    },
                )
            }
            AnimatedVisibility(visible = showChrome || error != null || asset == null,
                modifier = Modifier.align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().background(Color(0xBB11111B))
                .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Downloads", tint = Color.White)
                }
                Text(asset?.title ?: "Offline player", Modifier.weight(1f), color = Color.White,
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            }
            skipNotice?.let { message ->
                Surface(Modifier.align(Alignment.Center), color = Color(0xDD181825),
                    shape = MaterialTheme.shapes.medium) {
                    Text(message, Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun FullscreenWindow(fullscreen: Boolean) {
    val activity = LocalContext.current.activity()
    val originalOrientation = rememberSaveable {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val statusVisible = rememberSaveable { true }
    DisposableEffect(activity, fullscreen) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val behavior = controller?.systemBarsBehavior
        if (fullscreen && activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            if (fullscreen) {
                if (activity?.isChangingConfigurations != true) activity?.requestedOrientation = originalOrientation
                if (behavior != null) controller?.systemBarsBehavior = behavior
                if (statusVisible) controller?.show(WindowInsetsCompat.Type.statusBars())
                else controller?.hide(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
