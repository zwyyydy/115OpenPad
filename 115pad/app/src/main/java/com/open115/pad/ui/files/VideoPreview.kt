package com.open115.pad.ui.files

import com.open115.pad.data.FileItem
import com.open115.pad.ui.components.videoExts

/**
 * 视频"预览图"的文件名约定与列表后处理。
 *
 * 背景：115 不给视频缩略图（列表响应里视频的 `thumb` 恒为空），文件页的视频条目只有
 * 彩色类型图标。在播放器里长按「截图」可以把当前画面存成这个视频的预览图、上传到
 * 视频所在目录（命名 `<视频名去扩展名>预览图.jpg`）；列表里就**借它的缩略图**显示
 * （见 [applyVideoPreviews]），而预览图文件本身**从列表里隐藏** —— 不然同一个视频
 * 会出现两条：视频 + 预览图。
 */
const val VIDEO_PREVIEW_SUFFIX = "预览图"

/** `1.mp4` → `1预览图.jpg`（多段扩展名按最后一段切：`abc.1080p.mp4` → `abc.1080p预览图.jpg`） */
fun videoPreviewFileName(videoName: String): String =
    videoName.substringBeforeLast('.', videoName) + VIDEO_PREVIEW_SUFFIX + ".jpg"

/** 视频名去扩展名（预览图按它配对） */
private fun baseNameOf(name: String): String = name.substringBeforeLast('.', name)

private val previewNameRegex = Regex(
    "^(.+)" + Regex.escape(VIDEO_PREVIEW_SUFFIX) + "\\.(jpg|jpeg|png)$",
    RegexOption.IGNORE_CASE,
)

/** 是不是"某视频的预览图"；是 → 返回它对应的视频基名，不是 → null */
fun videoPreviewBaseName(fileName: String): String? =
    previewNameRegex.matchEntire(fileName)?.groupValues?.get(1)

/** 这个条目是视频吗（判据与 iconForItem 一致） */
private fun isVideoFile(item: FileItem): Boolean =
    !item.isDir && (item.isv == 1 || item.ico?.lowercase() in videoExts)

/**
 * 列表后处理：隐藏"视频的预览图"，并把它的缩略图借给对应的视频条目。
 *
 * - 配对规则：预览图 `<基名>预览图.jpg` 必须配到**同目录真有的**视频 `<基名>.<扩展名>` ——
 *   配不到就照常显示（用户手动传的同名图、视频已删后留下的孤儿图，都不该被静默吃掉）。
 * - 借的是预览图条目的 `thumb`（图片缩略图直链），没有就退回原图 `uo`；都没有就不借。
 * - **只在"一个目录"的列表上调用**：搜索结果跨目录，同名会误配 —— 调用方在搜索态跳过它
 *   （见 FilesViewModel 里 display 的构建）。
 */
fun applyVideoPreviews(items: List<FileItem>): List<FileItem> {
    if (items.size < 2) return items
    // 基名 → 视频条目（同基名多扩展名时取先出现的，罕见情形不作特判）
    val videosByBase = HashMap<String, FileItem>()
    items.forEach { item ->
        if (isVideoFile(item)) videosByBase.putIfAbsent(baseNameOf(item.fn), item)
    }
    if (videosByBase.isEmpty()) return items

    val previewOf = HashMap<String, FileItem>() // 视频 fn → 预览图条目
    val hidden = HashSet<String>()              // 预览图自身的 fn（从列表移除）
    items.forEach { item ->
        if (item.isDir) return@forEach
        val base = videoPreviewBaseName(item.fn) ?: return@forEach
        val video = videosByBase[base] ?: return@forEach
        previewOf[video.fn] = item
        hidden += item.fn
    }
    if (previewOf.isEmpty()) return items

    return items.mapNotNull { item ->
        if (item.fn in hidden) {
            null
        } else {
            val p = previewOf[item.fn]
            val url = p?.thumb ?: p?.uo
            if (p == null || url.isNullOrBlank()) item else item.copy(thumb = url)
        }
    }
}
