package com.open115.pad.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.data.OpenApi
import com.open115.pad.data.Uploader
import com.open115.pad.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.net.URLEncoder

data class XunleiSub(
    val name: String,
    val languages: List<String>,
    val durationMs: Long,
    val url: String,
    val ext: String,
)

private val xunleiJson = Json { ignoreUnknownKeys = true; isLenient = true }

private const val XUNLEI_UA =
    "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) thunder/11.1.12.1692 Chrome/83.0.4103.122 XWEB/2311"

fun searchXunleiSubtitles(client: OkHttpClient, keyword: String): List<XunleiSub> {
    val url = "http://api-shoulei-ssl.xunlei.com/oracle/subtitle?name=" +
        URLEncoder.encode(keyword, "UTF-8")
    val request = okhttp3.Request.Builder()
        .url(url)
        .header("User-Agent", XUNLEI_UA)
        .build()
    val body = client.newCall(request).execute().use { resp ->
        resp.body?.string() ?: error("迅雷接口无响应")
    }
    val root = xunleiJson.parseToJsonElement(body) as? JsonObject ?: error("迅雷接口响应异常")
    val code = (root["code"] as? JsonPrimitive)?.content
    if (code != "0") error("迅雷接口返回码 $code")
    val arr = root["data"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val langs = (o["languages"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
            ?: emptyList()
        val url = s("url") ?: return@mapNotNull null
        XunleiSub(
            name = s("name") ?: "未命名",
            languages = langs,
            durationMs = s("duration")?.toLongOrNull() ?: 0L,
            url = url,
            ext = (s("ext") ?: "srt").removePrefix("."),
        )
    }
}

fun downloadSubtitleBytes(client: OkHttpClient, url: String): ByteArray =
    client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", XUNLEI_UA).build())
        .execute().use { resp ->
            if (!resp.isSuccessful) error("字幕下载失败 HTTP ${resp.code}")
            resp.body?.bytes() ?: error("字幕内容为空")
        }

fun mimeForSubtitleExt(ext: String): String = when (ext.lowercase()) {
    "srt" -> "application/x-subrip"
    "vtt" -> "text/vtt"
    "ssa", "ass" -> "text/x-ssa"
    else -> "application/x-subrip"
}

/** 把字幕时间轴整体平移 offsetMs（支持 srt/vtt 的 --> 行和 ass 的 Dialogue 行），负值提前 */
fun shiftSubtitleTimestamps(bytes: ByteArray, offsetMs: Long): ByteArray {
    val text = runCatching { String(bytes, Charsets.UTF_8) }
        .getOrElse { String(bytes, Charsets.ISO_8859_1) }
    val tsRegex = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})([.,])(\\d{2,3})")

    fun shiftMatch(m: MatchResult): String {
        val (h, mi, s) = listOf(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong())
        val sep = m.groupValues[4][0]
        val fracStr = m.groupValues[5]
        val fracDigits = fracStr.length
        var total = h * 3600000 + mi * 60000 + s * 1000 +
            fracStr.padEnd(3, '0').take(3).toLong()
        total = (total + offsetMs).coerceAtLeast(0)
        val frac = if (fracDigits >= 3) (total % 1000).toString().padStart(3, '0')
        else (total % 1000 / 10).toString().padStart(2, '0')
        return "%02d:%02d:%02d%s%s".format(
            java.util.Locale.US,
            total / 3600000, total % 3600000 / 60000, total % 60000 / 1000, sep, frac,
        )
    }

    val out = text.lineSequence().joinToString("\n") { line ->
        when {
            // srt/vtt 时间轴行
            line.contains("-->") ->
                tsRegex.replace(line) { m -> shiftMatch(m) }
            // ass/ssa 对白行（两段时间轴逗号分隔）
            line.trimStart().startsWith("Dialogue:") ->
                tsRegex.replace(line) { m -> shiftMatch(m) }
            else -> line
        }
    }
    return out.toByteArray()
}

/** 语言标签用于云盘文件命名：优先取接口的 languages，拼接并清理非法字符 */
fun langTagFor(languages: List<String>): String {
    val tag = languages.filter { it.isNotBlank() }
        .joinToString("-") { it.trim() }
        .replace(Regex("[\\\\/:*?\"<>|\\s]"), "")
    return tag.ifBlank { "sub" }.take(40)
}

/**
 * 在线字幕搜索对话框：迅雷接口搜索，用户自行选择。
 * 点击条目 = 加载进播放器；云朵按钮 = 上传到视频同级目录（命名 视频名.语言.后缀）。
 */
@Composable
internal fun SubtitleSearchDialog(
    client: OkHttpClient,
    api: OpenApi,
    initialKeyword: String,
    videoDurationMs: Long = 0L,
    uploadTargetCid: String?,
    uploadBaseName: String,
    onLoad: (SubtitleCfg, ByteArray, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf(initialKeyword) }
    var results by remember { mutableStateOf<List<XunleiSub>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyIndex by remember { mutableStateOf(-1) }
    var status by remember { mutableStateOf<String?>(null) }

    fun doSearch() {
        scope.launch {
            searching = true
            error = null
            try {
                // 按与当前视频时长的接近程度排序：差别越小越靠前（未知时长排最后）
                results = withContext(Dispatchers.IO) {
                    searchXunleiSubtitles(client, keyword.trim())
                        .sortedWith(
                            compareBy(
                                { it.durationMs == 0L },
                                { kotlin.math.abs(it.durationMs - videoDurationMs) },
                            ),
                        )
                }
                if (results?.isEmpty() == true) error = "没有找到相关字幕"
            } catch (e: Exception) {
                error = "搜索失败：${e.message}"
            } finally {
                searching = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("在线字幕") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        placeholder = { Text("视频名或关键词") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { if (!searching && keyword.isNotBlank()) doSearch() }) {
                        Text(if (searching) "搜索中" else "搜索")
                    }
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Box(Modifier.height(360.dp).padding(top = 8.dp)) {
                    val list = results
                    when {
                        list == null -> Box(
                            Modifier.fillMaxWidth().height(360.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "输入关键词搜索迅雷字幕库\n点击条目直接加载，云朵按钮传到云盘",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        searching -> Box(
                            Modifier.fillMaxWidth().height(360.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(list) { sub ->
                                val idx = list.indexOf(sub)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = busyIndex < 0) {
                                            busyIndex = idx
                                            scope.launch {
                                                try {
                                                    val bytes = withContext(Dispatchers.IO) {
                                                        downloadSubtitleBytes(client, sub.url)
                                                    }
                                                    val tag = langTagFor(sub.languages)
                                                    onLoad(
                                                        SubtitleCfg(
                                                            url = "",
                                                            mime = mimeForSubtitleExt(sub.ext),
                                                            language = tag,
                                                            title = sub.name,
                                                        ),
                                                        bytes,
                                                        sub.ext,
                                                    )
                                                } catch (e: Exception) {
                                                    error = "加载失败：${e.message}"
                                                } finally {
                                                    busyIndex = -1
                                                }
                                            }
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            sub.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            buildString {
                                                append(sub.languages.joinToString("/").ifBlank { "未知语言" })
                                                if (sub.durationMs > 0) {
                                                    append(" · ")
                                                    append(Format.duration(sub.durationMs / 1000))
                                                }
                                                append(" · .")
                                                append(sub.ext)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (uploadTargetCid != null && uploadBaseName.isNotBlank()) {
                                        IconButton(
                                            enabled = busyIndex < 0,
                                            onClick = {
                                                busyIndex = idx
                                                scope.launch {
                                                    try {
                                                        status = "正在下载字幕…"
                                                        val bytes = withContext(Dispatchers.IO) {
                                                            downloadSubtitleBytes(client, sub.url)
                                                        }
                                                        status = "正在上传到云盘…"
                                                        val fileName = "${uploadBaseName}.${langTagFor(sub.languages)}.${sub.ext}"
                                                        val res = withContext(Dispatchers.IO) {
                                                            Uploader.uploadSmall(
                                                                api, fileName, bytes,
                                                                target = "U_1_$uploadTargetCid",
                                                            )
                                                        }
                                                        status = "已上传到云盘：${res.fileName}"
                                                    } catch (e: Exception) {
                                                        status = null
                                                        error = "上传失败：${e.message}"
                                                    } finally {
                                                        busyIndex = -1
                                                    }
                                                }
                                            },
                                        ) {
                                            if (busyIndex == idx) {
                                                CircularProgressIndicator(Modifier.height(18.dp))
                                            } else {
                                                Icon(
                                                    Icons.Outlined.CloudUpload,
                                                    contentDescription = "上传到云盘",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
