package com.sniptube.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class DatabaseConverters {
    @TypeConverter
    fun serverStage(value: String): ServerStage = enumValueOf(value)

    @TypeConverter
    fun serverStage(value: ServerStage): String = value.name

    @TypeConverter
    fun deviceStage(value: String?): DeviceStage? = value?.let(DeviceStage::valueOf)

    @TypeConverter
    fun deviceStage(value: DeviceStage?): String? = value?.name

    @TypeConverter
    fun retryStage(value: String?): RetryStage? = value?.let(RetryStage::valueOf)

    @TypeConverter
    fun retryStage(value: RetryStage?): String? = value?.name

    @TypeConverter
    fun localFileKind(value: String): LocalFileKind = enumValueOf(value)

    @TypeConverter
    fun localFileKind(value: LocalFileKind): String = value.name

    @TypeConverter
    fun assetType(value: String): AssetType = AssetType.valueOf(value)

    @TypeConverter
    fun assetType(value: AssetType): String = value.name
}

@Database(
    entities = [
        OfflineVideoEntity::class,
        QueueIntentEntity::class,
        ServerJobBindingEntity::class,
        DeviceTransferBindingEntity::class,
        LocalFileEntity::class,
        PlaybackProgressEntity::class,
        CollectionEntity::class,
        CollectionMembershipEntity::class,
        KeepOfflineExclusionEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class SniptubeDatabase : RoomDatabase() {
    abstract fun offlineDao(): OfflineDao

    companion object {
        const val DATABASE_NAME = "sniptube-offline.db"

        // Add every future schema migration here. Deliberately no destructive fallback is used.
        val MIGRATIONS: Array<Migration> = arrayOf(object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE device_transfer_bindings ADD COLUMN cacheKey TEXT")
                db.execSQL("ALTER TABLE local_files ADD COLUMN assetType TEXT NOT NULL DEFAULT 'File'")
                db.execSQL("ALTER TABLE local_files ADD COLUMN cacheKey TEXT")
            }
        }, object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN viewed INTEGER NOT NULL DEFAULT 0")
            }
        }, object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offline_videos ADD COLUMN uploadDate TEXT")
            }
        })

        fun build(context: Context): SniptubeDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SniptubeDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
