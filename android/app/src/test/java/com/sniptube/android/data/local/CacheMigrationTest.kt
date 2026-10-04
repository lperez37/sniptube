package com.sniptube.android.data.local

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class CacheMigrationTest {
    @Test fun uploadDateMigrationKeepsExistingVideoMetadata() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "upload-date-migration-test.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE offline_videos (youtubeId TEXT PRIMARY KEY, title TEXT NOT NULL)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO offline_videos VALUES ('old-id', 'Existing offline video')")
            SniptubeDatabase.MIGRATIONS.single { it.startVersion == 3 }.migrate(db)
            db.query("SELECT youtubeId, title, uploadDate FROM offline_videos").use {
                assertTrue(it.moveToFirst())
                assertEquals("old-id", it.getString(0))
                assertEquals("Existing offline video", it.getString(1))
                assertTrue(it.isNull(2))
            }
        } finally { helper.close(); context.deleteDatabase(name) }
    }

    @Test fun existingFileRowsRemainFilesAndNewBindingsGetNullableCacheKeys() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("cache-migration-test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE device_transfer_bindings (transferId TEXT PRIMARY KEY)")
                        db.execSQL("CREATE TABLE local_files (path TEXT PRIMARY KEY)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO device_transfer_bindings VALUES ('old-transfer')")
            db.execSQL("INSERT INTO local_files VALUES ('/files/old.mp4')")
            SniptubeDatabase.MIGRATIONS.first().migrate(db)
            db.query("SELECT cacheKey FROM device_transfer_bindings").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
            }
            db.query("SELECT path, assetType, cacheKey FROM local_files").use {
                assertTrue(it.moveToFirst())
                assertEquals("/files/old.mp4", it.getString(0))
                assertEquals("File", it.getString(1))
                assertTrue(it.isNull(2))
            }
        } finally {
            helper.close()
            context.deleteDatabase("cache-migration-test.db")
        }
    }

    @Test fun oldPlaybackPositionsSurviveViewedColumnMigration() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "viewed-migration-test.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE playback_progress (serverIdentity TEXT NOT NULL, youtubeId TEXT NOT NULL, positionMs INTEGER NOT NULL, durationMs INTEGER, updatedAt INTEGER NOT NULL, PRIMARY KEY(serverIdentity, youtubeId))")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO playback_progress VALUES ('old-server', 'video', 91, 100, 1)")
            SniptubeDatabase.MIGRATIONS.single { it.startVersion == 2 }.migrate(db)
            db.query("SELECT positionMs, durationMs, viewed FROM playback_progress").use {
                assertTrue(it.moveToFirst())
                assertEquals(91, it.getInt(0))
                assertEquals(100, it.getInt(1))
                assertEquals(0, it.getInt(2))
                assertTrue(watchedEnough(it.getLong(0), it.getLong(1)))
            }
        } finally { helper.close(); context.deleteDatabase(name) }
    }
}
