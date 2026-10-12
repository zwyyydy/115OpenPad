package com.open115.pad.ui.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.data.mcp.MagnetSearchClient
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler

/**
 * 磁力搜索对话框：关键词 → MCP `magnet_search` → 结果列表 → 看文件结构（`magnet_files`）→
 * 推进 115 离线下载（走 [OfflineViewModel.addUrls]，与「添加任务」同一套保存位置/错误提示）。
 *
 * 配置（端点+Token）在设置 → 实验室；Token 没配时这里根本不会被打开（入口就绪判断在宿主）。
 * 工具返回的 JSON 形状站方没文档，解析失败时展示工具原文兜底。
 */
@Composable
fun MagnetSearchOverlay(
    vm: OfflineViewModel,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val endpoint by vm.magnetEndpoint.collectAsState()
    val token by vm.magnetToken.collectAsState()

    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<MagnetSearchClient.MagnetItem>?>(null) }
    // 非 null = 正在看这条磁力的文件结构（第二态，从结果列表点进来的）
    var detailItem by remember { mutableStateOf<MagnetSearchClient.MagnetItem?>(null) }
    var detailInfo by remember { mutableStateOf<MagnetSearchClient.FilesInfo?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || loading) return
        if (token.isBlank()) {
            error = "还没配 MCP Token（设置 → 实验室 → 磁力搜索）"
            return
        }
        loading = true
        error = null
        detailItem = null
        scope.launch {
            try {
                results = MagnetSearchClient(
                    endpoint = endpoint.ifBlank { MagnetSearchClient.DEFAULT_ENDPOINT },
                    token = token,
                    http = vm.okHttpClient ?: fallbackHttp,
                ).search(q, limit = 20)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                results = null
            } finally {
                loading = false
            }
        }
    }

    fun loadDetail(item: MagnetSearchClient.MagnetItem) {
        detailItem = item
        detailInfo = null
        detailLoading = true
        scope.launch {
            try {
                detailInfo = MagnetSearchClient(
                    endpoint = endpoint.ifBlank { MagnetSearchClient.DEFAULT_ENDPOINT },
                    token = token,
                    http = vm.okHttpClient ?: fallbackHttp,
                ).files(item.magnet)
            } catch (e: Exception) {
                // 文件结构解析失败不挡提交：留空提示，磁力链照样能推
                detailInfo = MagnetSearchClient.FilesInfo(emptyList(), e.message ?: "解析失败")
            } finally {
                detailLoading = false
            }
        }
    }

    fun submit(item: MagnetSearchClient.MagnetItem) {
        if (submitting) return
        submitting = true
        scope.launch {
            val err = vm.addUrls(item.magnet, vm.saveLocation.value.cid)
            submitting = false
            if (err == null) onDismiss() else note = err
        }
    }

    // ★ 页面内全屏浮层，不是 Dialog 窗口——这台设备的 Dialog 新窗口首帧绘制不落地
    //   （窗口建了、画面全透明，要等下一次界面变化才显形：用户实测「点放大镜没反应、
    //   再点添加任务才出现」），剧照浏览/画廊踩过同一个坑后改的浮层，这里照方抓药。
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("磁力搜索", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 520.dp)) {
                val d = detailItem
                if (d == null) {
                    // ---- 搜索态 ----
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("关键词") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { search() }, enabled = query.isNotBlank() && !loading) {
                            Text("搜索")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    when {
                        loading -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator(Modifier.width(28.dp)) }

                        error != null -> Text(
                            error ?: "", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        results != null && results!!.isEmpty() -> Text(
                            "没有搜到。换个更短、更核心的词试试。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        results != null -> Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                            results!!.forEachIndexed { index, item ->
                                Column(
                                    Modifier.fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                        .let { m -> m },
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                item.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            val meta = listOfNotNull(
                                                item.size?.takeIf { it.isNotBlank() },
                                                item.date?.takeIf { it.isNotBlank() },
                                            ).joinToString(" · ")
                                            if (meta.isNotBlank()) {
                                                Text(
                                                    meta,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(
                                            onClick = { loadDetail(item) },
                                            enabled = !detailLoading,
                                        ) { Text("详情") }
                                    }
                                    if (index != results!!.lastIndex) HorizontalDivider()
                                }
                            }
                        }

                        else -> Text(
                            "输入关键词搜磁力资源（搜索范围是该源的聚合索引库）。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    // ---- 详情态：文件结构 + 提交 ----
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { detailItem = null }) { Text("‹ 返回") }
                        Text(
                            d.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    when {
                        detailLoading -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator(Modifier.width(28.dp)) }

                        else -> {
                            val info = detailInfo
                            if (info == null || info.files.isEmpty()) {
                                Text(
                                    "没能解析出文件结构。" + (info?.raw?.take(200)?.ifBlank { null } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    "${info.files.size} 个文件",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(6.dp))
                                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                                    info.files.forEach { f ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                            Text(
                                                f.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            f.size?.let {
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        d.magnet,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    note?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val d = detailItem
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (d != null) {
                    Button(onClick = { submit(d) }, enabled = !submitting && !detailLoading) {
                        Text(if (submitting) "提交中…" else "添加到云下载")
                    }
                }
            }
        }
    }
}


}

/** 兜底 HTTP 客户端（正常装配走 vm.okHttpClient） */
private val fallbackHttp: okhttp3.OkHttpClient by lazy { okhttp3.OkHttpClient() }
