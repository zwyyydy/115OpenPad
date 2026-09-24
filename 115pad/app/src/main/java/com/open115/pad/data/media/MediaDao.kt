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
    /**
     * 背景图 pick_code。海报墙只在**没有海报**时用它：拿它裁一张兜底海报
     * （见 [com.open115.pad.data.ImageUrlResolver.croppedPosterIfCached]）。
     *
     * 为什么加进这个投影（它本来有七八个查询在用）：海报墙的每张卡都得知道"兜底图在哪"，
     * 一条条查库比多带一列贵得多；而且 Room 是**编译期**校验列的，漏改一个查询直接编译失败，
     * 不会静默少一列。
     */
    val fanartPickCode: String? = null,
    /**
     * 剧照的 pick_code（\n 连接）。墙只在**没有 fanart.jpg** 时用它取第一张当兜底源 ——
     * 详情页本来就是这么兜的（见 [backgroundSourceOf]），两边取到不同的图就会出现
     * "详情页背景是 A、裁出来的海报是 B"。
     */
    val extraFanartPickCodes: String? = null,
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

/** 一条"作品 → 名字"（标签名 / 演员名，给列表筛选批量取用，见 [MediaDao.tagNamesOf] / [MediaDao.actorNamesOf]） */
data class MovieNameRow(val mediaKey: String, val name: String)

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
    /** 剧照 pick_code（\n 连接，见 [encodePickCodes]）：清本地缓存用 */
    val extraFanartPickCodes: String? = null,
    /**
     * 剧照目录 / 演员头像目录的 cid：彻底删除时按它列一次子目录换 file_id。
     * 这两个目录里的文件列**父目录**列不出来（见 deleteMovie 的注释）。
     */
    val extraFanartDirCid: String? = null,
    val actorsDirCid: String? = null,
)

/**
 * 详情页要的演员：名字 + 头像 pick_code（没有头像就是 null，UI 退回首字母圆底）。
 *
 * 头像存在 actors 表上（按名字唯一），所以这里读出来的就是"同名复用"后的结果 ——
 * 同一个演员在别的影片里扫到过头像，这部片也直接有。
 */
data class ActorCard(
    val name: String,
    val avatarPickCode: String? = null,
)

/**
 * 演员 + 它的头像落在哪儿（删影片时判断这个头像还能不能删）。
 *
 * [avatarDirCid] 是头像文件所在 `.actors` 目录的 cid：删掉这部片之后它可能已经不在
 * 本次要清理的目录里了（比如这个头像是**之前删掉的另一部片**提供的），
 * 那种情况下要单独去那个目录里删这一个文件。
 */
data class ActorAvatarRef(
    val id: Long,
    val name: String,
    val avatarPickCode: String,
    val avatarDirCid: String?,
)

/**
 * 删影片时**哪些头像可以跟着删**：只有这个演员在库里再没有别的作品了才删。
 *
 * ★ 还有别的作品在用就必须留着（连同云端文件一起留）：头像按名字全局唯一，
 *   而"同名复用"靠的就是这一份文件 —— 删了别的片就再也配不上头像，
 *   要等它们各自的目录被重扫才会好（`.actors` 里还有副本的话）。
 *
 * 判据是 movie_actors 里的**幸存链接**（本次要删的影片已经排除），
 * 纯逻辑所以单独提出来测。
 */
fun avatarsToDelete(
    avatars: List<ActorAvatarRef>,
    stillUsedNames: Set<String>,
): List<ActorAvatarRef> = avatars.filter { it.name !in stillUsedNames }

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

    /**
     * 关联演员并带上头像（来自影片目录里的 `.actors/<演员名>.jpg`）。
     *
     * ★ **已有头像就不覆盖** —— 这就是"同一个演员只需要一份头像"的实现：
     *   A 片扫到过就存下了，B 片（同演员、目录里没 `.actors`）读到的自然是同一份；
     *   即使 B 片自己也有一个副本，也不会去改写指向，于是那份副本永远不会被下载、
     *   更不会被缓存第二遍（缓存按 pick_code 命名，改了指向就会多一份字节）。
     *
     * 只在演员行**还没有头像**时补写：老库升级上来时全是 null，重扫一次就补上。
     */
    @Transaction
    suspend fun linkActorWithAvatar(
        mediaKey: String,
        name: String,
        avatarPickCode: String,
        avatarDirCid: String?,
    ) {
        val existing = findActorId(name)
        if (existing == null) {
            linkActor(MovieActorEntity(mediaKey, insertActor(
                ActorEntity(name = name, avatarPickCode = avatarPickCode, avatarDirCid = avatarDirCid),
            )))
            return
        }
        setActorAvatarIfAbsent(existing, avatarPickCode, avatarDirCid)
        linkActor(MovieActorEntity(mediaKey, existing))
    }

    /** 补头像：只在原本为空时写（空串也算空 —— 老数据/异常写入可能是空串而不是 NULL） */
    @Query(
        "UPDATE actors SET avatarPickCode = :pickCode, avatarDirCid = :dirCid " +
            "WHERE id = :id AND (avatarPickCode IS NULL OR avatarPickCode = '')",
    )
    suspend fun setActorAvatarIfAbsent(id: Long, pickCode: String, dirCid: String?)

    @Transaction
    suspend fun linkTagByName(mediaKey: String, name: String) {
        val id = findTagId(name) ?: insertTag(TagEntity(name = name))
        linkTag(MovieTagEntity(mediaKey, id))
    }

    // ---- 查询：搜索 / 评分排序 / 按演员 / 按标签 ----
    // 下面这些都只出**顶层条目**（seriesKey IS NULL）：分集归到系列卡里选集，
    // 不该在海报墙或搜索结果里各出一次。

    /**
     * **全局搜索**：片名 / 演员 / 标签，一网打尽，**不按媒体库过滤**（跨所有库）。
     *
     * 三个来源合并成"命中集合"再取顶层条目：
     *  - 片名 `LIKE`（片名不分词，子串匹配就够；用户搜的是"示例影片"这种短词）
     *  - 演员名 `LIKE` → 命中分集时用 `COALESCE(seriesKey, mediaKey)` 归到它所属的系列卡
     *    （演员挂在分集行上，直接拿 mediaKey 去比会一张卡都出不来）
     *  - 标签名 `LIKE` → 同上
     *
     * 用 UNION（去重）而不是三个 OR 子查询：一部片同时命中片名和演员时只出一张卡。
     */
    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, fanartPickCode, extraFanartPickCodes, " +
            "isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
            "WHERE seriesKey IS NULL AND mediaKey IN (" +
            "SELECT mediaKey FROM movies WHERE title LIKE '%' || :keyword || '%' " +
            "UNION " +
            "SELECT COALESCE(x.seriesKey, x.mediaKey) FROM movies x " +
            "JOIN movie_actors ma ON x.mediaKey = ma.mediaKey " +
            "JOIN actors a ON ma.actorId = a.id WHERE a.name LIKE '%' || :keyword || '%' " +
            "UNION " +
            "SELECT COALESCE(x.seriesKey, x.mediaKey) FROM movies x " +
            "JOIN movie_tags mt ON x.mediaKey = mt.mediaKey " +
            "JOIN tags t ON mt.tagId = t.id WHERE t.name LIKE '%' || :keyword || '%') " +
            "ORDER BY " +
                // 排序按序号分派（见 WorksSort）：一条查询管全部，不写六条几乎一样的 SQL。
                // NULL 在 SQLite 的 DESC 里自然沉底，所以"没评分/没日期"的行不会顶到最前。
                "CASE WHEN :sort = 0 THEN rating END DESC, " +
                "CASE WHEN :sort = 1 THEN premiered END DESC, " +
                "CASE WHEN :sort = 2 THEN COALESCE(dateAdded, scannedAt) END DESC, " +
                "CASE WHEN :sort = 3 THEN title END ASC, " +
                "CASE WHEN :sort = 4 THEN RANDOM() END, " +
                "title ASC LIMIT :limit",
    )
    suspend fun searchAll(keyword: String, limit: Int = 200, sort: Int = 0): List<MovieCard>

    /** 全库按评分排（当前无调用方，留着当通用入口）。同样只出顶层条目 */
    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, fanartPickCode, extraFanartPickCodes, isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
            "WHERE seriesKey IS NULL " +
            "ORDER BY rating IS NULL, rating DESC LIMIT :limit",
    )
    fun byRating(limit: Int = 200): Flow<List<MovieCard>>

    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, fanartPickCode, extraFanartPickCodes, isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
            // 精确等于（影片直接在库根目录）或以 库路径/ 开头：别用 LIKE 'x%'，
            // 否则库 "test" 会把库 "test001" 的影片也收进来。
            // seriesKey IS NULL：海报墙只出顶层条目（影片 / 系列本身），分集归到系列卡里选集
            "WHERE (dirPath = :prefix OR dirPath LIKE :prefix || '/%') AND seriesKey IS NULL " +
            "ORDER BY " +
                // 排序按序号分派（见 WorksSort）：一条查询管全部，不写六条几乎一样的 SQL。
                // NULL 在 SQLite 的 DESC 里自然沉底，所以"没评分/没日期"的行不会顶到最前。
                "CASE WHEN :sort = 0 THEN rating END DESC, " +
                "CASE WHEN :sort = 1 THEN premiered END DESC, " +
                "CASE WHEN :sort = 2 THEN COALESCE(dateAdded, scannedAt) END DESC, " +
                "CASE WHEN :sort = 3 THEN title END ASC, " +
                "CASE WHEN :sort = 4 THEN RANDOM() END, " +
                "title ASC LIMIT :limit",
    )
    fun byLibraryPath(prefix: String, limit: Int = 200, sort: Int = 0): Flow<List<MovieCard>>

    /**
     * 多根媒体库：任一路径匹配（精确等于或以其为前缀）即纳入。
     *
     * ☠ 每个参数都要先判 `<> ''`：库里不足 5 根时调用方会把空位补成空串，
     *   而 `dirPath LIKE :pN || '/%'` 在 `:pN = ''` 时等于 `LIKE '/%'` ——
     *   **匹配所有以 `/` 开头的路径**，也就是别的库整片漏进来。
     *   实测：根为 `/示例目录`（选目录器在根层给的就是带前导斜杠的形态）的库，
     *   会把它的条目漏进根为 `test/刮削测试` 的库，18 部 = 7 + 11。
     */
    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, fanartPickCode, extraFanartPickCodes, isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
            "WHERE seriesKey IS NULL AND (" +
            "(:p0 <> '' AND (dirPath = :p0 OR dirPath LIKE :p0 || '/%')) " +
            "OR (:p1 <> '' AND (dirPath = :p1 OR dirPath LIKE :p1 || '/%')) " +
            "OR (:p2 <> '' AND (dirPath = :p2 OR dirPath LIKE :p2 || '/%')) " +
            "OR (:p3 <> '' AND (dirPath = :p3 OR dirPath LIKE :p3 || '/%')) " +
            "OR (:p4 <> '' AND (dirPath = :p4 OR dirPath LIKE :p4 || '/%'))) " +
            "ORDER BY " +
                // 排序按序号分派（见 WorksSort）：一条查询管全部，不写六条几乎一样的 SQL。
                // NULL 在 SQLite 的 DESC 里自然沉底，所以"没评分/没日期"的行不会顶到最前。
                "CASE WHEN :sort = 0 THEN rating END DESC, " +
                "CASE WHEN :sort = 1 THEN premiered END DESC, " +
                "CASE WHEN :sort = 2 THEN COALESCE(dateAdded, scannedAt) END DESC, " +
                "CASE WHEN :sort = 3 THEN title END ASC, " +
                "CASE WHEN :sort = 4 THEN RANDOM() END, " +
                "title ASC LIMIT :limit",
    )
    fun byLibraryPaths(
        p0: String, p1: String, p2: String, p3: String, p4: String,
        limit: Int = 200,
        sort: Int = 0,
    ): Flow<List<MovieCard>>

    /**
     * 本库可以拿来当**海报墙背景**的图：顶层条目的 fanart（= 详情页那张背景图）。
     *
     * 单独一条查询、只取一列，而不是往 [MovieCard] 上加字段 —— 那个投影有七八个查询在用，
     * 加一列要同时改一圈，而这一个用途只需要 pick_code 本身。
     *
     * 只取 `seriesKey IS NULL`：分集的图是从系列继承来的，同一张会重复出现几十次，
     * 轮播时看着就是"卡住不动"。
     *
     * ☠ 空串判断同上（见 [byLibraryPaths]）：漏了它，背景轮播会把别的库的 fanart 也轮进来。
     */
    @Query(
        "SELECT fanartPickCode FROM movies " +
            "WHERE seriesKey IS NULL AND fanartPickCode IS NOT NULL AND fanartPickCode != '' AND (" +
            "(:p0 <> '' AND (dirPath = :p0 OR dirPath LIKE :p0 || '/%')) " +
            "OR (:p1 <> '' AND (dirPath = :p1 OR dirPath LIKE :p1 || '/%')) " +
            "OR (:p2 <> '' AND (dirPath = :p2 OR dirPath LIKE :p2 || '/%')) " +
            "OR (:p3 <> '' AND (dirPath = :p3 OR dirPath LIKE :p3 || '/%')) " +
            "OR (:p4 <> '' AND (dirPath = :p4 OR dirPath LIKE :p4 || '/%')))",
    )
    suspend fun backdropsInPaths(p0: String, p1: String, p2: String, p3: String, p4: String): List<String>

    /** 单根版：多根库超过 5 根时逐根补（与 byLibraryPath 的分工一致） */
    @Query(
        "SELECT fanartPickCode FROM movies " +
            "WHERE seriesKey IS NULL AND fanartPickCode IS NOT NULL AND fanartPickCode != '' " +
            "AND (dirPath = :prefix OR dirPath LIKE :prefix || '/%')",
    )
    suspend fun backdropsInPath(prefix: String): List<String>

    /**
     * 这个目录里各条目用到的海报 pick_code（去重）。
     *
     * 增量扫描**跳过**的目录靠它补海报：跳过 = 不重新索引，也就没有"顺手预取"那一步，
     * 于是清过缓存 / 新装机的机器跑增量扫描时，海报墙还得一张张现下。
     * 命中的不会产生请求（见 MediaScanner.prefetchPoster），所以代价只跟缺多少张成正比。
     */
    @Query(
        "SELECT DISTINCT posterPickCode FROM movies " +
            "WHERE sourceDirKey = :dirKey AND posterPickCode IS NOT NULL AND posterPickCode != ''",
    )
    suspend fun posterPickCodesInDir(dirKey: String): List<String>

    /** 多根媒体库的影片数（超过 5 根时前 5 根之外的用 byLibraryPath 逐个补）。同样只数顶层条目，和墙上看到的张数一致 */
    @Query(
        "SELECT COUNT(*) FROM movies " +
            "WHERE seriesKey IS NULL AND (" +
            "(:p0 <> '' AND (dirPath = :p0 OR dirPath LIKE :p0 || '/%')) " +
            "OR (:p1 <> '' AND (dirPath = :p1 OR dirPath LIKE :p1 || '/%')) " +
            "OR (:p2 <> '' AND (dirPath = :p2 OR dirPath LIKE :p2 || '/%')) " +
            "OR (:p3 <> '' AND (dirPath = :p3 OR dirPath LIKE :p3 || '/%')) " +
            "OR (:p4 <> '' AND (dirPath = :p4 OR dirPath LIKE :p4 || '/%')))",
    )
    suspend fun movieCountInPaths(p0: String, p1: String, p2: String, p3: String, p4: String): Int

    /**
     * 某个演员的全部作品。
     *
     * ★ **不按媒体库过滤**：演员不属于某一个库，A 库和 B 库里同一个演员的作品要一起列出来
     *   （需求原话："生效范围包括所有媒体库"）。
     *
     * ★ 剧集包要绕一道：演员挂在**分集**行上（整季 nfo 在系列根，演员在每一集的 nfo 里），
     *   而这里只出顶层条目（海报墙的口径）—— 所以除了直接命中的顶层条目，还要把
     *   "命中的分集所属的系列"一起列出来，否则点一部剧里的演员会得到 0 结果。
     */
    @Query(
        "SELECT m.mediaKey, m.title, m.year, m.rating, m.posterPickCode, m.fanartPickCode, " +
            "m.extraFanartPickCodes, m.isEpisodeLike, m.videoPickCode, m.genre, m.videoName FROM movies m " +
            "WHERE m.seriesKey IS NULL AND (" +
            "m.mediaKey IN (SELECT ma.mediaKey FROM movie_actors ma JOIN actors a ON ma.actorId = a.id WHERE a.name = :name) " +
            "OR m.mediaKey IN (SELECT s.seriesKey FROM movies s JOIN movie_actors ma2 ON s.mediaKey = ma2.mediaKey " +
            "JOIN actors a2 ON ma2.actorId = a2.id WHERE a2.name = :name AND s.seriesKey IS NOT NULL)) " +
            "ORDER BY " +
                // 排序按序号分派（见 WorksSort）：一条查询管全部，不写六条几乎一样的 SQL。
                // NULL 在 SQLite 的 DESC 里自然沉底，所以"没评分/没日期"的行不会顶到最前。
                "CASE WHEN :sort = 0 THEN rating END DESC, " +
                "CASE WHEN :sort = 1 THEN premiered END DESC, " +
                "CASE WHEN :sort = 2 THEN COALESCE(dateAdded, scannedAt) END DESC, " +
                "CASE WHEN :sort = 3 THEN title END ASC, " +
                "CASE WHEN :sort = 4 THEN RANDOM() END, " +
                "title ASC LIMIT 1000",
    )
    suspend fun byActor(name: String, sort: Int = 0): List<MovieCard>

    /** 某个标签的全部作品。口径与 [byActor] 一致：跨库、只出顶层条目、剧集归到系列卡 */
    @Query(
        "SELECT m.mediaKey, m.title, m.year, m.rating, m.posterPickCode, m.fanartPickCode, " +
            "m.extraFanartPickCodes, m.isEpisodeLike, m.videoPickCode, m.genre, m.videoName FROM movies m " +
            "WHERE m.seriesKey IS NULL AND (" +
            "m.mediaKey IN (SELECT mt.mediaKey FROM movie_tags mt JOIN tags t ON mt.tagId = t.id WHERE t.name = :name) " +
            "OR m.mediaKey IN (SELECT s.seriesKey FROM movies s JOIN movie_tags mt2 ON s.mediaKey = mt2.mediaKey " +
            "JOIN tags t2 ON mt2.tagId = t2.id WHERE t2.name = :name AND s.seriesKey IS NOT NULL)) " +
            "ORDER BY " +
                // 排序按序号分派（见 WorksSort）：一条查询管全部，不写六条几乎一样的 SQL。
                // NULL 在 SQLite 的 DESC 里自然沉底，所以"没评分/没日期"的行不会顶到最前。
                "CASE WHEN :sort = 0 THEN rating END DESC, " +
                "CASE WHEN :sort = 1 THEN premiered END DESC, " +
                "CASE WHEN :sort = 2 THEN COALESCE(dateAdded, scannedAt) END DESC, " +
                "CASE WHEN :sort = 3 THEN title END ASC, " +
                "CASE WHEN :sort = 4 THEN RANDOM() END, " +
                "title ASC LIMIT 1000",
    )
    suspend fun byTag(name: String, sort: Int = 0): List<MovieCard>

    /** 系列下的分集（海报墙上点进系列卡后用）。排序交给调用方按集号排（见 episodeSortKey） */
    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, fanartPickCode, extraFanartPickCodes, isEpisodeLike, videoPickCode, genre, videoName FROM movies " +
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
            "videoPickCode, nfoPickCode, posterPickCode, fanartPickCode, thumbPickCode, sourceDirKey, " +
            "extraFanartPickCodes, extraFanartDirCid, actorsDirCid " +
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

    /**
     * 详情页的演员（带头像）。按演员 id 排：同一个演员表在库里是稳定的，
     * 排序交给 SQL 比在 UI 层每次重排省事。
     *
     * 取代了原来的 `SELECT name` 版本 —— 详情页现在两样都要，没必要查两遍。
     */
    @Query(
        "SELECT actors.name AS name, actors.avatarPickCode AS avatarPickCode " +
            "FROM actors JOIN movie_actors ON actors.id = movie_actors.actorId " +
            "WHERE movie_actors.mediaKey = :mediaKey ORDER BY actors.id",
    )
    suspend fun actorCardsOf(mediaKey: String): List<ActorCard>

    /**
     * 这些影片用到的演员里**有头像的**那些（删影片时逐个判断头像还能不能删）。
     *
     * 必须在删本地索引**之前**查 —— 删完 movie_actors 就查不出"这部片用了谁"了。
     */
    @Query(
        "SELECT DISTINCT a.id AS id, a.name AS name, a.avatarPickCode AS avatarPickCode, " +
            "a.avatarDirCid AS avatarDirCid FROM actors a JOIN movie_actors ma ON ma.actorId = a.id " +
            "WHERE ma.mediaKey IN (:keys) AND a.avatarPickCode IS NOT NULL",
    )
    suspend fun actorAvatarsOfMovies(keys: List<String>): List<ActorAvatarRef>

    /**
     * 这些演员里**还有别的作品留在库里**的（本次要删的影片不算）。
     *
     * 判据就是 movie_actors 里还剩链接：有链接 = 详情页还会显示这个演员，
     * 头像删了他就配不上了。
     */
    @Query(
        "SELECT DISTINCT a.name FROM actors a JOIN movie_actors ma ON ma.actorId = a.id " +
            "WHERE a.id IN (:actorIds) AND ma.mediaKey NOT IN (:doomedKeys)",
    )
    suspend fun actorNamesStillUsed(actorIds: List<Long>, doomedKeys: List<String>): List<String>

    /** 清掉这些演员的头像引用（只在云端文件真的删掉之后调） */
    @Query("UPDATE actors SET avatarPickCode = NULL, avatarDirCid = NULL WHERE id IN (:ids)")
    suspend fun clearActorAvatars(ids: List<Long>)

    @Query("SELECT name FROM tags JOIN movie_tags ON tags.id = tagId WHERE mediaKey = :mediaKey")
    suspend fun tagsOf(mediaKey: String): List<String>

    /**
     * 一批作品的**演员**（同 [tagNamesOf]：关联表里的东西，一次查完给筛选用）。
     */
    @Query(
        "SELECT movie_actors.mediaKey AS mediaKey, actors.name AS name " +
            "FROM movie_actors JOIN actors ON movie_actors.actorId = actors.id " +
            "WHERE movie_actors.mediaKey IN (:keys)",
    )
    suspend fun actorNamesOf(keys: List<String>): List<MovieNameRow>

    /**
     * 一批作品的标签（**一次查完**，给列表筛选用）。
     *
     * `MovieCard` 投影里没有标签（它在一张关联表里），而筛选要按标签判断 ——
     * 每部片现查一次就是 N 次查询，所以这里一次把整批的取回来，调用方建映射再用。
     */
    @Query(
        "SELECT movie_tags.mediaKey AS mediaKey, tags.name AS name " +
            "FROM movie_tags JOIN tags ON movie_tags.tagId = tags.id " +
            "WHERE movie_tags.mediaKey IN (:keys)",
    )
    suspend fun tagNamesOf(keys: List<String>): List<MovieNameRow>

    /**
     * 这个目录里有多少条说明"分 CD 的合并结果还没到位、得重扫一遍"的行。
     *
     * 分 CD 合并是后加的逻辑，增量扫描本来会跳过没变化的目录 —— 那样老数据永远合不起来。
     * 四种"没到位"的样子：
     *  - 挂到别人名下（老数据里分集归了别的系列）
     *  - 该挂到合成行名下却是顶层（老数据里各占一张卡）
     *  - **`seriesKey = mediaKey` 的自引用**：第一张盘和合成行撞过键时留下的坏数据
     *  - **合成行缺海报、而目录里明明有带海报的行**：合成卡的图是"第一张盘优先、
     *    没有就借任意一张盘的"，借图那条也是后加的（实测 `ABC-204` 那种海报只挂在 cd2 上）
     *
     * 重扫一遍之后四种都不成立 → 永远为 0，不会反复重扫。
     */
    @Query(
        "SELECT COUNT(*) FROM movies WHERE sourceDirKey = :dirKey AND (" +
            "(seriesKey IS NOT NULL AND seriesKey != :base) OR " +
            "(seriesKey IS NULL AND mediaKey != :base) OR " +
            "seriesKey = mediaKey OR " +
            "(mediaKey = :base AND posterPickCode IS NULL AND EXISTS (" +
            "SELECT 1 FROM movies o WHERE o.sourceDirKey = :dirKey AND o.posterPickCode IS NOT NULL)))",
    )
    suspend fun cdGroupNeedsRescan(dirKey: String, base: String): Int

    /**
     * 哪些目录还是"元数据与视频分成两条"的老样子（判据在 [DirectorySniffer.clusterFiles] 里）。
     *
     * 与 [cdGroupNeedsRescan] 同一个理由：合并逻辑是后加的，而增量扫描会跳过没变化的目录 ——
     * 老数据永远合不起来。区别是这里**一次把全库查完**，扫描开始时取一次、之后每个目录只做一次
     * 集合判断：一条条按目录查的话（三个子查询都按 sourceDirKey 过滤，而这一列没有索引）
     * 就变成每目录 3 次全表扫描。
     *
     * 形状正是合并前的样子：顶层恰好两条 —— 一条"有视频没 nfo"、一条"有 nfo 没视频"。
     * 合并之后这个目录只剩一条（既带视频又带 nfo）→ 不再出现在结果里，不会反复逼着重扫；
     * 剧集/季目录（每条都带视频）、分 CD 目录（顶层只有合成行那一条）也都不匹配。
     */
    @Query(
        "SELECT sourceDirKey FROM movies WHERE seriesKey IS NULL AND sourceDirKey != '' " +
            "GROUP BY sourceDirKey HAVING COUNT(*) = 2 " +
            "AND SUM(videoPickCode IS NOT NULL AND nfoPickCode IS NULL) = 1 " +
            "AND SUM(videoPickCode IS NULL AND nfoPickCode IS NOT NULL) = 1",
    )
    suspend fun dirsNeedingOrphanMerge(): List<String>

    /**
     * 这个目录里还有多少条**没记上侧挂素材**的行（只统计这个目录真正有的那几样）。
     *
     * 用于升级补数据：v9 及更早的库里这两列全是 NULL，而"没补过"和"这个目录根本没有
     * `.actors`/`extrafanart`"在库里的样子**完全一样** —— 所以调用方先看父目录列表里
     * 有没有这两个目录项（免费拿到），有哪样才统计哪样。两个条件都满足才去列一次，
     * 补完这些行都带上了 cid，之后这个判断永远为假。
     */
    @Query(
        "SELECT COUNT(*) FROM movies WHERE sourceDirKey = :dirKey AND (" +
            "(:wantFanart AND extraFanartDirCid IS NULL) OR (:wantActors AND actorsDirCid IS NULL))",
    )
    suspend fun rowsMissingSideArt(dirKey: String, wantFanart: Boolean, wantActors: Boolean): Int

    @Query(
        "UPDATE movies SET extraFanartPickCodes = :fanartCodes, extraFanartDirCid = :fanartDirCid, " +
            "actorsDirCid = :actorsDirCid WHERE sourceDirKey = :dirKey",
    )
    suspend fun updateSideArtInDir(
        dirKey: String,
        fanartCodes: String?,
        fanartDirCid: String?,
        actorsDirCid: String?,
    )

    /**
     * 升级补数据：把一个目录的剧照/头像补到它名下**每一条**上（剧照是整目录共用的素材，
     * 与扫描时的挂法一致），并按演员名把头像补进 actors 表（已有头像的不动）。
     *
     * 只在增量扫描的"跳过"分支里、且确实有行没补过时才调（见 MediaScanner）——
     * 补完这些行就都带上目录 cid 了，这个判断之后永远为假，不会再列第二次。
     */
    @Transaction
    suspend fun backfillSideArtInDir(
        dirKey: String,
        fanartCodes: String?,
        fanartDirCid: String?,
        actorsDirCid: String?,
        actorAvatars: Map<String, String>,
    ) {
        updateSideArtInDir(dirKey, fanartCodes, fanartDirCid, actorsDirCid)
        if (actorAvatars.isEmpty()) return
        for (key in mediaKeysInDir(dirKey)) {
            actorCardsOf(key).forEach { actor ->
                actorAvatars[normalizeActorName(actor.name)]?.let { pc ->
                    linkActorWithAvatar(key, actor.name, pc, actorsDirCid)
                }
            }
        }
    }

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
            "rateLimitMs = :rateLimitMs, autoScanOnStart = :autoScanOnStart, minVideoSizeMb = :minVideoSizeMb, " +
            "autoScanIntervalHours = :autoScanIntervalHours " +
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
        autoScanIntervalHours: Int,
    )

    /**
     * 记下"这个库刚扫完一轮"。
     *
     * 只在 [MediaScanner.runScan] 收尾时调用，而且**一个目录都没跑完就不记**
     * （那种情况是列目录就抛了，多半是网络问题，下次启动该再试）。
     */
    @Query("UPDATE libraries SET lastScanAt = :at WHERE id = :id")
    suspend fun markLibraryScanned(id: Long, at: Long)

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
        /** 归一化演员名 → 头像 pick_code（来自 `.actors/`，见 [actorAvatarFilesOf]） */
        actorAvatars: Map<String, String> = emptyMap(),
        /** 头像文件所在目录 cid，随头像一起存（彻底删除时判断该不该清掉这个引用） */
        actorsDirCid: String? = null,
    ) {
        upsertMovie(movie)
        replaceEpisodes(movie.mediaKey, episodes)
        // ★ 演员/标签**整表替换**：这次扫出来的就是权威结果，先清旧关联再挂新的。
        //
        // 不清的话旧名字会一直赖在影片上 —— 实测踩到：演员名的提取规则一改
        // （目录名猜的 `-4K-C 示例演员` → 从 `.actors` 取的真名 `示例演员`），
        // 重扫之后那条影片**同时挂着两个名字**，详情页两个演员卡并排显示。
        // nfo 改演员表、换刮削器同理。
        unlinkActors(listOf(movie.mediaKey))
        unlinkTags(listOf(movie.mediaKey))
        actors.forEach { name ->
            val avatar = actorAvatars[normalizeActorName(name)]
            if (avatar != null) {
                linkActorWithAvatar(movie.mediaKey, name, avatar, actorsDirCid)
            } else {
                linkActorByName(movie.mediaKey, name)
            }
        }
        tags.forEach { linkTagByName(movie.mediaKey, it) }
    }

    // ---------------- 观影历史 ----------------

    /**
     * 观影历史（最近看的在最前）+ **库内元数据**。
     *
     * join 用 `movies.videoPickCode = watch_history.itemKey`：命中说明这条在某个媒体库里，
     * 海报和标题现取（库里换了海报/改了名，历史页立刻跟着变，不会像存快照那样过期）。
     * 分集的 `seriesKey` 再 join 一次系列拿系列名，列表里显示"剧名 · 第 7 集"。
     *
     * join 不上时（片子后来被删了、库被删了）三个字段全是 null —— 界面退回用播放时记的
     * [WatchHistoryRow.name]（文件名），列表**不会因此空一行**。
     */
    @Query(
        "SELECT h.itemKey AS itemKey, h.name AS name, h.positionMs AS positionMs, " +
            "h.durationMs AS durationMs, h.updatedAt AS updatedAt, h.local AS local, " +
            "COALESCE(m.posterPickCode, s.posterPickCode) AS posterPickCode, " +
            "COALESCE(s.title, m.title) AS libraryTitle, m.title AS ownTitle " +
            "FROM watch_history h " +
            "LEFT JOIN movies m ON m.videoPickCode = h.itemKey " +
            "LEFT JOIN movies s ON s.mediaKey = m.seriesKey " +
            "ORDER BY h.updatedAt DESC LIMIT :limit",
    )
    fun watchHistoryRows(limit: Int = 500): Flow<List<WatchHistoryRow>>

    @Query("SELECT * FROM watch_history WHERE itemKey = :key")
    suspend fun watchHistoryOf(key: String): WatchHistoryEntity?

    /** 一条只需一个 pick_code/uri：再看一次就是覆盖同一条，不堆重复行 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchHistory(row: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE itemKey = :key")
    suspend fun deleteWatchHistory(key: String)

    @Query("DELETE FROM watch_history")
    suspend fun clearWatchHistory()

    // ---------------- 扫描记录（媒体库自己的流水） ----------------

    /**
     * 记录一次扫描。**顺手裁掉多余的**：只留最近 [SCAN_LOG_KEEP] 条 ——
     * 每次启动都可能自动扫一轮，不裁的话这张表会一直长（而且它每行都带一串新增键）。
     */
    @Transaction
    suspend fun addScanLog(row: ScanLogEntity) {
        insertScanLog(row)
        trimScanLogs(SCAN_LOG_KEEP)
    }

    @Insert
    suspend fun insertScanLog(row: ScanLogEntity): Long

    /** 只保留最新 [keep] 条（扫一次插一条，按完成时间倒序数） */
    @Query(
        "DELETE FROM scan_log WHERE id NOT IN (" +
            "SELECT id FROM scan_log ORDER BY at DESC LIMIT :keep)",
    )
    suspend fun trimScanLogs(keep: Int)

    @Query("SELECT * FROM scan_log ORDER BY at DESC LIMIT :limit")
    fun scanLogs(limit: Int = 200): Flow<List<ScanLogEntity>>

    @Query("DELETE FROM scan_log")
    suspend fun clearScanLogs()

    /**
     * 一批 mediaKey → 海报墙同款卡片（[MovieCard]）。扫描记录里"新增的影片"就用它渲染成图。
     *
     * 分块查：一次扫描可能新增几百部，`IN (…)` 的变量数是 SQLite 有上限的（老版本 999），
     * 撞上就是一句难懂的 SQL 错误 —— 这里按 300 一批拼起来，调用方不用管。
     * 查不到的键（片子后来被删了）自然不在结果里。
     */
    @Transaction
    suspend fun movieCardsByKeys(keys: List<String>): List<MovieCard> {
        if (keys.isEmpty()) return emptyList()
        return keys.chunked(300).flatMap { movieCardsByKeysChunk(it) }
    }

    @Query(
        "SELECT mediaKey, title, year, rating, posterPickCode, fanartPickCode, extraFanartPickCodes, " +
            "isEpisodeLike, videoPickCode, genre, videoName FROM movies WHERE mediaKey IN (:keys)",
    )
    suspend fun movieCardsByKeysChunk(keys: List<String>): List<MovieCard>

    private companion object {
        /** 扫描记录最多留这么多条（每次启动的自动扫描也算一次，不裁会一直涨） */
        const val SCAN_LOG_KEEP = 200
    }
}
