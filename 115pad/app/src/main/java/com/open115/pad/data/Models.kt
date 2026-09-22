package com.open115.pad.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 115 接口里数字/布尔经常以字符串形式出现，这里做宽容反序列化。
 */
object LenientLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG)
    override fun deserialize(decoder: Decoder): Long {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return when (el) {
            is JsonPrimitive -> el.longOrNull ?: el.content.toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }
    }
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return when (el) {
            is JsonPrimitive -> el.intOrNull ?: el.content.toDoubleOrNull()?.toInt() ?: 0
            else -> 0
        }
    }
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

object LenientDoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientDouble", PrimitiveKind.DOUBLE)
    override fun deserialize(decoder: Decoder): Double {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return when (el) {
            is JsonPrimitive -> el.doubleOrNull ?: el.content.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
}

object LenientBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientBoolean", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Boolean {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        if (el is JsonNull || el is JsonArray || el is JsonObject) return false
        val p = el as JsonPrimitive
        return if (p.isString) p.content == "1" || p.content.equals("true", ignoreCase = true)
        else p.content == "1" || p.content == "true"
    }
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

@Serializable
data class ApiEnvelope<T>(
    @Serializable(with = LenientBooleanSerializer::class) val state: Boolean = false,
    val message: String? = null,
    val code: Int? = null,
    val data: T? = null,
    val error: String? = null,
    val errno: Int? = null,
) {
    val ok: Boolean get() = state && (code == null || code == 0)
}

// ---------------- 授权 ----------------

@Serializable
data class DeviceCodeData(
    val uid: String? = null,
    @Serializable(with = LenientLongSerializer::class) val time: Long = 0,
    val qrcode: String? = null,
    val sign: String? = null,
)

@Serializable
data class QrStatusData(
    val msg: String? = null,
    @Serializable(with = LenientIntSerializer::class) val status: Int = 0,
    val version: String? = null,
)

@Serializable
data class TokenData(
    val access_token: String? = null,
    val refresh_token: String? = null,
    @Serializable(with = LenientLongSerializer::class) val expires_in: Long = 0,
)

// ---------------- 用户 ----------------

@Serializable
data class UserInfo(
    @SerialName("user_id") @Serializable(with = LenientLongSerializer::class) val userId: Long = 0,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("user_face_m") val avatar: String? = null,
    @SerialName("rt_space_info") val space: SpaceInfo? = null,
    @SerialName("vip_info") val vip: VipInfo? = null,
)

@Serializable
data class SpaceInfo(
    @SerialName("all_total") val total: SizeEntry? = null,
    @SerialName("all_remain") val remain: SizeEntry? = null,
    @SerialName("all_use") val used: SizeEntry? = null,
)

@Serializable
data class SizeEntry(
    @Serializable(with = LenientLongSerializer::class) val size: Long = 0,
    @SerialName("size_format") val sizeFormat: String? = null,
)

@Serializable
data class VipInfo(
    @SerialName("level_name") val levelName: String? = null,
    @Serializable(with = LenientLongSerializer::class) val expire: Long = 0,
)

// ---------------- 文件 ----------------

@Serializable
data class FileItem(
    val fid: String? = null,
    val pid: String? = null,
    @Serializable(with = LenientIntSerializer::class) val fc: Int = 1, // 0 文件夹 1 文件
    val fn: String = "",
    @Serializable(with = LenientLongSerializer::class) val fs: Long = 0,
    val ico: String? = null,
    val pc: String? = null,
    val sha1: String? = null,
    @Serializable(with = LenientIntSerializer::class) val ism: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val isv: Int = 0,
    val thumb: String? = null,
    val v_img: String? = null,
    val fco: String? = null,
    val uo: String? = null,
    @Serializable(with = LenientLongSerializer::class) val play_long: Long = 0,
    @Serializable(with = LenientLongSerializer::class) val upt: Long = 0,
    @Serializable(with = LenientLongSerializer::class) val uppt: Long = 0,
) {
    val isDir: Boolean get() = fc == 0
}

@Serializable
data class PathNode(
    val name: String = "",
    val cid: String? = null,
    val pid: String? = null,
)

@Serializable
data class FilesPage(
    @Serializable(with = LenientLongSerializer::class) val count: Long = 0,
    @SerialName("data") val items: List<FileItem> = emptyList(),
    val cid: String? = null,
    val path: List<PathNode> = emptyList(),
    val offset: Int? = null,
    val limit: Int? = null,
)

/**
 * 按文件名判断是不是视频（115 搜索接口不返回 isv，归一化时按扩展名推断）。
 * 集合与 ui.components.videoExts / FilterRules.TYPE_VIDEO 对齐（跨层不好互引，各自维护）。
 */
fun isVideoFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in setOf(
        "mkv", "mp4", "avi", "mov", "wmv", "flv", "m4v", "ts", "m2ts", "rmvb", "rm",
        "webm", "mpg", "mpeg", "vob", "3gp", "asf",
    )
}

/** 搜索接口的字段名与列表接口不同，这里归一成 FileItem。 */
@Serializable
data class SearchRawItem(
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") @Serializable(with = LenientLongSerializer::class) val fileSize: Long = 0,
    @SerialName("pick_code") val pickCode: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("file_category") val fileCategory: String? = null,
    val ico: String? = null,
    val sha1: String? = null,
    @SerialName("user_ptime") @Serializable(with = LenientLongSerializer::class) val userPtime: Long = 0,
) {
    fun toItem() = FileItem(
        fid = fileId,
        pid = parentId,
        // 接口有时给数字 0（不是字符串 "0"），只按字符串比较会把文件夹判成文件
        fc = if (fileCategory?.toIntOrNull() == 0) 0 else 1,
        fn = fileName ?: "",
        fs = fileSize,
        ico = ico,
        pc = pickCode,
        sha1 = sha1,
        // 搜索接口不返回 isv，视频按扩展名补齐——否则点击搜索结果里的视频走不到播放分支
        isv = if (fileName != null && isVideoFile(fileName)) 1 else 0,
        uppt = userPtime,
    )
}

@Serializable
data class SearchPage(
    @Serializable(with = LenientLongSerializer::class) val count: Long = 0,
    val data: List<SearchRawItem> = emptyList(),
)

@Serializable
data class FolderAddData(
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("file_name") val fileName: String? = null,
)

// ---------------- 视频 ----------------
// 播放接口返回结构多变（definition_list_new 为映射、data 可能为空数组等），改在 RawParsers.kt 手动解析

/**
 * 播放器用的播放列表条目。
 * 只需要"播放凭据 + 标题"两样：pc=pick_code 用于取流，fn 用于标题显示。
 * 由文件页按当前视图顺序构建后经 Intent（JSON 字符串）传给播放器，用于上一集/下一集。
 */
@Serializable
data class PlaylistEntry(
    val pc: String,
    val fn: String,
    /** 文件 ID：播放列表内直接删除源文件（ufile/delete 的 file_ids）用；旧队列可为空 */
    val fid: String? = null,
)

// ---------------- 云下载 ----------------

@Serializable
data class OfflineTask(
    @SerialName("info_hash") val infoHash: String = "",
    val name: String? = null,
    @Serializable(with = LenientLongSerializer::class) val size: Long = 0,
    @SerialName("percentDone") @Serializable(with = LenientDoubleSerializer::class) val percentDone: Double = 0.0,
    @Serializable(with = LenientIntSerializer::class) val status: Int = 0, // -1 失败 0 分配中 1 下载中 2 成功
    @SerialName("add_time") @Serializable(with = LenientLongSerializer::class) val addTime: Long = 0,
    @SerialName("last_update") @Serializable(with = LenientLongSerializer::class) val lastUpdate: Long = 0,
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("delete_file_id") val deleteFileId: String? = null,
    val url: String? = null,
    @SerialName("wp_path_id") val wpPathId: String? = null,
    @SerialName("play_long") @Serializable(with = LenientLongSerializer::class) val playLong: Long = 0,
)

@Serializable
data class OfflinePage(
    val page: Int? = null,
    @SerialName("page_count") val pageCount: Int? = null,
    val count: Int? = null,
    val tasks: List<OfflineTask> = emptyList(),
)

@Serializable
data class OfflineQuota(
    val count: Int? = null,
    val surplus: Int? = null,
    val used: Int? = null,
    @SerialName("package") val packages: List<QuotaPackage> = emptyList(),
)

@Serializable
data class QuotaPackage(
    val name: String? = null,
    @Serializable(with = LenientIntSerializer::class) val count: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val surplus: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val used: Int = 0,
)

// ---------------- 回收站 ----------------

@Serializable
data class RecycleItem(
    val id: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val dtime: Long,
    val thumbUrl: String? = null,
    val cid: String? = null,
    val parentName: String? = null,
    val pickCode: String? = null,
    @Serializable(with = LenientIntSerializer::class) val status: Int = 0,
)
