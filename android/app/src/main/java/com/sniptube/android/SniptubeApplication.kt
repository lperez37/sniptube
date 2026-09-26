package com.sniptube.android

import android.app.Application
import android.content.Context
import com.sniptube.android.data.api.ServerConnectionStore
import com.sniptube.android.data.api.ServerConnection
import com.sniptube.android.data.api.SharedPreferencesConnectionSettings
import com.sniptube.android.data.api.SniptubeApiClient
import com.sniptube.android.data.browse.BrowseRepository
import com.sniptube.android.data.acquisition.AcquisitionCoordinator
import com.sniptube.android.data.acquisition.ServerAcquisition
import com.sniptube.android.data.local.SniptubeDatabase
import com.sniptube.android.data.transfer.DeviceTransfers
import com.sniptube.android.data.transfer.MediaDownloads
import com.sniptube.android.data.transfer.OfflineStorageBudget
import com.sniptube.android.data.library.LibraryActions
import com.sniptube.android.data.playback.OfflineExtras
import com.sniptube.android.data.local.DeviceStage
import com.sniptube.android.data.local.LocalFileKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SniptubeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.acquisitionCoordinator.restore()
        container.deviceTransfers.restore()
        container.restoreLibrary()
    }
}

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database: SniptubeDatabase by lazy { SniptubeDatabase.build(context) }
    val mediaDownloads: MediaDownloads by lazy { MediaDownloads(applicationContext as SniptubeApplication) }
    val offlineStorageBudget: OfflineStorageBudget by lazy { OfflineStorageBudget(applicationContext) }
    val deviceTransfers: DeviceTransfers by lazy {
        DeviceTransfers(applicationContext as SniptubeApplication, database.offlineDao(), mediaDownloads) { server, video ->
            if (deviceTransfers.gate.eligibility() == null) offlineExtras.sync(server, video)
        }
    }

    val libraryActions: LibraryActions by lazy { LibraryActions(this) }
    val offlineExtras: OfflineExtras by lazy {
        OfflineExtras(applicationContext, database.offlineDao(), ::apiClient) {
            deviceTransfers.gate.eligibility()
        }
    }

    fun restoreLibrary() {
        scope.launch { libraryActions.reconcileKeepOffline() }
        // Upgrade existing ready copies with optional offline artwork without blocking launch.
        scope.launch(Dispatchers.IO) {
            if (deviceTransfers.gate.eligibility() != null) return@launch
            val dao = database.offlineDao()
            dao.getMediaAssets().take(20).forEach { media ->
                val intent = dao.getIntent(media.serverIdentity, media.youtubeId)
                if (intent?.generation == media.generation && intent.deviceStage == DeviceStage.Ready &&
                    dao.getLocalFiles(media.serverIdentity, media.youtubeId).none { it.kind == LocalFileKind.Thumbnail &&
                        it.generation == media.generation && java.io.File(it.path).isFile }) {
                    offlineExtras.sync(media.serverIdentity, media.youtubeId)
                }
            }
        }
    }

    val serverConnections = ServerConnectionStore(
        SharedPreferencesConnectionSettings(
            context.getSharedPreferences("server_connection", Context.MODE_PRIVATE),
        ),
    )

    fun apiClient(
        connection: ServerConnection = serverConnections.load(),
    ): SniptubeApiClient = SniptubeApiClient(connection)

    val acquisitionCoordinator: AcquisitionCoordinator by lazy {
        AcquisitionCoordinator(
            applicationContext,
            ServerAcquisition(database.offlineDao(), ::apiClient),
            database.offlineDao(),
        )
    }

    fun browseRepository(
        connection: ServerConnection = serverConnections.load(),
    ): BrowseRepository = BrowseRepository(
        serverIdentity = connection.identity,
        client = apiClient(connection),
        offlineDao = database.offlineDao(),
        onQueued = { acquisitionCoordinator.queued(it) },
    )
}
