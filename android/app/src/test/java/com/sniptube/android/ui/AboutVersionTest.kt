package com.sniptube.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sniptube.android.BuildConfig
import com.sniptube.android.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AboutVersionTest {
    @Test fun packagedChangelogStartsWithTheInstalledVersion() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val first = context.resources.openRawResource(R.raw.changelog).bufferedReader().use { it.readLine() }
        assertTrue("About history must match the build shown to users", first.startsWith("## ${BuildConfig.VERSION_NAME} "))
        assertTrue("Shared APKs need a new Android version code", BuildConfig.VERSION_CODE >= 2)
    }
}
