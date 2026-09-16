package com.open115.pad.data

/**
 * 图片业务实体：把 115 两套字段命名归一成一份。
 *
 * - 文件列表接口（GET /open/ufile/files）字段：fid / fn / fs / pc / thumb / uo
 * - 搜索接口（GET /open/ufile/search）字段：file_id / file_name / file_size / pick_code
 *
 * 两者在项目里已经统一映射成 [FileItem]（见 SearchRawItem.toItem()），
 * 这里只做一次很薄的转接：图片相关代码只认 ImageMediaItem，不再关心字段来源。
 */
data class ImageMediaItem(
    val fileId: String?,
    val fileName: String,
    val fileSize: Long,
    val pickCode: String?,
    /** 列表直接给的压缩缩略图，适合网格与"大图首帧" */
    val thumbnailUrl: String?,
    /** 列表直接给的原图直链（有则可完全跳过 downurl 解析） */
    val originUrl: String?,
) {
    /** 网格 / 大图首帧用的地址：有缩略图用缩略图，实在没有退回原图 */
    val preview: String? get() = thumbnailUrl ?: originUrl

    /**
     * 是否按"超大图"处理（单反原图 / 长图）。
     * 判定依据是文件体积——像素尺寸要等解码后才知道，到时再按 [HUGE_MAX_PIXELS] 复核。
     * 超大图不允许 Coil 一次性整张解码，走 BitmapRegionDecoder 只渲染可视区域。
     */
    val isHugeBySize: Boolean get() = fileSize > HUGE_FILE_BYTES

    companion object {
        /** 体积阈值：> 15MB 视为超大图 */
        const val HUGE_FILE_BYTES: Long = 15L * 1024 * 1024

        /** 像素阈值：最长边 > 4096px 视为超大图 */
        const val HUGE_MAX_EDGE: Int = 4096
    }
}

/** 文件列表 / 搜索结果 → 图片业务实体 */
fun FileItem.toImageMediaItem(): ImageMediaItem = ImageMediaItem(
    fileId = fid,
    fileName = fn,
    fileSize = fs,
    pickCode = pc,
    thumbnailUrl = thumb,
    originUrl = uo,
)
