package com.open115.pad.data.media

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.open115.pad.data.media.scrape.AiPromptTemplate
import com.open115.pad.data.media.scrape.JsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.mediaDataStore by preferencesDataStore(name = "media")

/**
 * 媒体库设置（跨重启保留）。
 *
 * 注意：**没有**"全局自动扫描"这一项 —— 每个库的开关是
 * [MediaLibraryEntity.autoScanOnStart]（每库独立），两者同名过一段时间，
 * 那个全局字段从来没被读过，已经删掉。
 */
class MediaPrefs(private val context: Context) {

    /** 媒体库落盘缓存总开关（默认开）。关掉 = 不读写磁盘缓存，已存的不动 */
    val cacheEnabled: Flow<Boolean> = context.mediaDataStore.data.map { it[KEY_CACHE_ENABLED] ?: true }

    /** 缓存上限（MB），默认 2 GB；[UNLIMITED_MB] = 不限制。主要限制海报字节，nfo 是 KB 级的 */
    val cacheMaxMb: Flow<Long> = context.mediaDataStore.data.map { it[KEY_CACHE_MAX_MB] ?: DEFAULT_MAX_MB }

    /** 作品列表的排序方式（作品页表头那个菜单选的），存名字不存序号 —— 序号改了不至于串味 */
    val worksSort: Flow<WorksSort> =
        context.mediaDataStore.data.map { WorksSort.ofName(it[KEY_WORKS_SORT]) }

    /**
     * 扫描队列的整份快照（JSON，结构见 [MediaScanner] 里的 ScanQueueSnapshot）。
     *
     * 存**整份**而不是逐条增删：队列最多几条，一次写清楚比逐条维护简单得多，
     * 也不会读出"删了一半"的状态。启动时读一次（[scanQueueJson]），之后由扫描器负责写。
     */
    suspend fun scanQueueJson(): String? = context.mediaDataStore.data.first()[KEY_SCAN_QUEUE]

    suspend fun setScanQueue(json: String) =
        context.mediaDataStore.edit { it[KEY_SCAN_QUEUE] = json }

    suspend fun setWorksSort(sort: WorksSort) =
        context.mediaDataStore.edit { it[KEY_WORKS_SORT] = sort.name }

    suspend fun setCacheEnabled(enabled: Boolean) =
        context.mediaDataStore.edit { it[KEY_CACHE_ENABLED] = enabled }

    suspend fun setCacheMaxMb(mb: Long) = context.mediaDataStore.edit {
        it[KEY_CACHE_MAX_MB] = if (mb <= 0L) UNLIMITED_MB else mb.coerceIn(MIN_MAX_MB, MAX_MAX_MB)
    }

    // ---- 流水线同步（服务器刮削完把结果搬进媒体库，见 [PadSync]）----

    /** 自动同步总开关。默认开：没配过也能自己找到信号目录（媒体库根下的 `.pad_signal`） */
    val padSyncEnabled: Flow<Boolean> = context.mediaDataStore.data.map { it[KEY_PAD_ENABLED] ?: true }

    /** 信号目录的 cid（自动找到或用户手选；空 = 还没找到，下次轮询再找） */
    val padSignalCid: Flow<String> = context.mediaDataStore.data.map { it[KEY_PAD_DIR_CID] ?: "" }

    /** 信号目录的名字，只为设置页显示（真正的定位靠 cid） */
    val padSignalName: Flow<String> = context.mediaDataStore.data.map { it[KEY_PAD_DIR_NAME] ?: "" }

    /**
     * 已处理到哪个信号文件（文件名就是时间戳，按字符串比大小即时间序）。
     *
     * 落盘而不是只放内存：App 没开着的期间服务器攒下的信号，下次启动要接着处理。
     */
    val padCursor: Flow<String> = context.mediaDataStore.data.map { it[KEY_PAD_CURSOR] ?: "" }

    /** 轮询间隔（分钟） */
    val padPollMinutes: Flow<Int> = context.mediaDataStore.data.map { it[KEY_PAD_POLL_MINUTES] ?: 10 }

    /** 上次轮询时刻（设置页状态行） */
    val padLastAt: Flow<Long> = context.mediaDataStore.data.map { it[KEY_PAD_LAST_AT] ?: 0L }

    /** 上次轮询结果一句话（设置页状态行） */
    val padLastNote: Flow<String> = context.mediaDataStore.data.map { it[KEY_PAD_LAST_NOTE] ?: "" }

    suspend fun setPadSyncEnabled(enabled: Boolean) =
        context.mediaDataStore.edit { it[KEY_PAD_ENABLED] = enabled }

    suspend fun setPadSignalDir(cid: String, name: String) = context.mediaDataStore.edit {
        it[KEY_PAD_DIR_CID] = cid
        it[KEY_PAD_DIR_NAME] = name
    }

    suspend fun setPadCursor(name: String) = context.mediaDataStore.edit { it[KEY_PAD_CURSOR] = name }

    suspend fun setPadPollMinutes(minutes: Int) =
        context.mediaDataStore.edit { it[KEY_PAD_POLL_MINUTES] = minutes.coerceIn(1, 24 * 60) }

    suspend fun setPadLast(at: Long, note: String) = context.mediaDataStore.edit {
        it[KEY_PAD_LAST_AT] = at
        it[KEY_PAD_LAST_NOTE] = note
    }

    // ---- 演员头像补全（gfriends 那类头像仓库，见 [ActorAvatarSource]）----
    //
    // 地址**不写死**：存在 DataStore 里、可在「实验室」改（第三方地址说变就变）。
    // 代码里那个常量只当**首次的预填值**，改地址不需要改代码。

    /** 总开关。默认**关**：开着才会去那个第三方地址拉索引、取头像 */
    val avatarSourceEnabled: Flow<Boolean> =
        context.mediaDataStore.data.map { it[KEY_AVATAR_ENABLED] ?: false }

    /** 头像源基地址（末尾斜杠会归一化），空 = 没配 */
    val avatarSourceUrl: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_AVATAR_URL] ?: ActorAvatarSource.DEFAULT_BASE }

    /** 本地索引的条数与更新时间（实验室状态行） */
    val avatarIndexCount: Flow<Int> = context.mediaDataStore.data.map { it[KEY_AVATAR_COUNT] ?: 0 }
    val avatarIndexAt: Flow<Long> = context.mediaDataStore.data.map { it[KEY_AVATAR_AT] ?: 0L }

    /** 覆盖率：更新索引时顺手算的"本库演员能对上多少个"，直接回答"这个源对你有没有用" */
    val avatarCoverageHit: Flow<Int> = context.mediaDataStore.data.map { it[KEY_AVATAR_HIT] ?: 0 }
    val avatarCoverageTotal: Flow<Int> = context.mediaDataStore.data.map { it[KEY_AVATAR_TOTAL] ?: 0 }

    suspend fun setAvatarSourceEnabled(enabled: Boolean) =
        context.mediaDataStore.edit { it[KEY_AVATAR_ENABLED] = enabled }

    suspend fun setAvatarSourceUrl(url: String) =
        context.mediaDataStore.edit { it[KEY_AVATAR_URL] = url.trim() }

    suspend fun setAvatarIndex(count: Int, at: Long, hit: Int, total: Int) =
        context.mediaDataStore.edit {
            it[KEY_AVATAR_COUNT] = count
            it[KEY_AVATAR_AT] = at
            it[KEY_AVATAR_HIT] = hit
            it[KEY_AVATAR_TOTAL] = total
        }

    // ---- 元数据刮削（TMDB）：地址与 key 全都不写死，见 [MetadataScraper] ----

    /**
     * TMDB 接口基址。默认官方域名，**国内直连多半不通**（DNS 污染，见 docs 里 TMDB 那轮的结论），
     * 所以用户通常要填自己服务器上的反代地址 —— 能拼出 `{基址}/3/search/movie` 即可。
     */
    val scrapeTmdbApiBase: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_SCRAPE_API_BASE]?.takeIf { v -> v.isNotBlank() } ?: DEFAULT_TMDB_API_BASE }

    /** TMDB 图片基址（海报/缩略图），可与接口地址走同一个反代 */
    val scrapeTmdbImageBase: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_SCRAPE_IMAGE_BASE]?.takeIf { v -> v.isNotBlank() } ?: DEFAULT_TMDB_IMAGE_BASE }

    /** TMDB API key（v3 的 32 位 key，或 v4 的 JWT 以 eyJ 开头 —— 两种都认，见 [TmdbClient]） */
    val scrapeTmdbApiKey: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_SCRAPE_API_KEY] ?: "" }

    suspend fun setScrapeTmdbApiBase(url: String) =
        context.mediaDataStore.edit { it[KEY_SCRAPE_API_BASE] = url.trim() }

    suspend fun setScrapeTmdbImageBase(url: String) =
        context.mediaDataStore.edit { it[KEY_SCRAPE_IMAGE_BASE] = url.trim() }

    suspend fun setScrapeTmdbApiKey(key: String) =
        context.mediaDataStore.edit { it[KEY_SCRAPE_API_KEY] = key.trim() }

    /**
     * 给刮削这条路单独用的**代理地址**（空 = 直连）。TMDB 在国内直连不通，
     * 用户填自己已有的代理（`host:port` / `socks5://host:port`）即可，不需要给 App 全局挂代理。
     */
    val scrapeProxy: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_SCRAPE_PROXY] ?: "" }

    suspend fun setScrapeProxy(url: String) =
        context.mediaDataStore.edit { it[KEY_SCRAPE_PROXY] = url.trim() }

    // ---- AI 检索词（可选）：把乱七八糟的文件名交给 OpenAI 兼容的大模型洗成干净的片名，见 [AiQueryClient] ----
    // 端点/key/模型全不写死、默认留空；没配全之前界面上的「用 AI 优化」按钮不出现。

    /** OpenAI 兼容的服务基址（能拼出 `{基址}/chat/completions`），如 https://api.openai.com/v1 */
    val aiEndpoint: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_AI_ENDPOINT] ?: "" }

    suspend fun setAiEndpoint(url: String) =
        context.mediaDataStore.edit { it[KEY_AI_ENDPOINT] = url.trim() }

    val aiApiKey: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_AI_API_KEY] ?: "" }

    suspend fun setAiApiKey(key: String) =
        context.mediaDataStore.edit { it[KEY_AI_API_KEY] = key.trim() }

    /** 模型名，如 gpt-4o-mini / deepseek-chat */
    val aiModel: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_AI_MODEL] ?: "" }

    suspend fun setAiModel(m: String) =
        context.mediaDataStore.edit { it[KEY_AI_MODEL] = m.trim() }

    /** 用户自建的提示词模板（内置的不在这里，见 [com.open115.pad.data.media.scrape.AiPromptTemplates.BUILTINS]） */
    val aiCustomTemplates: Flow<List<AiPromptTemplate>> =
        context.mediaDataStore.data.map { decodeTemplates(it[KEY_AI_CUSTOM_TEMPLATES]) }

    suspend fun setAiCustomTemplates(list: List<AiPromptTemplate>) =
        context.mediaDataStore.edit { it[KEY_AI_CUSTOM_TEMPLATES] = encodeTemplates(list) }

    /** 新建一个自定义模板，返回它的 id（id 用时间戳，够唯一） */
    suspend fun addAiTemplate(name: String, prompt: String): String {
        val id = "custom_" + System.currentTimeMillis()
        setAiCustomTemplates(aiCustomTemplates.first() + AiPromptTemplate(id, name.trim(), prompt.trim(), false))
        return id
    }

    suspend fun updateAiTemplate(id: String, name: String, prompt: String) =
        setAiCustomTemplates(
            aiCustomTemplates.first().map { if (it.id == id) it.copy(name = name.trim(), prompt = prompt.trim()) else it },
        )

    suspend fun deleteAiTemplate(id: String) =
        setAiCustomTemplates(aiCustomTemplates.first().filterNot { it.id == id })

    /** 刮削场景默认用哪个模板 id */
    val aiScrapeTemplate: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_AI_SCRAPE_TPL] ?: com.open115.pad.data.media.scrape.AiPromptTemplates.SCRAPE_QUERY }

    suspend fun setAiScrapeTemplate(id: String) =
        context.mediaDataStore.edit { it[KEY_AI_SCRAPE_TPL] = id }

    /** 重命名场景默认用哪个模板 id */
    val aiRenameTemplate: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_AI_RENAME_TPL] ?: com.open115.pad.data.media.scrape.AiPromptTemplates.RENAME_CLEAN }

    suspend fun setAiRenameTemplate(id: String) =
        context.mediaDataStore.edit { it[KEY_AI_RENAME_TPL] = id }

    /** 多选批量刮削的改名方式（记住上次）：存枚举名，默认 NONE */
    val scrapeRenameMode: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_SCRAPE_RENAME_MODE] ?: "NONE" }

    suspend fun setScrapeRenameMode(mode: String) =
        context.mediaDataStore.edit { it[KEY_SCRAPE_RENAME_MODE] = mode }

    /** 手动命名的模板（记住上次），token：{片名}{原名}{年份}{序号} */
    val scrapeRenameTemplate: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_SCRAPE_RENAME_TPL]?.takeIf { v -> v.isNotBlank() } ?: "{片名}" }

    suspend fun setScrapeRenameTemplate(t: String) =
        context.mediaDataStore.edit { it[KEY_SCRAPE_RENAME_TPL] = t.trim() }

    /**
     * 刮削写回时每部片存的剧照张数上限（按赞数取前几张进 `<前缀>-extrafanart/` 目录）。
     *
     * [SCRAPE_STILLS_UNLIMITED]（0）= 不限制：源给多少存多少（写回更慢、流量更大）。
     * 只管**刮削写回**这一条路 —— 扫描器本来就不下剧照字节（只记 pick_code、详情页按需缓存），不受它影响。
     */
    val scrapeStillsMax: Flow<Int> =
        context.mediaDataStore.data.map { it[KEY_SCRAPE_STILLS_MAX] ?: DEFAULT_SCRAPE_STILLS_MAX }

    suspend fun setScrapeStillsMax(n: Int) =
        context.mediaDataStore.edit { it[KEY_SCRAPE_STILLS_MAX] = n.coerceIn(SCRAPE_STILLS_UNLIMITED, SCRAPE_STILLS_HARD_MAX) }

    /**
     * 单片刮削「搜完自动刮第一条」开关（默认**关**）。
     *
     * 开着时：对话框搜出结果后，用户 5 秒内没手动点选任何候选，就自动选**第一条**并直接写回。
     * 只在对话框开着时计时（关框即取消，挂后台搜索不会自动落笔）；用户一旦点过候选就不再自动刮。
     */
    val scrapeAutoFirst: Flow<Boolean> =
        context.mediaDataStore.data.map { it[KEY_SCRAPE_AUTO_FIRST] ?: false }

    suspend fun setScrapeAutoFirst(enabled: Boolean) =
        context.mediaDataStore.edit { it[KEY_SCRAPE_AUTO_FIRST] = enabled }

    // ---- 插件化 JS 源 ----

    /** 用户在 App 内建的 JS 源（内置的 TMDB 不在这里）。存 JSON 列表。 */
    val jsSources: Flow<List<JsSource>> =
        context.mediaDataStore.data.map { decodeJsSources(it[KEY_JS_SOURCES]) }

    suspend fun setJsSources(list: List<JsSource>) =
        context.mediaDataStore.edit { it[KEY_JS_SOURCES] = encodeJsSources(list) }

    suspend fun addJsSource(name: String, code: String): String {
        val id = "js_" + System.currentTimeMillis()
        setJsSources(jsSources.first() + JsSource(id, name.trim(), code, enabled = true))
        return id
    }

    suspend fun updateJsSource(id: String, name: String, code: String) =
        setJsSources(jsSources.first().map { if (it.id == id) it.copy(name = name.trim(), code = code) else it })

    suspend fun setJsSourceEnabled(id: String, enabled: Boolean) =
        setJsSources(jsSources.first().map { if (it.id == id) it.copy(enabled = enabled) else it })

    suspend fun deleteJsSource(id: String) =
        setJsSources(jsSources.first().filterNot { it.id == id })

    /** 当前选用的源 id（"tmdb" 或某个 js 源 id） */
    val selectedSourceId: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_SELECTED_SOURCE] ?: "tmdb" }

    suspend fun setSelectedSourceId(id: String) =
        context.mediaDataStore.edit { it[KEY_SELECTED_SOURCE] = id }

    /** 扫描 .js 的本地目录（SAF tree uri；空 = 不扫描） */
    val jsSourcesDir: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_JS_DIR] ?: "" }

    suspend fun setJsSourcesDir(uri: String) =
        context.mediaDataStore.edit { it[KEY_JS_DIR] = uri }

    // ---- HTTP 服务源 ----
    // 插件的另一种形态：代码跑在服务器上，App 只当一个 HTTP 客户端。
    // 协议即 HTTP 服务源的 REST（/v1/movies/search、/v1/movies/{provider}/{id}）——
    // 自建服务照这三个端点实现就能接进来。

    /** 服务基址（如 `http://192.168.x.x:8090`，不带尾部斜杠）；空 = 不启用这个源 */
    val metatubeUrl: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_METATUBE_URL]?.trim()?.trimEnd('/') ?: "" }

    suspend fun setMetatubeUrl(url: String) =
        context.mediaDataStore.edit { it[KEY_METATUBE_URL] = url.trim() }

    /** 鉴权 token（服务端设了 -token 才要填；空 = 不带 Authorization 头） */
    val metatubeToken: Flow<String> =
        context.mediaDataStore.data.map { it[KEY_METATUBE_TOKEN]?.trim() ?: "" }

    suspend fun setMetatubeToken(token: String) =
        context.mediaDataStore.edit { it[KEY_METATUBE_TOKEN] = token.trim() }

    companion object {
        const val DEFAULT_MAX_MB = 2048L
        const val MIN_MAX_MB = 512L
        const val MAX_MAX_MB = 8192L

        /** "不限制"挡位的存储值。0 的语义（上限 ≤ 0 视为不限）消费端各有判，见 textPoolBytes / pruneCache / sweep */
        const val UNLIMITED_MB = 0L

        /** 滑块步进 */
        const val STEP_MB = 256L

        /** 剧照张数：默认与 [com.open115.pad.data.media.scrape.MetadataScraper.EXTRAFANART_MAX] 一致 */
        const val DEFAULT_SCRAPE_STILLS_MAX = 8

        /** 剧照张数滑块的右端点（再往右没有挡位） */
        const val SCRAPE_STILLS_HARD_MAX = 30

        /** 剧照张数"不限制"挡的存储值（0 = 源给多少存多少） */
        const val SCRAPE_STILLS_UNLIMITED = 0

        private val KEY_CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        private val KEY_CACHE_MAX_MB = longPreferencesKey("cache_max_mb")
        private val KEY_WORKS_SORT = stringPreferencesKey("works_sort")
        private val KEY_SCAN_QUEUE = stringPreferencesKey("scan_queue")
        private val KEY_PAD_ENABLED = booleanPreferencesKey("pad_sync_enabled")
        private val KEY_PAD_DIR_CID = stringPreferencesKey("pad_signal_cid")
        private val KEY_PAD_DIR_NAME = stringPreferencesKey("pad_signal_name")
        private val KEY_PAD_CURSOR = stringPreferencesKey("pad_cursor")
        private val KEY_PAD_POLL_MINUTES = intPreferencesKey("pad_poll_minutes")
        private val KEY_PAD_LAST_AT = longPreferencesKey("pad_last_at")
        private val KEY_PAD_LAST_NOTE = stringPreferencesKey("pad_last_note")

        private val KEY_AVATAR_ENABLED = booleanPreferencesKey("avatar_source_enabled")
        private val KEY_AVATAR_URL = stringPreferencesKey("avatar_source_url")
        private val KEY_AVATAR_COUNT = intPreferencesKey("avatar_index_count")
        private val KEY_AVATAR_AT = longPreferencesKey("avatar_index_at")
        private val KEY_AVATAR_HIT = intPreferencesKey("avatar_coverage_hit")
        private val KEY_AVATAR_TOTAL = intPreferencesKey("avatar_coverage_total")

        /** TMDB 官方地址。只是**预填值**，用户可在实验室改成反代地址（改地址不需要改代码） */
        const val DEFAULT_TMDB_API_BASE = "https://api.themoviedb.org"
        const val DEFAULT_TMDB_IMAGE_BASE = "https://image.tmdb.org"

        private val KEY_SCRAPE_API_BASE = stringPreferencesKey("scrape_tmdb_api_base")
        private val KEY_SCRAPE_IMAGE_BASE = stringPreferencesKey("scrape_tmdb_image_base")
        private val KEY_SCRAPE_API_KEY = stringPreferencesKey("scrape_tmdb_api_key")
        private val KEY_SCRAPE_PROXY = stringPreferencesKey("scrape_proxy")

        private val KEY_AI_ENDPOINT = stringPreferencesKey("ai_endpoint")
        private val KEY_AI_API_KEY = stringPreferencesKey("ai_api_key")
        private val KEY_AI_MODEL = stringPreferencesKey("ai_model")
        private val KEY_AI_CUSTOM_TEMPLATES = stringPreferencesKey("ai_custom_templates")
        private val KEY_AI_SCRAPE_TPL = stringPreferencesKey("ai_scrape_template")
        private val KEY_AI_RENAME_TPL = stringPreferencesKey("ai_rename_template")
        private val KEY_SCRAPE_RENAME_MODE = stringPreferencesKey("scrape_rename_mode")
        private val KEY_SCRAPE_RENAME_TPL = stringPreferencesKey("scrape_rename_template")
        private val KEY_SCRAPE_STILLS_MAX = intPreferencesKey("scrape_stills_max")
        private val KEY_SCRAPE_AUTO_FIRST = booleanPreferencesKey("scrape_auto_first")
        private val KEY_JS_SOURCES = stringPreferencesKey("js_sources")
        private val KEY_SELECTED_SOURCE = stringPreferencesKey("selected_source")
        private val KEY_JS_DIR = stringPreferencesKey("js_sources_dir")
        private val KEY_METATUBE_URL = stringPreferencesKey("metatube_url")
        private val KEY_METATUBE_TOKEN = stringPreferencesKey("metatube_token")

        private val jsJson = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun decodeJsSources(raw: String?): List<JsSource> =
            if (raw.isNullOrBlank()) emptyList() else runCatching {
                jsJson.decodeFromString(ListSerializer(JsSource.serializer()), raw)
            }.getOrDefault(emptyList())

        internal fun encodeJsSources(list: List<JsSource>): String =
            jsJson.encodeToString(ListSerializer(JsSource.serializer()), list)

        private val tplJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 自定义模板只存 id/name/prompt（builtin 恒 false），读回来补 builtin=false */
        @Serializable
        private data class StoredTemplate(val id: String, val name: String, val prompt: String)

        internal fun decodeTemplates(raw: String?): List<AiPromptTemplate> {
            if (raw.isNullOrBlank()) return emptyList()
            val list = runCatching {
                tplJson.decodeFromString(ListSerializer(StoredTemplate.serializer()), raw)
            }.getOrNull() ?: return emptyList()
            return list.map { AiPromptTemplate(it.id, it.name, it.prompt, false) }
        }

        internal fun encodeTemplates(list: List<AiPromptTemplate>): String {
            val stored = list.filterNot { it.builtin }.map { StoredTemplate(it.id, it.name, it.prompt) }
            return tplJson.encodeToString(ListSerializer(StoredTemplate.serializer()), stored)
        }

        /**
         * nfo 文本池从总上限里分到的份额（5%）。
         *
         * 单独切一小块而不是共用一个池：海报是 MB 级、nfo 是 KB 级，量级差三个数量级，
         * 混在一起淘汰时 nfo 会被海报挤光（而且几乎不影响总量，挤掉也不释放空间）。
         * 份额取总上限的 5% —— nfo 实际远用不满（几千部片才几 MB），这个数只是天花板；
         * 上限为 0（不限制）时算出来也是 0，MediaCache.sweep 把 ≤ 0 一并视为不限。
         */
        fun textPoolBytes(totalMb: Long): Long = totalMb * 1024 * 1024 / 20
    }
}
