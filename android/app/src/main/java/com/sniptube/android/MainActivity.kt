package com.sniptube.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import com.sniptube.android.ui.SniptubeApp
import com.sniptube.android.ui.theme.SniptubeTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as SniptubeApplication).container.acquisitionCoordinator.enterForeground()
        (application as SniptubeApplication).container.deviceTransfers.enterForeground()
    }

    override fun onStop() {
        (application as SniptubeApplication).container.acquisitionCoordinator.leaveForeground()
        (application as SniptubeApplication).container.deviceTransfers.leaveForeground()
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SniptubeApplication).container
        setContent {
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
            SniptubeTheme(darkTheme = true) {
                SniptubeApp(container = container)
            }
        }
    }
}
