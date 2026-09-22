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
        "SELECT mediaKey, title, year, rating, posterPickCode, isEpisodeLike, videoPickCode FROM movies " +
            "WHERE title LIKE '%' || :keyword || '%' " +
            "ORDER BY rating IS NULL, rating DESC LIMIT :limit",
    )
    suspend fun searchByTitle(keyword: String, limit: Int = 100): List<MovieCard>

    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, isEpisodeLike, videoPickCode FROM movies " +
            "ORDER BY rating IS NULL, rating DESC LIMIT :limit",
    )
    fun byRating(limit: Int = 200): Flow<List<MovieCard>>

    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, isEpisodeLike, videoPickCode FROM movies " +
            // 精确等于（影片直接在库根目录）或以 库路径/ 开头：别用 LIKE 'x%'，
            // 否则库 "test" 会把库 "test001" 的影片也收进来
            "WHERE dirPath = :prefix OR dirPath LIKE :prefix || '/%' " +
            "ORDER BY rating IS NULL, rating DESC LIMIT :limit",
    )
    fun byLibraryPath(prefix: String, limit: Int = 200): Flow<List<MovieCard>>

    @Query(
        "SELECT m.mediaKey, m.title, m.year, m.rating, m.posterPickCode, m.isEpisodeLike, m.videoPickCode " +
            "FROM movies m JOIN movie_actors ma ON m.mediaKey = ma.mediaKey " +
            "JOIN actors a ON ma.actorId = a.id WHERE a.name = :name " +
            "ORDER BY m.rating IS NULL, m.rating DESC",
    )
    suspend fun byActor(name: String): List<MovieCard>

    @Query(
        "SELECT m.mediaKey, m.title, m.year, m.rating, m.posterPickCode, m.isEpisodeLike, m.videoPickCode " +
            "FROM movies m JOIN movie_tags mt ON m.mediaKey = mt.mediaKey " +
            "JOIN tags t ON mt.tagId = t.id WHERE t.name = :name " +
            "ORDER BY m.rating IS NULL, m.rating DESC",
    )
    suspend fun byTag(name: String): List<MovieCard>

    @Query("SELECT * FROM movies WHERE mediaKey = :mediaKey")
    suspend fun movie(mediaKey: String): MovieEntity?

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

    // ---- 媒体库（用户手动新建的库，一个云盘根路径一个库）----
    @Insert
    suspend fun insertLibrary(library: MediaLibraryEntity): Long

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
