package com.sniptube.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.*
import com.sniptube.android.AppContainer
import com.sniptube.android.R
import com.sniptube.android.data.api.SniptubeApiException
import com.sniptube.android.data.library.*
import com.sniptube.android.data.local.DeviceStage
import com.sniptube.android.ui.browse.BrowseScreen
import com.sniptube.android.ui.browse.BrowseViewModel
import com.sniptube.android.ui.library.*
import com.sniptube.android.ui.player.OfflinePlayerScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val PLAYER_ROUTE = "player/{server}/{youtube}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SniptubeApp(container: AppContainer, modifier: Modifier = Modifier) {
    var serverIdentity by rememberSaveable { mutableStateOf(container.serverConnections.loadOrNull()?.identity) }
    val initialDestination = remember { if (serverIdentity == null) "connect" else "downloads" }
    val connection = remember(serverIdentity) { serverIdentity?.let(com.sniptube.android.data.api.ServerConnection::parse) }
    val model: BrowseViewModel? = if (connection == null) null else viewModel(key = "browse:$serverIdentity",
        factory = BrowseViewModel.factory(container.browseRepository(connection)))
    val browse = if (model == null) null else model.state.collectAsStateWithLifecycle().value
    val actions = remember(container) { container.libraryActions }
    val snapshot by remember(actions) { actions.observe() }.collectAsStateWithLifecycle(LibrarySnapshot())
    val dao = remember(container) { container.database.offlineDao() }
    val jobs by remember(dao) { dao.observeServerJobs() }.collectAsStateWithLifecycle(emptyList())
    val transfers by remember(dao) { dao.observeDeviceTransfers() }.collectAsStateWithLifecycle(emptyList())
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "downloads"
    val snackbar = remember { SnackbarHostState() }
    val appScope = rememberCoroutineScope()
    val commands: LibraryCommands = viewModel()
    val scrollStore: LibraryScrollStore = viewModel()
    val context = LocalContext.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var notificationDismissed by rememberSaveable { mutableStateOf(false) }
    val isPlayer = route == PLAYER_ROUTE
    fun play(key: VideoKey) { nav.navigate("player/${Uri.encode(key.serverIdentity)}/${Uri.encode(key.youtubeId)}") { launchSingleTop = true } }
    fun destination(target: String) {
        nav.navigate(target) {
            popUpTo("downloads") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    LaunchedEffect(browse?.notice) { browse?.notice?.let { snackbar.showSnackbar(it); model?.clearNotice() } }
    LaunchedEffect(commands, snackbar) { commands.messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(modifier.fillMaxSize(), topBar = {
        if (!isPlayer) TopAppBar(title = {
            if (route == "settings" || route == "about" || route == "connect") Text(when (route) {
                "about" -> "About"; "connect" -> "Connect to Sniptube"; else -> "Server connection"
            }) else Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(R.drawable.ic_sniptube), null, Modifier.size(28.dp))
                Text("Sniptube", style = MaterialTheme.typography.titleLarge)
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            navigationIcon = { if (route == "settings" || route == "about") IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (route != "settings" && route != "about" && route != "connect") IconButton(onClick = { nav.navigate("settings") { launchSingleTop = true } }) { Icon(Icons.Default.Settings, "Server settings") } })
    }, bottomBar = {
        if (!isPlayer && route != "settings" && route != "about" && route != "connect" && !keyboardVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            listOf("browse" to "Browse", "collections" to "Collections", "downloads" to "Downloads").forEach { (target, label) ->
                NavigationBarItem(selected = route == target, onClick = { destination(target) }, label = { Text(label) }, icon = {
                    Icon(when (target) { "browse" -> Icons.Default.Search; "collections" -> Icons.Default.CollectionsBookmark; else -> Icons.Default.DownloadForOffline }, null)
                })
            }
        }
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        // Downloads is a useful cold-start destination even in airplane mode; no connection gate.
        NavHost(navController = nav, startDestination = initialDestination, modifier = if (isPlayer) Modifier else Modifier.padding(padding).imePadding()) {
            composable("connect") { ConnectionScreen(container, onIdentityChanged = { serverIdentity = it },
                onConnected = { nav.navigate("downloads") { popUpTo("connect") { inclusive = true } } },
                onAbout = { nav.navigate("about") },
                onNotice = { appScope.launch { snackbar.showSnackbar(it) } }, snapshot = snapshot) }
            composable("browse") {
                if (browse != null && model != null && serverIdentity != null) key(serverIdentity) {
                    BrowseScreen(browse, snapshot, actions, commands, model::updateQuery,
                        model::loadNextPage, model::refreshLibrary, { destination("downloads") }, model::retrySearch,
                        scrollStore, serverIdentity.orEmpty(), jobs, transfers)
                } else Text("Set your Sniptube server in Settings to browse videos.")
            }
            composable("downloads") {
                Column {
                    if (!notificationDismissed && Build.VERSION.SDK_INT >= 33 && snapshot.queue.any { it.deviceStage != DeviceStage.Removed && it.deviceStage != DeviceStage.Ready } &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        Row {
                            TextButton(onClick = { notificationDismissed = true; notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.weight(1f)) { Text("Enable sync notifications") }
                            TextButton(onClick = { notificationDismissed = true }) { Text("Not now") }
                        }
                    }
                    DownloadsScreen(snapshot, jobs, transfers, actions, commands, ::play, scrollStore)
                }
            }
            composable("collections") { CollectionsScreen(snapshot, actions, commands, jobs, transfers, ::play, scrollStore) }
            composable("settings") { ConnectionScreen(container, onIdentityChanged = { serverIdentity = it },
                onConnected = { nav.popBackStack() },
                onAbout = { nav.navigate("about") { launchSingleTop = true } },
                onNotice = { appScope.launch { snackbar.showSnackbar(it) } }, snapshot = snapshot) }
            composable("about") { AboutScreen() }
            composable(PLAYER_ROUTE, arguments = listOf(navArgument("server") { type = NavType.StringType }, navArgument("youtube") { type = NavType.StringType })) { player ->
                OfflinePlayerScreen(container, player.arguments?.getString("server").orEmpty(),
                    player.arguments?.getString("youtube").orEmpty(), onBack = { nav.popBackStack() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ConnectionScreen(container: AppContainer, onIdentityChanged: (String) -> Unit,
    onConnected: () -> Unit, onAbout: () -> Unit, onNotice: (String) -> Unit, snapshot: LibrarySnapshot) {
    var url by rememberSaveable { mutableStateOf(container.serverConnections.loadOrNull()?.identity.orEmpty()) }
    var message by rememberSaveable { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    val storage = remember(container) { container.offlineStorageBudget }
    var limit by remember(storage) { mutableStateOf(storage.limitBytes()) }
    val used = container.mediaDownloads.cache.cacheSpace + snapshot.assets.filter { it.kind != com.sniptube.android.data.local.LocalFileKind.Media }.sumOf { it.byteSize }
    val capacity = storage.safeCapacityBytes(used)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun save() {
        if (checking) return
        keyboard?.hide()
        checking = true
        scope.launch {
            try {
                // Persist even if unreachable: offline libraries remain independently accessible.
                val connection = container.serverConnections.update(url)
                onIdentityChanged(connection.identity)
                try { container.apiClient(connection).listVideos() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    onNotice("Server URL saved. Could not connect yet: " +
                        ((error as? SniptubeApiException)?.userMessage ?: error.message ?: "Check your connection."))
                }
                onConnected()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = (error as? SniptubeApiException)?.userMessage ?: error.message ?: "Could not check this connection." }
            finally { checking = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Connect to your Sniptube server", style = MaterialTheme.typography.headlineSmall)
        Text("Search and new downloads use this server. Existing phone copies remain in Downloads when it is unreachable.")
        OutlinedTextField(url, { url = it }, enabled = !checking, singleLine = true, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }))
        Button(onClick = ::save, enabled = !checking, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (checking) "Checking…" else "Save server URL") }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = {
            val saved = container.serverConnections.loadOrNull()?.identity ?: return@OutlinedButton
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(saved)).addCategory(Intent.CATEGORY_BROWSABLE)) }
            catch (error: Exception) { message = "Could not open a browser: ${error.message ?: "No browser available."}" }
        }, enabled = container.serverConnections.loadOrNull() != null,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Default.OpenInBrowser, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Open web version in browser")
        }
        HorizontalDivider()
        Text("Offline storage", style = MaterialTheme.typography.titleMedium)
        Text("${byteText(used)} stored · ${storage.freeBytes()?.let(::byteText) ?: "Free space unknown"} free",
            style = MaterialTheme.typography.bodyMedium)
        Text("Maximum media budget (at least 128 MiB stays free). Choices above the current safe capacity are disabled.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = limit == null, onClick = {
                storage.setLimitBytes(null); limit = null; container.deviceTransfers.refreshPolicy()
            }, label = { Text("No app limit") }, modifier = Modifier.heightIn(min = 48.dp))
            listOf(1L, 2L, 5L, 10L, 20L).forEach { gb ->
                val bytes = gb * com.sniptube.android.data.transfer.OfflineStorageBudget.GIB
                FilterChip(selected = limit == bytes, enabled = capacity == null || bytes <= capacity,
                    onClick = { storage.setLimitBytes(bytes); limit = bytes; container.deviceTransfers.refreshPolicy() },
                    label = { Text("$gb GiB") }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        OutlinedButton(onClick = { confirmingClear = true }, enabled = !clearing && snapshot.queue.any {
            it.deviceStage != DeviceStage.Removed
        }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Default.DeleteSweep, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (clearing) "Clearing offline files…" else "Clear all phone copies")
        }
        HorizontalDivider()
        TextButton(onClick = onAbout, modifier = Modifier.heightIn(min = 48.dp)) { Text("About · version and changelog") }
    }
    if (confirmingClear) AlertDialog(onDismissRequest = { confirmingClear = false },
        title = { Text("Clear all offline files?") },
        text = { Text("This removes local media, thumbnails and subtitles for ${snapshot.queue.count { it.deviceStage != DeviceStage.Removed }} items (about ${byteText(used)} stored). Collections, watched history and server videos remain. Collection videos will need Sync now to download again; use Wi-Fi or confirm cellular data separately.") },
        confirmButton = { Button(onClick = {
            confirmingClear = false
            clearing = true
            scope.launch {
                try {
                    val (count, failures) = container.deviceTransfers.clearAllLocal()
                    onNotice("Cleared $count offline items${if (failures > 0) "; $failures failed" else ""}. Media3 will free cache space shortly.")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onNotice(error.message ?: "Could not clear offline files.") }
                finally { clearing = false }
            }
        }, enabled = !clearing) { Text("Clear local files") } },
        dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("Cancel") } })
}
