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
    version = 8,
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

        /**
         * 剧集分层：movies 加 seriesKey（分集指向所属系列）。
         *
         * 索引必须一起建：实体上声明了 `indices = [Index("seriesKey")]`，Room 开库时会拿
         * 迁移后的实际 schema 和实体声明对账，**少建索引会直接抛 "Migration didn't properly
         * handle"**。索引名用 Room 的默认规则 `index_<表>_<列>`。
         *
         * 旧数据全是 NULL（= 都是顶层条目），所以升级后海报墙表现不变，重扫一次才会把分集归位。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN seriesKey TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_movies_seriesKey ON movies (seriesKey)")
            }
        }

        /**
         * 体积过滤：libraries 加 minVideoSizeMb。
         *
         * 默认 0 = 不过滤，所以升级后行为不变 —— 用户去库里设了阈值再重扫才会生效
         * （扫描期过滤，老数据不会自己消失）。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE libraries ADD COLUMN minVideoSizeMb INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 「彻底删除」要用的 file_id：movies 加 videoFid / nfoFid。
         *
         * 旧数据全是 NULL，所以升级后「彻底删除」对**没重扫过的**条目拿不到 id ——
         * 那种情况会明确提示"这条是旧索引，重扫一次才能彻底删除"，而不是静默只删本地。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN videoFid TEXT")
                db.execSQL("ALTER TABLE movies ADD COLUMN nfoFid TEXT")
            }
        }

/**
     * 「彻底删除」要连图片一起删：movies 补 posterFid / fanartFid / thumbFid 与 thumbPickCode。
     *
     * 起因是用户实测：彻底删除后目录里只剩 poster.jpg / fanart.jpg / -thumb.jpg，
     * 等于没删干净。图片的 file_id 跟视频一样只有扫描时顺手存才拿得到。
     *
     * 旧数据全是 NULL，下一次扫描会补齐（增量扫描对"视频/nfo 都没变"的目录也会跳过，
     * 所以没重扫过的老条目仍然是"只有视频和 nfo 能删"）。
     */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE movies ADD COLUMN posterFid TEXT")
            db.execSQL("ALTER TABLE movies ADD COLUMN fanartFid TEXT")
            db.execSQL("ALTER TABLE movies ADD COLUMN thumbFid TEXT")
            db.execSQL("ALTER TABLE movies ADD COLUMN thumbPickCode TEXT")
        }
    }

    fun build(context: Context): MediaDatabase =
        Room.databaseBuilder(context, MediaDatabase::class.java, "media.db")
            .addMigrations(
                MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
            )
            .fallbackToDestructiveMigration()
            .build()
    }
}
