package com.sniptube.android.data.acquisition

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sniptube.android.MainActivity
import com.sniptube.android.R
import com.sniptube.android.SniptubeApplication
import com.sniptube.android.data.local.DeviceStage
import com.sniptube.android.data.local.OfflineDao
import com.sniptube.android.data.local.ServerStage
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class AcquisitionCoordinator(
    private val context: Context,
    private val acquisition: ServerAcquisition,
    private val dao: OfflineDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var visibleLoop: Job? = null
    @Volatile var isForeground = false
        private set

    fun restore() {
        scope.launch {
            dao.getAcquisitionCandidates()
                .filter { it.serverStage != ServerStage.Ready &&
                    (it.serverStage != ServerStage.Failed || it.nextAttemptAt != null) }
                .map { it.serverIdentity }.distinct().forEach(::schedule)
        }
    }

    fun queued(serverIdentity: String) {
        schedule(serverIdentity)
        if (isForeground) scope.launch { acquisition.reconcile(serverIdentity, foreground = true) }
    }

    fun enterForeground() {
        isForeground = true
        context.getSystemService(NotificationManager::class.java).cancel(WAITING_NOTIFICATION_ID)
        visibleLoop?.cancel()
        visibleLoop = scope.launch {
            while (true) {
                dao.getAcquisitionCandidates().map { it.serverIdentity }.distinct().forEach { identity ->
                    try {
                        acquisition.reconcile(identity, foreground = true)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // WorkManager retains the retry path across process death.
                    }
                }
                delay(5_000)
            }
        }
    }

    fun leaveForeground() {
        isForeground = false
        visibleLoop?.cancel()
        visibleLoop = null
        scope.launch {
            dao.getAcquisitionCandidates().filter {
                it.serverStage == ServerStage.Ready && it.deviceStage == DeviceStage.Queued
            }.forEach {
                dao.waitForAllowedStart(it.serverIdentity, it.youtubeId, it.generation,
                    System.currentTimeMillis())
            }
        }
    }

    suspend fun backgroundPass(serverIdentity: String): Boolean {
        val pending = acquisition.reconcile(serverIdentity, foreground = isForeground)
        if (!isForeground && dao.getAcquisitionCandidates().any {
                it.serverIdentity == serverIdentity && it.deviceStage == DeviceStage.WaitingForNetwork &&
                    it.errorCode == "open_app_to_sync"
            }
        ) notifyOpenApp()
        return pending
    }

    private fun schedule(serverIdentity: String) {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(serverIdentity.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        val request = OneTimeWorkRequestBuilder<AcquisitionWorker>()
            .setInputData(workDataOf(SERVER_IDENTITY to serverIdentity))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "acquisition-$key", ExistingWorkPolicy.KEEP, request,
        )
    }

    private fun notifyOpenApp() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            WAITING_CHANNEL, "Offline sync", NotificationManager.IMPORTANCE_DEFAULT,
        ))
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(WAITING_NOTIFICATION_ID, NotificationCompat.Builder(context, WAITING_CHANNEL)
            .setSmallIcon(R.drawable.ic_sniptube)
            .setContentTitle("Sniptube video ready on server")
            .setContentText("Open the app to continue offline sync.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build())
    }

    private companion object {
        const val SERVER_IDENTITY = "server_identity"
        const val WAITING_CHANNEL = "offline_sync"
        const val WAITING_NOTIFICATION_ID = 1101
    }

    class AcquisitionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val identity = inputData.getString(SERVER_IDENTITY) ?: return Result.failure()
            val coordinator = (applicationContext as SniptubeApplication).container.acquisitionCoordinator
            return try {
                // No foreground Worker: stop this reconciliation pass rather than holding a job
                // while yt-dlp works on the server. WorkManager retains the next retry.
                if (withTimeout(90_000) { coordinator.backgroundPass(identity) }) {
                    Result.retry()
                } else {
                    Result.success()
                }
            } catch (_: TimeoutCancellationException) {
                Result.retry()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Result.retry()
            }
        }
    }
}
