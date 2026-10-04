package com.sniptube.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.sniptube.android.BuildConfig
import com.sniptube.android.R

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val changelog = remember(context) {
        context.resources.openRawResource(R.raw.changelog).bufferedReader().use { it.readLines() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${context.getString(R.string.app_name)} for Android", style = MaterialTheme.typography.headlineMedium)
        Text("Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("Search, sync and watch your Sniptube library offline. Clips and GIFs stay in the web app.",
            style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { links.openUri("http://wiki/services/sniptube") }) {
            Text("Open Sniptube documentation")
        }
        HorizontalDivider()
        Text("Changelog", style = MaterialTheme.typography.headlineSmall)
        changelog.forEach { line -> when {
            line.startsWith("## ") -> Text(line.removePrefix("## "),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            line.startsWith("- ") -> Text("• ${line.removePrefix("- ")}",
                style = MaterialTheme.typography.bodyMedium)
            line.isBlank() -> Spacer(Modifier.height(4.dp))
            else -> Text(line, style = MaterialTheme.typography.bodyMedium)
        } }
    }
}
