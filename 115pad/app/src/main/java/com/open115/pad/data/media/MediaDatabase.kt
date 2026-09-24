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
    version = 11,
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

    /**
     * 增量扫描改用**目录指纹**：scan_state 加 dirFingerprint。
     *
     * 起因是 upt 判据发现不了删除/改名/移入（详见 [dirFingerprintOf] 的注释）。
     * 老数据这一列是空串 → 与算出来的指纹不相等 → **每个目录会被重扫一遍**（就一次），
     * 重扫时补上指纹，之后恢复正常跳过。这是有意的：宁可多扫一轮，也不能让老数据
     * 一直带着"永远跳过"的错判。
     */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE scan_state ADD COLUMN dirFingerprint TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * 剧照（`extrafanart/`）与演员头像（`.actors/`）：movies 加三列、actors 加两列。
     *
     * 老数据全是 NULL = 没有剧照、演员没头像，详情页表现跟以前一样（剧照区不出现、
     * 演员仍是药丸）。
     *
     * ★ 这两列只能靠重扫填上，而增量扫描的跳过判据（目录指纹）跟升级前**一模一样** ——
     *   照理该在这里把指纹清空、逼每个目录重扫一遍。没那么做：**增量扫描的跳过分支里
     *   加了"补数据"**（父目录列表里能看到侧挂素材目录、而库里还有行没补过时才列一次，
     *   见 MediaScanner），代价一样是每部片 1~2 次请求，但不用把整库重新索引一遍、
     *   也不用让用户莫名其妙等一次长扫描。
     */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE movies ADD COLUMN extraFanartPickCodes TEXT")
            db.execSQL("ALTER TABLE movies ADD COLUMN extraFanartDirCid TEXT")
            db.execSQL("ALTER TABLE movies ADD COLUMN actorsDirCid TEXT")
            db.execSQL("ALTER TABLE actors ADD COLUMN avatarPickCode TEXT")
            db.execSQL("ALTER TABLE actors ADD COLUMN avatarDirCid TEXT")
        }
    }

    /**
     * nfo 解析结果整份存一列（`movies.nfoJson`）：把解析补全到四十多个字段
     * （原名/标语/时长/国家/语言/编剧/合集/技术参数/音轨字幕/角色名…）之后，
     * 逐列建表要一次几十条 ALTER，且以后每加一个字段还要再来一次 —— 长尾字段一律进 JSON。
     *
     * 老库这一列是 NULL：详情页只显示原有字段，**重扫一次**（或详情页的按需自愈）补上。
     * 顺便把 nfo 的落盘缓存 key 升了一版（见 MediaScanner.NFO_CACHE_VERSION）——
     * 不升的话缓存里那些"只解析了 13 个字段"的旧结果会被继续命中，新字段永远是空的。
     */
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE movies ADD COLUMN nfoJson TEXT")
        }
    }

    fun build(context: Context): MediaDatabase =
        Room.databaseBuilder(context, MediaDatabase::class.java, "media.db")
            .addMigrations(
                MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11,
            )
            .fallbackToDestructiveMigration()
            .build()
    }
}
