package com.open115.pad.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.open115.pad.appContainer
import com.open115.pad.data.FileItem
import com.open115.pad.ui.theme.AppColors
import com.open115.pad.ui.theme.KindBadge
import com.open115.pad.util.Format
import androidx.compose.ui.text.font.FontWeight

val imageExts = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "avif", "tiff")

/** 高亮提醒闪几下：每下 300ms 亮 + 300ms 灭（共 3 下 ≈ 1.8s，之后底色消失） */
private const val HIGHLIGHT_FLASH_TIMES = 3
private const val HIGHLIGHT_FLASH_HALF_MS = 300

/**
 * "就是这一条"的闪烁进度：0 = 不显示，1 = 最亮。
 *
 * [key] 非空 = 这次跳转要指认的就是这一条（值是请求序号，拿来当动画 key：
 * 同一条被重复指认、或序号变了，都会重新闪一遍）。**只闪固定几下就落回 0**——
 * 用户要的是"提醒一下在哪"，不是常驻选中态，所以不靠状态常驻，闪完自己就淡掉了。
 * 状态那边（[com.open115.pad.ui.files.FilesViewModel]）过一会儿也会把高亮清掉，
 * 免得这条被滚出屏幕再滚回来时又闪一遍。
 */
@Composable
internal fun rememberHighlightFlash(key: Long?): Float {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(key) {
        flash.snapTo(0f)
        if (key == null) return@LaunchedEffect
        repeat(HIGHLIGHT_FLASH_TIMES) {
            flash.animateTo(1f, tween(HIGHLIGHT_FLASH_HALF_MS))
            flash.animateTo(0f, tween(HIGHLIGHT_FLASH_HALF_MS))
        }
    }
    return flash.value
}
/** 视频扩展名清单：唯一一份定义在 data 层（批量预览图任务也要用），这里只是别名 */
val videoExts = com.open115.pad.data.VIDEO_EXTS
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

/** 是不是视频（与 [iconForItem]/[FileItem.isv] 同一判据） */
fun isVideoItem(item: FileItem): Boolean =
    !item.isDir && (item.isv == 1 || item.ico?.lowercase() in videoExts)

fun thumbUrlFor(item: FileItem): String? = when {
    item.isDir -> item.fco
    item.thumb != null -> item.thumb
    isImageItem(item) -> item.uo
    else -> null
}

/**
 * 文件缩略卡：类型彩色底 + 缩略图（图/视频封面/文件夹封面）兜底。拖动幻影也用它。
 * [iconSize] 是无缩略图时兜底图标的直径；有缩略图时铺满容器、图标被盖住不可见。
 *
 * 视频的缩略图来源按优先级：**本机预览图缓存**（点播放自动生成/批量生成，零请求，见
 * com.open115.pad.data.VideoPreviewStore）→ 云端 `<视频名>预览图.jpg` 借来的 thumb
 * （[applyVideoPreviews] 已把它塞进 item.thumb）→ 类型角标兜底。
 */
@Composable
internal fun Thumb(item: FileItem, modifier: Modifier = Modifier, iconSize: Int = 40) {
    val context = LocalContext.current
    val store = remember(context) { context.appContainer.videoPreviews }
    // 生成侧（播放器/批量任务）写完一张就 +1：这样从播放器回到列表时这一行会重新取缩略图
    val version by store.version.collectAsState()
    val model = remember(item.pc, item.fn, item.thumb, item.uo, item.fco, item.isDir, version) {
        localPreviewModel(context, item, store) ?: thumbUrlFor(item)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        // 彩色类型视觉做底层常驻：缩略图加载失败/无缩略图时兜底，避免出现空白图标
        KindBadge(item.fn, item.isDir, Modifier.size(iconSize.dp))
        if (model != null) {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                loading = {},
                error = {},
            )
        }
    }
}

/**
 * 视频条目命中了本机预览图 → 返回加载请求，否则 null。
 *
 * ★ 显式给 `memoryCacheKey` 掺进文件 mtime：预览图重新生成时文件名（pick_code 哈希）不变，
 * 只按路径缓存会一直显示旧画面（壁纸那条线踩过同一个坑）。
 */
private fun localPreviewModel(
    context: Context,
    item: FileItem,
    store: com.open115.pad.data.VideoPreviewStore,
): Any? {
    if (!isVideoItem(item)) return null
    val pc = item.pc ?: return null
    if (!store.has(pc)) return null
    val file = store.fileFor(pc)
    return ImageRequest.Builder(context)
        .data(file)
        .memoryCacheKey("vpreview|$pc|${file.lastModified()}")
        .build()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListRow(
    item: FileItem,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    /** null = 不做长按检测（多选态长按让位给拖动，且它的事件消费会杀掉外层拖拽手势） */
    onLongClick: (() -> Unit)?,
    /** 已置顶的文件夹：淡蓝圆角卡（内收留缝，与玻璃卡同款）+ 图钉标记，让置顶区一眼可分 */
    pinned: Boolean = false,
    /** 最近一次浏览（播放/看图/读文本）的时间戳；0 = 没看过，不显示时间行 */
    lastViewed: Long = 0,
    /**
     * 非空 = 这次跳转要指认的就是这一条（值是请求序号）：整行**闪几下淡蓝底**
     * （对齐 Windows 资源管理器里被选中的那一行，但只是一次性提醒，不常驻）。
     */
    highlightSeq: Long? = null,
    /**
     * 视频行下面是否显示**宽幅预览条**（按片长抓的 2~10 帧，可横向滑）。
     * 关闭只是不显示；已经抓过的帧仍在缓存里（设置 → 存储与缓存 里可清）。
     */
    showVideoStrip: Boolean = true,
) {
    // 壁纸激活时行变"玻璃卡"：半透明白底 + 圆角 + 细描边（不透明度/缝隙/圆角在设置里调），
    // 壁纸从行间缝隙透出；**无壁纸时每一行都是实底卡**（深色底/浅色纯底都走这条），
    // 置顶行用更浓的强调底区分
    val glass = com.open115.pad.ui.theme.LocalWallpaperGlass.current
    val glassRadius = glass.radiusDp.dp
    val glassGapPad = (glass.gapDp / 2).dp
    val flash = rememberHighlightFlash(highlightSeq)
    val flashOn = flash > 0f
    // 无壁纸时所有行（含文件夹、含没预览条的）统一套卡：一屏里一半有框一半没有，
    // 比"全都没有框"更刺眼。卡片两侧内收 12dp，行内容的内边距相应从 16 收到 4 ——
    // 合起来仍是 16dp，所以图标、标题的左边缘位置不变
    val solidCard = !glass.active
    val cardRadius = if (solidCard) 14.dp else glassRadius
    val cardShape = RoundedCornerShape(cardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = if (solidCard) 4.dp else glassGapPad)
            .then(
                when {
                    // 高亮闪示：从卡片底色渐变过去（不叠半透明层，否则会透出页面底），
                    // 描边一起渐变到蓝；卡片形状始终保持，免得好端端一行突然变成通栏
                    flashOn -> Modifier
                        .clip(cardShape)
                        .background(
                            lerp(
                                AppColors.Card.copy(alpha = if (solidCard) 1f else glass.cardAlpha),
                                AppColors.BlueBg,
                                flash,
                            ),
                        )
                        .border(
                            1.dp,
                            lerp(AppColors.CardBorder, AppColors.BlueFg.copy(alpha = 0.55f), flash),
                            cardShape,
                        )
                    glass.active && pinned -> Modifier
                        .clip(cardShape)
                        .background(AppColors.AccentSoft.copy(alpha = (glass.cardAlpha + 0.12f).coerceAtMost(1f)))
                        .border(1.dp, AppColors.CardBorder, cardShape)
                    glass.active -> Modifier
                        .clip(cardShape)
                        .background(AppColors.Card.copy(alpha = glass.cardAlpha))
                        .border(1.dp, AppColors.CardBorder, cardShape)
                    pinned -> Modifier
                        // 置顶：同款卡片、强调底，描边与普通行同色 —— 同屏出现两种深浅的框
                        // 会像是两种控件，只让底色说话
                        .clip(cardShape)
                        .background(AppColors.AccentSoft)
                        .border(1.dp, AppColors.Divider, cardShape)
                    else -> Modifier
                        // 描边用 Divider 而不是 CardBorder：浅色纯底上 CardBorder 只有 5% 黑，
                        // 画出来几乎看不见，那种底上的行边界全靠这条线
                        .clip(cardShape)
                        .background(AppColors.Card)
                        .border(1.dp, AppColors.Divider, cardShape)
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = if (solidCard) 4.dp else 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
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
                // 看过/播过/读过的条目：补一行最近浏览时间（数据来自操作记录的播放/看图/读文本三类）
                // ago 吃毫秒：刚刚 / N 分钟前 / N 天前，超一周落回日期
                if (lastViewed > 0) {
                    Text(
                        "最近浏览 " + Format.ago(lastViewed),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.AccentDeep,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

        // 视频行下面那条宽幅预览条（按片长抓的 2~10 帧，可横向滑；没生成过/开关关着就不显示）
        if (showVideoStrip) VideoStrip(item)
    }
}

/**
 * 视频行下面的**宽幅预览条**：按片长抓的 2~10 帧横排，可横向滑动。
 *
 * 帧很小（长边 320、JPEG 70，实测 10~15KB/张）—— 它的用处是"扫一眼这片子大概是什么内容"，
 * 不追求清晰；每帧宽度固定、`Crop` 成统一的长方形，所以不同比例的片子排出来也齐。
 *
 * 数据来自 [com.open115.pad.data.VideoPreviewStore] 的预览条目录，**纯本地读、零请求**；
 * 没抓过的视频（或抓不到帧的，如云端未转码）这一行不占位置。
 */
@Composable
internal fun VideoStrip(
    item: FileItem,
    tileWidth: Dp = 104.dp,
    tileHeight: Dp = 58.dp,
    topPadding: Dp = 8.dp,
) {
    item.pc?.let { pc -> VideoStrip(pc, tileWidth, tileHeight, topPadding) }
}

/** 同上，但直接按 pick_code 取（预览图结果面板里手上只有 FileItem，走上面那个重载） */
@Composable
internal fun VideoStrip(
    pc: String,
    tileWidth: Dp = 104.dp,
    tileHeight: Dp = 58.dp,
    topPadding: Dp = 8.dp,
) {
    val context = LocalContext.current
    val store = remember(context) { context.appContainer.videoPreviews }
    // 生成侧写完会 +1：从播放器/批量任务回来后这一行要立刻长出预览条
    val version by store.version.collectAsState()
    val count = remember(pc, version) { store.stripCount(pc) }
    if (count <= 0) return
    // 预览条自己垫一层"底托"：帧的明暗没法预料，暗场片子（比如 VR/夜戏）落在暗底上时
    // 光看画面根本找不到这条预览占了多大一块 —— 底托把它的范围固定下来
    Box(
        Modifier
            .padding(top = topPadding)
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.TextPrimary.copy(alpha = 0.05f))
            .padding(6.dp),
    ) {
        // 有意**不加 fillMaxWidth**：底托要贴着帧数收紧（2 帧就一小块），
        // 只有帧多到一行装不下时才滑得动
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 0 until count) {
                val file = remember(pc, i) { store.stripFileFor(pc, i) }
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(file)
                        // 文件名固定，掺 mtime 才不会在重新抓过之后继续显示旧帧（同 Thumb 的坑）
                        .memoryCacheKey("vstrip|$pc|$i|${file.lastModified()}")
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = tileWidth, height = tileHeight)
                        .clip(RoundedCornerShape(6.dp)),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCard(
    item: FileItem,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    /** null = 不做长按检测（同 FileListRow） */
    onLongClick: (() -> Unit)?,
    /** 大图标模式：兜底图标放大一档，文字随格子自然变大 */
    large: Boolean = false,
    /** 已置顶的文件夹：卡片淡蓝底 + 左上角图钉角标 */
    pinned: Boolean = false,
    /** 非空 = 这次跳转要指认的就是这一张（值是请求序号）：卡片**闪几下淡蓝底 + 蓝描边** */
    highlightSeq: Long? = null,
) {
    val flash = rememberHighlightFlash(highlightSeq)
    // 卡片不透明：从"平时的底色"往淡蓝渐变（不是叠一层半透明，不然闪的时候能透出页面底）
    val base = when {
        selected -> AppColors.AccentSoft
        pinned -> AppColors.BlueBg
        else -> AppColors.Card
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        // 置顶比选中态用得再深一档（卡片浮在灰底上，略强的色块才读得出"成组"）；
        // 选中优先，避免"又选中又置顶"时两种状态互相盖住
        color = if (flash > 0f) lerp(base, AppColors.BlueBg, flash) else base,
        border = when {
            flash > 0f -> androidx.compose.foundation.BorderStroke(
                1.5.dp,
                lerp(AppColors.CardBorder, AppColors.BlueFg.copy(alpha = 0.6f), flash),
            )
            selected -> null
            else -> androidx.compose.foundation.BorderStroke(1.dp, AppColors.CardBorder)
        },
        tonalElevation = 0.dp,
        // 闪的时候顺带把卡片抬高一档（阴影跟着亮暗一起起伏，更容易注意到）
        shadowElevation = if (selected) 0.dp else (2f + 4f * flash).dp,
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
                Thumb(item, Modifier.fillMaxSize(), iconSize = if (large) 64 else 40)
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
                // 标题恒占两行：一行名的卡不再比两行名的矮，网格卡高度才能对齐
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
            // 尺寸行恒占位：文件夹没有尺寸，但占住同一行高，卡片高度才一致
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 18.dp),
            ) {
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
}
