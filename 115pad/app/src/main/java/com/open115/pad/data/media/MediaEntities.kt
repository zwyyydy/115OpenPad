package com.open115.pad.data.media

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ---------------- 媒体库（类 Yamby）Room 实体 ----------------
// 数据源是 115 网盘上的 Emby 范式目录（两种实测结构：{tmdbid-…} 前缀聚类 / 演员目录番号聚类）。
// DB 是索引缓存，权威仍是云盘上的 .nfo；scan_state 记录每个目录的扫描状态支撑手动扫描可续跑。

@Entity(tableName = "movies")
data class MovieEntity(
    // 唯一键：tmdbid（示例影片式）或番号如 ABC-301（演员式）
    @PrimaryKey val mediaKey: String,
    val title: String = "",
    val year: Int? = null,
    val rating: Double? = null,
    val plot: String? = null,
    val genre: String? = null,
    /** 番号式资源=剧标记（无 tmdbid、按演员组织）；Emby 式资源=false */
    val isEpisodeLike: Boolean = false,
    /** 云盘里的影片目录 id（cid），直达播放/重新扫描用 */
    val dirCid: String? = null,
    val dirPath: String? = null,
    /** 主视频文件的 pick_code（多视频时取第一个） */
    val videoPickCode: String? = null,
    val videoName: String? = null,
    /** 海报（竖版）/背景（横版）文件的 pick_code，加载时解析直链 */
    val posterPickCode: String? = null,
    val fanartPickCode: String? = null,
    /** nfo 文件的 pick_code 与云盘 upt，增量重扫时跳过未变化的条目 */
    val nfoPickCode: String? = null,
    val nfoUpt: Long = 0,
    val sourceDirKey: String = "",
    val scannedAt: Long = 0,
)

@Entity(tableName = "episodes", primaryKeys = ["mediaKey", "episodeKey"])
data class EpisodeEntity(
    val mediaKey: String,
    /** 剧内集标识：文件名公共前缀（S01E02 / 02 等） */
    val episodeKey: String,
    val season: Int? = null,
    val episode: Int? = null,
    val videoPickCode: String? = null,
    val videoName: String? = null,
    val thumbPickCode: String? = null,
    val nfoPickCode: String? = null,
)

@Entity(tableName = "actors", indices = [Index("name", unique = true)])
data class ActorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "movie_actors",
    primaryKeys = ["mediaKey", "actorId"],
    indices = [Index("actorId")],
)
data class MovieActorEntity(
    val mediaKey: String,
    val actorId: Long,
)

@Entity(tableName = "tags", indices = [Index("name", unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "movie_tags",
    primaryKeys = ["mediaKey", "tagId"],
    indices = [Index("tagId")],
)
data class MovieTagEntity(
    val mediaKey: String,
    val tagId: Long,
)

/** 每个目录的扫描状态：手动触发、可续跑、upt 增量重扫 */
@Entity(tableName = "scan_state", primaryKeys = ["dirKey"])
data class ScanStateEntity(
    val dirKey: String,
    val dirPath: String = "",
    /** 0 未扫 / 1 进行中 / 2 完成 */
    val status: Int = 0,
    /** 目录的最近修改时间（列表 upt），未变则跳过 */
    val cloudUpt: Long = 0,
    val scannedAt: Long = 0,
)

/** 用户建的媒体库：一个云盘根路径 = 一个库（如「示例系列」「演员目录」） */
@Entity(tableName = "libraries")
data class MediaLibraryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 云盘根目录 cid */
    val rootCid: String,
    val rootPath: String,
    val createdAt: Long = 0,
)
