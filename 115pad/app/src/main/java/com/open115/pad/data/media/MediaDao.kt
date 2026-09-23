package com.open115.pad.data.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 列表项：海报墙卡片只需要标题/年份/评分/键 */
data class MovieCard(
    val mediaKey: String,
    val title: String,
    val year: Int?,
    val rating: Double?,
    val posterPickCode: String?,
    val isEpisodeLike: Boolean,
    /** 主视频 pick_code：海报墙点进去直接播，也用来拼"播完自动下一部"的播放列表 */
    val videoPickCode: String? = null,
    /** 副标题用：nfo 里的分类 */
    val genre: String? = null,
    /** 主视频文件名：画质微标（4K/1080P/HDR）从这里启发式推导 */
    val videoName: String? = null,
)

/** 删库前收集的 pick_code：清该库对应的海报与 nfo 落盘缓存用 */
data class MediaPickCodes(
    val nfoPickCode: String?,
    val posterPickCode: String?,
    val fanartPickCode: String?,
)

/**
 * 删单个条目时要用的文件信息（**这条 + 它名下的分集**）。
 *
 * 那几个 `*Fid` 是云盘 file_id —— `ufile/delete` 只认它，pick_code 不认；
 * 那几个 `*PickCode` 是清本地海报/nfo 落盘缓存用的。
 *
 * `sourceDirKey` 是条目所在目录的 cid：本地删除后要拿它把该目录的扫描状态作废，
 * 否则下次增量扫描会因为"目录没变"整目录跳过，刚删掉的条目再也回不来。
 */
data class MovieFiles(
    val mediaKey: String,
    val videoFid: String?,
    val nfoFid: String?,
    val posterFid: String?,
    val fanartFid: String?,
    val thumbFid: String?,
    val videoPickCode: String?,
    val nfoPickCode: String?,
    val posterPickCode: String?,
    val fanartPickCode: String?,
    val thumbPickCode: String?,
    val sourceDirKey: String,
)

/**
 * 分集要用的"祖先行"：`seriesKey` 取它的 mediaKey，海报/背景取它的图。
 *
 * 只认 `isEpisodeLike = 0` 的行 —— 分集的上层必须是"系列/影片"，不能拿另一集当系列。
 * `ORDER BY (posterPickCode IS NULL)` 让它优先返回带海报的那条（表达式排序，false=0 排前面）。
 */
data class AncestorRow(
    val mediaKey: String,
    val posterPickCode: String?,
    val fanartPickCode: String?,
)

@Dao
interface MediaDao {
    @Upsert
    suspend fun upsertMovie(movie: MovieEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(episodes: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE mediaKey = :mediaKey")
    suspend fun clearEpisodes(mediaKey: String)

    @Transaction
    suspend fun replaceEpisodes(mediaKey: String, episodes: List<EpisodeEntity>) {
        clearEpisodes(mediaKey)
        insertEpisodes(episodes)
    }

    // ---- 演员/标签多对多：ensure-then-link，全在调用方的事务里 ----
    @Query("SELECT id FROM actors WHERE name = :name")
    suspend fun findActorId(name: String): Long?

    @Insert
    suspend fun insertActor(actor: ActorEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkActor(link: MovieActorEntity)

    @Query("SELECT id FROM tags WHERE name = :name")
    suspend fun findTagId(name: String): Long?

    @Insert
    suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkTag(link: MovieTagEntity)

    @Transaction
    suspend fun linkActorByName(mediaKey: String, name: String) {
        val id = findActorId(name) ?: insertActor(ActorEntity(name = name))
        linkActor(MovieActorEntity(mediaKey, id))
    }

    @Transaction
    suspend fun linkTagByName(mediaKey: String, name: String) {
        val id = findTagId(name) ?: insertTag(TagEntity(name = name))
        linkTag(MovieTagEntity(mediaKey, id))
    }

    // ---- 查询：标题搜索 / 评分排序 / 按演员 / 按标签 ----
    // 下面这些都只出**顶层条目**（seriesKey IS NULL）：分集归到系列卡里选集，
    // 不该在海报墙或搜索结果里各占一张卡。
    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
            "WHERE title LIKE '%' || :keyword || '%' AND seriesKey IS NULL " +
            "ORDER BY rating IS NULL, rating DESC LIMIT :limit",
    )
    suspend fun searchByTitle(keyword: String, limit: Int = 100): List<MovieCard>

    /** 全库按评分排（当前无调用方，留着当通用入口）。同样只出顶层条目 */
    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
            "WHERE seriesKey IS NULL " +
            "ORDER BY rating IS NULL, rating DESC LIMIT :limit",
    )
    fun byRating(limit: Int = 200): Flow<List<MovieCard>>

    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
            // 精确等于（影片直接在库根目录）或以 库路径/ 开头：别用 LIKE 'x%'，
            // 否则库 "test" 会把库 "test001" 的影片也收进来。
            // seriesKey IS NULL：海报墙只出顶层条目（影片 / 系列本身），分集归到系列卡里选集
            "WHERE (dirPath = :prefix OR dirPath LIKE :prefix || '/%') AND seriesKey IS NULL " +
            "ORDER BY rating IS NULL, rating DESC LIMIT :limit",
    )
    fun byLibraryPath(prefix: String, limit: Int = 200): Flow<List<MovieCard>>

    /** 多根媒体库：任一路径匹配（精确等于或以其为前缀）即纳入 */
    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
            "WHERE seriesKey IS NULL AND (:p0 = '' OR dirPath = :p0 OR dirPath LIKE :p0 || '/%' " +
            "OR dirPath = :p1 OR dirPath LIKE :p1 || '/%' " +
            "OR dirPath = :p2 OR dirPath LIKE :p2 || '/%' " +
            "OR dirPath = :p3 OR dirPath LIKE :p3 || '/%' " +
            "OR dirPath = :p4 OR dirPath LIKE :p4 || '/%') " +
            "ORDER BY rating IS NULL, rating DESC LIMIT :limit",
    )
    fun byLibraryPaths(p0: String, p1: String, p2: String, p3: String, p4: String, limit: Int = 200): Flow<List<MovieCard>>

    /** 多根媒体库的影片数（超过 5 根时前 5 根之外的用 byLibraryPath 逐个补）。同样只数顶层条目，和墙上看到的张数一致 */
    @Query(
        "SELECT COUNT(*) FROM movies " +
            "WHERE seriesKey IS NULL AND (:p0 = '' OR dirPath = :p0 OR dirPath LIKE :p0 || '/%' " +
            "OR dirPath = :p1 OR dirPath LIKE :p1 || '/%' " +
            "OR dirPath = :p2 OR dirPath LIKE :p2 || '/%' " +
            "OR dirPath = :p3 OR dirPath LIKE :p3 || '/%' " +
            "OR dirPath = :p4 OR dirPath LIKE :p4 || '/%')",
    )
    suspend fun movieCountInPaths(p0: String, p1: String, p2: String, p3: String, p4: String): Int

    @Query(
        "SELECT m.mediaKey, m.title, m.year, m.rating, m.posterPickCode, m.isEpisodeLike, m.videoPickCode, m.genre, m.videoName " +
            "FROM movies m JOIN movie_actors ma ON m.mediaKey = ma.mediaKey " +
            "JOIN actors a ON ma.actorId = a.id WHERE a.name = :name AND m.seriesKey IS NULL " +
            "ORDER BY m.rating IS NULL, m.rating DESC",
    )
    suspend fun byActor(name: String): List<MovieCard>

    @Query(
        "SELECT m.mediaKey, m.title, m.year, m.rating, m.posterPickCode, m.isEpisodeLike, m.videoPickCode, m.genre, m.videoName " +
            "FROM movies m JOIN movie_tags mt ON m.mediaKey = mt.mediaKey " +
            "JOIN tags t ON mt.tagId = t.id WHERE t.name = :name AND m.seriesKey IS NULL " +
            "ORDER BY m.rating IS NULL, m.rating DESC",
    )
    suspend fun byTag(name: String): List<MovieCard>

    /** 系列下的分集（海报墙上点进系列卡后用）。排序交给调用方按集号排（见 episodeSortKey） */
    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
            "WHERE seriesKey = :seriesKey",
    )
    suspend fun episodesOfSeries(seriesKey: String): List<MovieCard>

    /**
     * 这条**以及它名下的分集**的文件信息（删单个条目用）。
     *
     * 一条查询同时服务两件事：那几个 `*Fid` 给云端删除，几个 pickCode 给本地缓存清理，
     * `sourceDirKey` 给扫描状态作废。
     * 系列卡会把整季的分集一起带出来 —— 删系列就该连分集一起删，否则留下的是没有系列的孤儿分集。
     */
    @Query(
        "SELECT mediaKey, videoFid, nfoFid, posterFid, fanartFid, thumbFid, " +
            "videoPickCode, nfoPickCode, posterPickCode, fanartPickCode, thumbPickCode, sourceDirKey " +
            "FROM movies WHERE mediaKey = :key OR seriesKey = :key",
    )
    suspend fun filesOf(key: String): List<MovieFiles>

    @Query("SELECT * FROM movies WHERE mediaKey = :mediaKey")
    suspend fun movie(mediaKey: String): MovieEntity?

    /**
     * 某个目录里那条"系列/影片"行（分集归属 + 海报继承都靠它）。
     *
     * 按 dirPath 扫是必然的（这一列没索引），但只在扫描期、每个分集目录往上查几层。
     */
    @Query(
        "SELECT mediaKey, posterPickCode, fanartPickCode FROM movies " +
            "WHERE dirPath = :dirPath AND isEpisodeLike = 0 " +
            "ORDER BY (posterPickCode IS NULL) LIMIT 1",
    )
    suspend fun ancestorRowOf(dirPath: String): AncestorRow?

    // ---- 删除媒体库：清掉这个库的全部索引 ----
    // 一律用**子查询**而不是 `IN (:keys)` —— SQLite 的变量上限是 999，几千部片的库
    // 直接 "too many SQL variables" 报错。子查询没有这个限制。

    /** 删库前先收集要清缓存的 pick_code（投影，不取整个实体） */
    @Query(
        "SELECT nfoPickCode, posterPickCode, fanartPickCode FROM movies " +
            "WHERE dirPath = :prefix OR dirPath LIKE :prefix || '/%'",
    )
    suspend fun pickCodesInPath(prefix: String): List<MediaPickCodes>

    @Query(
        "DELETE FROM movie_actors WHERE mediaKey IN " +
            "(SELECT mediaKey FROM movies WHERE dirPath = :prefix OR dirPath LIKE :prefix || '/%')",
    )
    suspend fun unlinkActorsInPath(prefix: String)

    @Query(
        "DELETE FROM movie_tags WHERE mediaKey IN " +
            "(SELECT mediaKey FROM movies WHERE dirPath = :prefix OR dirPath LIKE :prefix || '/%')",
    )
    suspend fun unlinkTagsInPath(prefix: String)

    @Query(
        "DELETE FROM episodes WHERE mediaKey IN " +
            "(SELECT mediaKey FROM movies WHERE dirPath = :prefix OR dirPath LIKE :prefix || '/%')",
    )
    suspend fun deleteEpisodesInPath(prefix: String)

    @Query("DELETE FROM movies WHERE dirPath = :prefix OR dirPath LIKE :prefix || '/%'")
    suspend fun deleteMoviesInPath(prefix: String)

    /** 扫描状态也要清：不清的话重新建一个同路径的库，增量扫描会把这些目录当成"已扫过"而全部跳过 */
    @Query("DELETE FROM scan_state WHERE dirPath = :prefix OR dirPath LIKE :prefix || '/%'")
    suspend fun deleteScanStateInPath(prefix: String)

    /** 删除一个库根下的全部索引（影片 + 演员/标签关联 + 分集 + 扫描状态）。多根库对每根各调一次 */
    @Transaction
    suspend fun deleteLibraryContent(prefix: String) {
        unlinkActorsInPath(prefix)
        unlinkTagsInPath(prefix)
        deleteEpisodesInPath(prefix)
        deleteMoviesInPath(prefix)
        deleteScanStateInPath(prefix)
    }

    // ---- 重扫后的陈旧条目清理 ----
    // 必须清，两个理由：
    //  ① 云盘上删掉的片，不清就永远留在库里（重扫只 upsert，从不删）；
    //  ② mediaKey 的取值会变：nfo 解析成功后 uniqueTmdbid 有值，key 从"目录前缀"
    //     变成 "tmdb-348"，旧键的行会留下来 —— 海报墙上同一部片出现两次，
    //     其中一次没有简介和评分（2026-09-22 实测踩到）。

    /** 某个目录下已索引的影片键 */
    @Query("SELECT mediaKey FROM movies WHERE sourceDirKey = :dirCid")
    suspend fun mediaKeysInDir(dirCid: String): List<String>

    @Query("DELETE FROM movie_actors WHERE mediaKey IN (:keys)")
    suspend fun unlinkActors(keys: List<String>)

    @Query("DELETE FROM movie_tags WHERE mediaKey IN (:keys)")
    suspend fun unlinkTags(keys: List<String>)

    @Query("DELETE FROM episodes WHERE mediaKey IN (:keys)")
    suspend fun deleteEpisodesOf(keys: List<String>)

    @Query("DELETE FROM movies WHERE mediaKey IN (:keys)")
    suspend fun deleteMovieRows(keys: List<String>)

    /**
     * 删除影片及其关联。Room 这几张表**没有建外键**，所以演员/标签/分集的关联
     * 得自己一起清 —— 只删 movies 会留下一堆指向不存在影片的关联行。
     */
    @Transaction
    suspend fun deleteMovies(keys: List<String>) {
        if (keys.isEmpty()) return
        unlinkActors(keys)
        unlinkTags(keys)
        deleteEpisodesOf(keys)
        deleteMovieRows(keys)
    }

    @Query("SELECT * FROM episodes WHERE mediaKey = :mediaKey ORDER BY season, episode")
    suspend fun episodesOf(mediaKey: String): List<EpisodeEntity>

    @Query("SELECT name FROM actors JOIN movie_actors ON actors.id = actorId WHERE mediaKey = :mediaKey")
    suspend fun actorsOf(mediaKey: String): List<String>

    @Query("SELECT name FROM tags JOIN movie_tags ON tags.id = tagId WHERE mediaKey = :mediaKey")
    suspend fun tagsOf(mediaKey: String): List<String>

    // ---- 扫描状态 ----
    @Upsert
    suspend fun upsertScanState(state: ScanStateEntity)

    @Query("SELECT * FROM scan_state WHERE dirKey = :dirKey")
    suspend fun scanState(dirKey: String): ScanStateEntity?

    @Query("SELECT * FROM scan_state ORDER BY scannedAt DESC")
    fun scanStates(): Flow<List<ScanStateEntity>>

    @Query("SELECT COUNT(*) FROM scan_state WHERE status = 1")
    suspend fun scanningCount(): Int

    /**
     * 把一个目录的扫描状态作废，让下次**增量**扫描重新处理它。
     *
     * 增量跳过的条件是 `status == 2 && cloudUpt 未变`（见 MediaScanner）。而「仅从媒体库移除」
     * 不碰云端，目录的 upt 自然不变 —— 于是那个目录永远被跳过，刚删掉的条目再也回不来，
     * 只有全量扫描才能重新入库。这里把状态和 upt 一起抹掉，两条判据同时失效。
     *
     * 不用删行：留着 dirPath / scannedAt，重扫一遍状态就回来了。
     */
    @Query("UPDATE scan_state SET status = 0, cloudUpt = 0 WHERE dirKey = :dirKey")
    suspend fun invalidateScanState(dirKey: String)

    /**
     * 这个目录里还剩多少条索引。
     *
     * 「彻底删除」的收尾判据：为 0 才说明用户把整个目录的内容都从库里删掉了，
     * 这时目录里剩下的素材图（`folder.jpg`、`xxx-logo.png` 这种没有归属的）才可以一并清掉。
     */
    @Query("SELECT COUNT(*) FROM movies WHERE sourceDirKey = :dirKey")
    suspend fun rowsInDir(dirKey: String): Int

    // ---- 媒体库（用户手动新建的库，一个库可含多个云盘根路径）----
    @Insert
    suspend fun insertLibrary(library: MediaLibraryEntity): Long

    @Query(
        "UPDATE libraries SET name = :name, rootCid = :rootCid, rootPath = :rootPath, " +
            "rateLimitMs = :rateLimitMs, autoScanOnStart = :autoScanOnStart, minVideoSizeMb = :minVideoSizeMb " +
            "WHERE id = :id",
    )
    suspend fun updateLibrary(
        id: Long,
        name: String,
        rootCid: String,
        rootPath: String,
        rateLimitMs: Long,
        autoScanOnStart: Boolean,
        minVideoSizeMb: Int,
    )

    @Query("SELECT * FROM libraries ORDER BY createdAt DESC")
    fun libraries(): Flow<List<MediaLibraryEntity>>

    @Query("SELECT * FROM libraries WHERE id = :id")
    suspend fun library(id: Long): MediaLibraryEntity?

    /** 库内影片数（与 byLibraryPath 同一套路径匹配规则）。**也要 seriesKey IS NULL**，否则会连分集一起数进去 */
    @Query(
        "SELECT COUNT(*) FROM movies " +
            "WHERE (dirPath = :prefix OR dirPath LIKE :prefix || '/%') AND seriesKey IS NULL",
    )
    suspend fun movieCountIn(prefix: String): Int
    @Query("DELETE FROM libraries WHERE id = :id")
    suspend fun deleteLibrary(id: Long)

    @Transaction
    suspend fun upsertMovieWithPeople(
        movie: MovieEntity,
        actors: List<String>,
        tags: List<String>,
        episodes: List<EpisodeEntity>,
    ) {
        upsertMovie(movie)
        replaceEpisodes(movie.mediaKey, episodes)
        actors.forEach { linkActorByName(movie.mediaKey, it) }
        tags.forEach { linkTagByName(movie.mediaKey, it) }
    }
}
