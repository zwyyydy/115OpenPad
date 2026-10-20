package com.open115.pad.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.open115.pad.data.magnet.WhatsLinkClient
import okhttp3.OkHttpClient

/** 取数的四种落点：还在取 / 拿到了 / 服务解析不了 / 请求失败 */
private data class ShotState(
    val loading: Boolean = false,
    val shots: List<String> = emptyList(),
    val unresolved: Boolean = false,
    val error: String? = null,
)

/**
 * 种子/磁力的**第三方画面预览**：给一条磁力链，出一排抽样截图，点一张进全屏放大。
 *
 * 用在哪都是"顺手看一眼"的位置（云盘种子的下载弹窗、添加任务里选完种子），所以：
 * - [enabled] 关掉或磁力链为空时**一个字节都不发**，也不占地方；
 * - 拿不到就降级成一行说明，绝不抛异常、绝不挡住调用方的按钮（"下载"/"云下载"照常能点）；
 * - 开关与地址来自实验室的「磁力预览」，与磁力搜索的源/Token 相互独立。
 *
 * @param magnet 已经拼好的磁力链（种子的 info_hash + dn + tracker）
 */
@Composable
fun MagnetShotPreview(
    magnet: String?,
    enabled: Boolean,
    endpoint: String,
    http: OkHttpClient,
    modifier: Modifier = Modifier,
) {
    val usable = enabled && !magnet.isNullOrBlank()
    // 用 remember(keys) 跟着 key 重置：换一条种子时直接进入 loading，
    // 不会先闪一帧"这个种子没有可预览的画面"
    var state by remember(magnet, enabled, endpoint) {
        mutableStateOf(if (usable) ShotState(loading = true) else ShotState())
    }
    LaunchedEffect(magnet, enabled, endpoint) {
        if (!usable) {
            state = ShotState()
            return@LaunchedEffect
        }
        state = ShotState(loading = true)
        state = runCatching {
            WhatsLinkClient(endpoint.ifBlank { WhatsLinkClient.DEFAULT_BASE }, http)
                .preview(magnet!!)
        }.fold(
            onSuccess = { ShotState(shots = it.screenshots, unresolved = !it.resolved) },
            onFailure = { ShotState(error = it.message ?: "预览失败") },
        )
    }

    if (usable) {
        when {
            state.loading -> ShotLine("正在取预览图…", modifier)
            state.shots.isNotEmpty() -> ShotStrip(state.shots, modifier)
            state.error != null -> ShotLine("预览暂不可用：${state.error}", modifier)
            state.unresolved -> ShotLine("预览服务解析不了这条种子。", modifier)
            else -> ShotLine("这个种子没有可预览的画面。", modifier)
        }
    }
}

@Composable
private fun ShotLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun ShotStrip(urls: List<String>, modifier: Modifier = Modifier) {
    var zoom by remember { mutableStateOf<String?>(null) }
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        urls.forEach { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 132.dp, height = 78.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { zoom = url },
            )
        }
    }
    zoom?.let { url ->
        // 全屏看大图：Dialog 默认有宽度上限，要铺满得关掉（usePlatformDefaultWidth=false）
        FadeDialog(
            onDismissRequest = { zoom = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .pointerInput(Unit) { detectTapGestures { zoom = null } },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
    }
}
