package com.open115.pad.data.media.scrape

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.open115.pad.data.media.MediaPrefs
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/**
 * 元数据源注册表：内置 TMDB + HTTP 服务源 + 用户 JS 源（App 内建的 + 扫描目录里的 `.js`）。
 *
 * 快照式：`refresh()` 读设置重建"可用源列表 + 当前选中源"两个 volatile，供非挂起的
 * `sources()` / `currentSource()` 读（刮削器与界面都在协程里，但 source 提供者是普通 lambda）。
 * 每个 JS 源一个 [JsRuntime]（QuickJS），按 `id#codeHash` 缓存——改代码才重建，反复刷新不重复起引擎。
 * HTTP 服务源是常驻对象（基址/token 走 lambda 每次请求现读），配了基址就进列表。
 */
class ScrapeSourceRegistry(
    private val prefs: MediaPrefs,
    private val tmdb: TmdbMetadataSource,
    private val metatube: MetatubeSource,
    private val http: OkHttpClient,
    private val appContext: Context,
) {
    private val runtimes = HashMap<String, JsMetadataSource>()  // key = id + "#" + codeHash

    @Volatile
    private var snapshot: List<MetadataSource> = emptyList()

    @Volatile
    private var selected: MetadataSource? = null

    /** 非挂起读当前可用源（含 TMDB + HTTP 服务源 + 启用的 JS 源） */
    fun sources(): List<MetadataSource> = snapshot

    /** 非挂起读当前选中源；没选中/未配 → 回退到列表第一个（或 null） */
    fun currentSource(): MetadataSource? = selected ?: snapshot.firstOrNull()

    /** 读设置重建快照。启动时、以及任何相关设置变化后调用。 */
    suspend fun refresh() {
        val list = ArrayList<MetadataSource>()
        if (tmdb.configured()) list += tmdb
        if (metatube.configured()) list += metatube
        val js = enabledJsSources()
        for (s in js) {
            val m = runtimeFor(s)
            // 预热：把 SOURCE.id 读回 metaId，让下面的按 id 匹配稳定（引擎在 js-xxx 线程建，不卡这里）
            runCatching { m.ensureLoaded() }
            list += m
        }
        snapshot = list
        val wantId = prefs.selectedSourceId.first()
        selected = list.firstOrNull { it.id == wantId } ?: (if (tmdb.configured()) tmdb else list.firstOrNull())
    }

    private suspend fun enabledJsSources(): List<JsSource> {
        val stored = prefs.jsSources.first().filter { it.enabled }
        val scanned = scanDir(prefs.jsSourcesDir.first())
        // 编辑器里的优先（同 id 覆盖扫描的）；扫描目录用 dir_ 前缀 id，一般不冲突
        val ids = stored.map { it.id }.toSet()
        return stored + scanned.filterNot { it.id in ids }
    }

    private fun runtimeFor(js: JsSource): JsMetadataSource {
        val key = js.id + "#" + js.code.hashCode()
        return runtimes.getOrPut(key) {
            JsMetadataSource(JsRuntime(js.id, js.name, js.code, http), http)
        }
    }

    /** 扫描设置的本地目录（SAF tree）里的 *.js，每个文件当一个源（id 用 dir_+文件名，稳定） */
    private fun scanDir(uri: String): List<JsSource> {
        if (uri.isBlank()) return emptyList()
        return runCatching {
            val tree = DocumentFile.fromTreeUri(appContext, Uri.parse(uri)) ?: return emptyList()
            tree.listFiles().filter { it.isFile && it.name?.endsWith(".js", ignoreCase = true) == true }.mapNotNull { f ->
                val name = f.name ?: return@mapNotNull null
                val code = runCatching {
                    appContext.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull() ?: return@mapNotNull null
                JsSource("dir_" + name, name.removeSuffix(".js"), code, enabled = true)
            }
        }.getOrDefault(emptyList())
    }
}
