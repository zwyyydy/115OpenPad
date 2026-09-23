package com.open115.pad.data.media

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MovieEntity::class,
        EpisodeEntity::class,
        ActorEntity::class,
        MovieActorEntity::class,
        TagEntity::class,
        MovieTagEntity::class,
        ScanStateEntity::class,
        MediaLibraryEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        /** libraries 表增量补列，不动已有数据 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE libraries ADD COLUMN rateLimitMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE libraries ADD COLUMN autoScanOnStart INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): MediaDatabase =
            Room.databaseBuilder(context, MediaDatabase::class.java, "media.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
    }
}
