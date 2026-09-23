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
    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
            "WHERE title LIKE '%' || :keyword || '%' " +
            "ORDER BY rating IS NULL, rating DESC LIMIT :limit",
    )
    suspend fun searchByTitle(keyword: String, limit: Int = 100): List<MovieCard>

    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
            "ORDER BY rating IS NULL, rating DESC LIMIT :limit",
    )
    fun byRating(limit: Int = 200): Flow<List<MovieCard>>

    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
            // 精确等于（影片直接在库根目录）或以 库路径/ 开头：别用 LIKE 'x%'，
            // 否则库 "test" 会把库 "test001" 的影片也收进来
            "WHERE dirPath = :prefix OR dirPath LIKE :prefix || '/%' " +
            "ORDER BY rating IS NULL, rating DESC LIMIT :limit",
    )
    fun byLibraryPath(prefix: String, limit: Int = 200): Flow<List<MovieCard>>

    /** 多根媒体库：任一路径匹配（精确等于或以其为前缀）即纳入 */
    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
            "WHERE (:p0 = '' OR dirPath = :p0 OR dirPath LIKE :p0 || '/%' " +
            "OR dirPath = :p1 OR dirPath LIKE :p1 || '/%' " +
            "OR dirPath = :p2 OR dirPath LIKE :p2 || '/%' " +
            "OR dirPath = :p3 OR dirPath LIKE :p3 || '/%' " +
            "OR dirPath = :p4 OR dirPath LIKE :p4 || '/%') " +
            "ORDER BY rating IS NULL, rating DESC LIMIT :limit",
    )
    fun byLibraryPaths(p0: String, p1: String, p2: String, p3: String, p4: String, limit: Int = 200): Flow<List<MovieCard>>

    /** 多根媒体库的影片数（超过 5 根时前 5 根之外的用 byLibraryPath 逐个补） */
    @Query(
        "SELECT COUNT(*) FROM movies " +
            "WHERE (:p0 = '' OR dirPath = :p0 OR dirPath LIKE :p0 || '/%' " +
            "OR dirPath = :p1 OR dirPath LIKE :p1 || '/%' " +
            "OR dirPath = :p2 OR dirPath LIKE :p2 || '/%' " +
            "OR dirPath = :p3 OR dirPath LIKE :p3 || '/%' " +
            "OR dirPath = :p4 OR dirPath LIKE :p4 || '/%')",
    )
    suspend fun movieCountInPaths(p0: String, p1: String, p2: String, p3: String, p4: String): Int

    @Query(
        "SELECT m.mediaKey, m.title, m.year, m.rating, m.posterPickCode, m.isEpisodeLike, m.videoPickCode, m.genre, m.videoName " +
            "FROM movies m JOIN movie_actors ma ON m.mediaKey = ma.mediaKey " +
            "JOIN actors a ON ma.actorId = a.id WHERE a.name = :name " +
            "ORDER BY m.rating IS NULL, m.rating DESC",
    )
    suspend fun byActor(name: String): List<MovieCard>

    @Query(
        "SELECT m.mediaKey, m.title, m.year, m.rating, m.posterPickCode, m.isEpisodeLike, m.videoPickCode, m.genre, m.videoName " +
            "FROM movies m JOIN movie_tags mt ON m.mediaKey = mt.mediaKey " +
            "JOIN tags t ON mt.tagId = t.id WHERE t.name = :name " +
            "ORDER BY m.rating IS NULL, m.rating DESC",
    )
    suspend fun byTag(name: String): List<MovieCard>

    @Query("SELECT * FROM movies WHERE mediaKey = :mediaKey")
    suspend fun movie(mediaKey: String): MovieEntity?

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

    // ---- 媒体库（用户手动新建的库，一个库可含多个云盘根路径）----
    @Insert
    suspend fun insertLibrary(library: MediaLibraryEntity): Long

    @Query(
        "UPDATE libraries SET name = :name, rootCid = :rootCid, rootPath = :rootPath, rateLimitMs = :rateLimitMs, autoScanOnStart = :autoScanOnStart WHERE id = :id",
    )
    suspend fun updateLibrary(id: Long, name: String, rootCid: String, rootPath: String, rateLimitMs: Long, autoScanOnStart: Boolean)

    @Query("SELECT * FROM libraries ORDER BY createdAt DESC")
    fun libraries(): Flow<List<MediaLibraryEntity>>

    @Query("SELECT * FROM libraries WHERE id = :id")
    suspend fun library(id: Long): MediaLibraryEntity?

    /** 库内影片数（与 byLibraryPath 同一套路径匹配规则） */
    @Query(
        "SELECT COUNT(*) FROM movies " +
            "WHERE dirPath = :prefix OR dirPath LIKE :prefix || '/%'",
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
