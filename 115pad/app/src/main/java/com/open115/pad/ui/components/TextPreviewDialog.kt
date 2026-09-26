package com.open115.pad.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open115.pad.data.ImageUrlResolver
import com.open115.pad.data.TEXT_ENCODINGS
import com.open115.pad.data.TEXT_LINES_PER_PAGE
import com.open115.pad.data.TextPreview
import com.open115.pad.data.TextPreviewResult
import com.open115.pad.data.fetchTextPreview
import com.open115.pad.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * 文本预览：解析直链 → 取前若干字节 → 按行分页展示。
 *
 * 和图片画廊一样设计成普通全屏 Composable、由调用方在应用根层级渲染——
 * Dialog 在平板布局下会被侧栏宽度内缩，盖不住左侧导航。
 */
@Composable
fun TextPreviewHost(
    name: String,
    pickCode: String,
    sizeText: String,
    resolver: ImageUrlResolver,
    client: OkHttpClient,
    onDownload: (url: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var loading by remember(pickCode) { mutableStateOf(true) }
    var error by remember(pickCode) { mutableStateOf<String?>(null) }
    var preview by remember(pickCode) { mutableStateOf<TextPreview?>(null) }
    var url by remember(pickCode) { mutableStateOf<String?>(null) }

    LaunchedEffect(pickCode) {
        loading = true
        error = null
        preview = null
        try {
            // 直链解析与"下载到本机"共用同一条严格链路：失败原因直接给用户看
            val resolved = withContext(Dispatchers.IO) { resolver.downloadUrl(pickCode) }
            url = resolved
            when (val r = fetchTextPreview(client, resolved)) {
                is TextPreviewResult.Ok -> preview = r.preview
                is TextPreviewResult.Fail -> error = r.message
            }
        } catch (e: Exception) {
            error = "读取失败：${e.message ?: e::class.java.simpleName}"
        } finally {
            loading = false
        }
    }

    TextPreviewOverlay(
        name = name,
        sizeText = sizeText,
        loading = loading,
        error = error,
        preview = preview,
        onDownload = { url?.let { onDownload(it, name) } },
        onDismiss = onDismiss,
    )
}

@Composable
private fun TextPreviewOverlay(
    name: String,
    sizeText: String,
    loading: Boolean,
    error: String?,
    preview: TextPreview?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember(preview) { mutableIntStateOf(0) }
    var encodingMenu by remember { mutableStateOf(false) }
    // 编码切换时重解，不走网络，直接替换本地内容
    var current by remember(preview) { mutableStateOf(preview) }
    val total = current?.pageCount ?: 1
    val pageNo = page.coerceIn(0, total - 1)

    // 返回键 = 关闭预览，回到文件列表而不是退出程序（和图片画廊一致）
    BackHandler { onDismiss() }

    // 全屏覆盖层：实底（scheme 的 surface 在壁纸激活时是半透明的，会透出底下的文件页）
    Surface(Modifier.fillMaxSize(), color = AppColors.Card) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：文件名 + 元信息 + 下载/关闭
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(AppColors.GraySoft)
                    .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = buildMeta(sizeText, current, loading)
                    if (meta.isNotEmpty()) {
                        Text(
                            meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextTertiary,
                            maxLines = 1,
                        )
                    }
                }
                // 编码切换：失效的可选项置灰，点选后直接换码重解（不走网络）
                Box {
                    Row(
                        Modifier
                            .clickable { if (current != null) encodingMenu = true }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            current?.encoding ?: "编码",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary,
                        )
                        Icon(
                            Icons.Outlined.ArrowDropDown,
                            contentDescription = null,
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = encodingMenu, onDismissRequest = { encodingMenu = false }) {
                        TEXT_ENCODINGS.forEach { enc ->
                            val chosen = current?.encoding == enc
                            val usable = chosen || (current != null && current!!.reDecode(enc) != null)
                            DropdownMenuItem(
                                text = { Text(enc) },
                                onClick = {
                                    encodingMenu = false
                                    if (!chosen && current != null) {
                                        current!!.reDecode(enc)?.let { page = 0; current = it }
                                    }
                                },
                                enabled = usable,
                            )
                        }
                    }
                }
                IconButton(onClick = onDownload, enabled = !loading && error == null) {
                    Icon(Icons.Outlined.Download, contentDescription = "下载到本机", tint = AppColors.TextSecondary)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = AppColors.TextSecondary)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    error != null -> Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }

                    current == null || current!!.lines.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("（空文件）", color = AppColors.TextTertiary)
                    }

                    else -> Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = current!!.page(pageNo).joinToString("\n"),
                            // 等宽：代码缩进、目录树的对齐都靠它
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = AppColors.TextPrimary,
                        )
                    }
                }
            }

            // 底栏：翻页 + 位置提示（单页时只留行数/编码信息）
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(AppColors.GraySoft)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { page = (pageNo - 1).coerceAtLeast(0) }, enabled = pageNo > 0) {
                    Text("上一页")
                }
                Text(
                    if (total > 1) "第 ${pageNo + 1} / $total 页 · 每页 $TEXT_LINES_PER_PAGE 行"
                    else "共 ${current?.lines?.size ?: 0} 行",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { page = (pageNo + 1).coerceAtMost(total - 1) },
                    enabled = pageNo < total - 1,
                ) { Text("下一页") }
            }
        }
    }
}

/** 顶栏副标题：大小 / 编码 / 是否截断 */
private fun buildMeta(sizeText: String, preview: TextPreview?, loading: Boolean): String {
    if (loading || preview == null) return sizeText
    return buildList {
        if (sizeText.isNotEmpty()) add(sizeText)
        add(preview.encoding)
        add("${preview.lines.size} 行")
        if (preview.truncated) add("仅预览前 ${preview.bytes / 1024 / 1024}MB")
    }.joinToString(" · ")
}
