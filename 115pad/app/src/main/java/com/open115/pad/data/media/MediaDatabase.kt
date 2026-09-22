package com.open115.pad.data.media

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 2,
    exportSchema = false,
)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        fun build(context: Context): MediaDatabase =
            Room.databaseBuilder(context, MediaDatabase::class.java, "media.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
