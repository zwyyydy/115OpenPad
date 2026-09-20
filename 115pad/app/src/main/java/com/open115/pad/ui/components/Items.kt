package com.open115.pad.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.open115.pad.data.FileItem
import com.open115.pad.ui.theme.AppColors
import com.open115.pad.ui.theme.KindBadge
import com.open115.pad.util.Format
import androidx.compose.ui.text.font.FontWeight

val imageExts = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "avif", "tiff")
val videoExts = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "ts", "webm", "m4v", "mpg", "mpeg", "rmvb", "m2ts")
val audioExts = setOf("mp3", "flac", "aac", "ogg", "wav", "m4a", "ape", "wma")

/**
 * 本地文件能不能交给应用内播放器播（视频/音频）。
 *
 * MIME 与扩展名**取或**，不能只看一边：DownloadManager 上报的 media_type 常是
 * application/octet-stream 这类泛型，而部分机型的 MimeTypeMap 又不认 mkv/m2ts
 * （返回 null），任何单边判据都会漏掉一批能播的片子。
 */
fun isLocalPlayable(name: String, mime: String?): Boolean {
    val m = mime?.lowercase()
    if (m != null && (m.startsWith("video/") || m.startsWith("audio/"))) return true
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in videoExts || ext in audioExts
}

fun iconForItem(item: FileItem): ImageVector {
    if (item.isDir) return Icons.Outlined.Folder
    val ext = item.ico?.lowercase()
    return when {
        item.isv == 1 || ext in videoExts -> Icons.Outlined.OndemandVideo
        ext in imageExts -> Icons.Outlined.Image
        ext in audioExts -> Icons.Outlined.MusicNote
        ext in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "txt", "epub", "mobi") -> Icons.Outlined.Description
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> Icons.Outlined.FolderZip
        ext == "apk" -> Icons.Outlined.Android
        else -> Icons.Outlined.InsertDriveFile
    }
}

fun isImageItem(item: FileItem): Boolean =
    !item.isDir && (item.ico?.lowercase() in imageExts)

fun thumbUrlFor(item: FileItem): String? = when {
    item.isDir -> item.fco
    item.thumb != null -> item.thumb
    isImageItem(item) -> item.uo
    else -> null
}

@Composable
private fun Thumb(item: FileItem, modifier: Modifier = Modifier, iconSize: Int = 24) {
    val url = thumbUrlFor(item)
    Box(modifier, contentAlignment = Alignment.Center) {
        // 彩色类型视觉做底层常驻：缩略图加载失败/无缩略图时兜底，避免出现空白图标
        KindBadge(item.fn, item.isDir, Modifier.size(40.dp))
        if (url != null) {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                loading = {},
                error = {},
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListRow(
    item: FileItem,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** 已置顶的文件夹：整行铺一层淡蓝底 + 图钉标记，让置顶区一眼可分 */
    pinned: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (pinned) Modifier.background(AppColors.AccentSoft) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(
            item,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                item.fn,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = com.open115.pad.ui.theme.AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildString {
                if (!item.isDir) append(Format.size(item.fs))
                if (item.play_long > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(Format.duration(item.play_long))
                }
                if (item.upt > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(Format.dateTime(item.upt))
                }
            }
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = com.open115.pad.ui.theme.AppColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (pinned) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = "已置顶",
                tint = AppColors.AccentDeep,
                modifier = Modifier.size(16.dp),
            )
        }
        if (item.ism == 1 && !selectMode) {
            Icon(
                Icons.Outlined.Star,
                contentDescription = "星标",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (selectMode) Checkbox(checked = selected, onCheckedChange = { onClick() })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCard(
    item: FileItem,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** 已置顶的文件夹：卡片淡蓝底 + 左上角图钉角标 */
    pinned: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        // 置顶比选中态用得再深一档（卡片浮在灰底上，略强的色块才读得出"成组"）；
        // 选中优先，避免"又选中又置顶"时两种状态互相盖住
        color = when {
            selected -> AppColors.AccentSoft
            pinned -> AppColors.BlueBg
            else -> AppColors.Card
        },
        border = if (selected) null else androidx.compose.foundation.BorderStroke(
            1.dp,
            AppColors.CardBorder,
        ),
        tonalElevation = 0.dp,
        shadowElevation = if (selected) 0.dp else 2.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Thumb(item, Modifier.fillMaxSize(), iconSize = 40)
                if (pinned) {
                    // 图钉角标放左上角，与右上角的勾选框分区，互不打架
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(2.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(AppColors.Card),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "已置顶",
                            tint = AppColors.AccentDeep,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                if (selectMode) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Checkbox(checked = selected, onCheckedChange = { onClick() })
                    }
                }
            }
            Text(
                item.fn,
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
            if (!item.isDir) {
                Text(
                    Format.size(item.fs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
