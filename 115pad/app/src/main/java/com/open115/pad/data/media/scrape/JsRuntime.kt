package com.open115.pad.data.media.scrape

import com.quickjs.JSArray
import com.quickjs.JavaCallback
import com.quickjs.JSContext
import com.quickjs.QuickJS
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors

/**
 * 一个 JS 源的运行时：持有一个 QuickJS 实例 + 上下文，加载预置桥接层 + 用户脚本。
 *
 * 契约（用户脚本要做的）：定义一个全局对象 `SOURCE`：
 * ```
 * SOURCE = {
 *   id: 'mysite', name: '我的源',
 *   search: function(query, kind) { return [ {id,title,year,...} ] },
 *   fetch:  function(id) { return {title,year,plot,rating,genres,actors:[{name,role}],posterUrl,fanartUrl,tmdbId} },
 *   backdrops: function(id, max) { return [url,...] }, // 可选（max = 宿主要求的张数上限，不限制时是个极大值）
 *   season: function(id, n) { return {season, episodes:[...]} }, // 可选
 * }
 * ```
 * 桥接层注入 `http.get/post`、`log`，并把一次调用包成 `__callExport(name, argsJson) → JSON 字符串`
 * （返回值统一 JSON.stringify，Kotlin 侧再解析，省掉 JSObject↔Kotlin 的逐字段映射）。
 *
 * 线程：QuickJS 上下文只能单线程用；这里用一个专属单线程执行器，所有调用都投递进去串行执行。
 */
class JsRuntime(
    private val sourceId: String,
    private val sourceName: String,
    private val code: String,
    private val http: OkHttpClient,
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "js-$sourceId").apply { isDaemon = true } }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var context: JSContext? = null
    private val initError = kotlinx.coroutines.CompletableDeferred<String?>()

    /** 元信息（id/name）；加载失败时回退到构造参数 */
    @Volatile
    var metaId: String = sourceId; private set
    @Volatile
    var metaName: String = sourceName; private set

    init {
        // 首次调用时惰性初始化上下文（QuickJS.createRuntime 会起一个 HandlerThread，别在构造期抢主线程）
    }

    private suspend fun ensureContext(): JSContext? = kotlinx.coroutines.withContext(dispatcher) {
        context?.let { return@withContext it }
        try {
            val runtime = QuickJS.createRuntime()
            val ctx = runtime.createContext()
            // 宿主：__http(method, url, headersJson, bodyJson) → JSON 字符串 {status, body}
            ctx.registerJavaMethod(JavaCallback { _, a ->
                hostHttp(
                    argStr(a, 0) ?: "GET",
                    argStr(a, 1) ?: "",
                    argStr(a, 2),
                    argStr(a, 3),
                )
            }, "__http")
            ctx.registerJavaMethod(JavaCallback { _, a ->
                android.util.Log.i("JsSource", "[${argStr(a, 0)}] ${argStr(a, 1)}")
                null
            }, "__log")
            // 注意：taoweiji QuickJS 的 executeXScript(code, fileName) 是「代码在前、文件名在后」
            ctx.executeVoidScript(PRELUDE, "prelude.js")
            ctx.executeVoidScript(code, "source.js")
            // 读回 SOURCE.id / name（读不到就用构造参数）。不用 globalThis（部分 QuickJS 构建无此全局），
            // 全局 eval 下裸赋值即成全局。
            runCatching {
                ctx.executeVoidScript("var __id = (typeof SOURCE!=='undefined'&&SOURCE&&SOURCE.id)||''; var __name=(typeof SOURCE!=='undefined'&&SOURCE&&SOURCE.name)||'';", "meta.js")
                val idStr = ctx.executeStringScript("String(typeof __id!=='undefined'?__id:'')", "getid")
                val nameStr = ctx.executeStringScript("String(typeof __name!=='undefined'?__name:'')", "getname")
                if (idStr.isNotBlank()) metaId = idStr
                if (nameStr.isNotBlank()) metaName = nameStr
            }
            context = ctx
            initError.complete(null)
            ctx
        } catch (e: Throwable) {
            android.util.Log.e("JsRuntime", "JS 源初始化失败 id=$sourceId", e)
            initError.complete(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /** 加载失败的原因（null = 成功）。首次调用会触发初始化。 */
    suspend fun loadError(): String? { ensureContext(); return initError.getCompleted() }

    /**
     * 调 SOURCE 上的一个导出函数，返回它 `JSON.stringify` 后的结果字符串。
     * 约定：`{"ok":true,"value":...}` 或 `{"ok":false,"error":"..."}`。异常/无函数都归成 ok:false。
     */
    suspend fun call(name: String, argsJson: String): String = kotlinx.coroutines.withContext(dispatcher) {
        val ctx = ensureContext() ?: return@withContext """{"ok":false,"error":"JS 源加载失败"}"""
        try {
            ctx.set("__callName", name)
            ctx.set("__callArgs", argsJson)
            ctx.executeStringScript("__callExport(typeof __callName!=='undefined'?__callName:'', typeof __callArgs!=='undefined'?__callArgs:'[]')", "call.js")
        } catch (e: Throwable) {
            """{"ok":false,"error":${jsonEscape(e.message ?: e.javaClass.simpleName)}}"""
        }
    }

    /** 解析 call 的返回：ok 时取 value 的原始 JSON 文本，否则抛带 error 的异常 */
    fun unwrap(resultJson: String): String {
        val root = runCatching { json.parseToJsonElement(resultJson).jsonObject }.getOrNull()
            ?: throw ScrapeException(ScrapeFailure.RequestError("JS 源返回非预期格式"))
        val ok = root["ok"]?.jsonPrimitive?.content == "true"
        if (!ok) throw ScrapeException(ScrapeFailure.RequestError("JS 源错误：" + (root["error"]?.jsonPrimitive?.content ?: "未知")))
        return root["value"]?.toString() ?: "null"
    }

    private fun argStr(a: JSArray, i: Int): String? = if (i < a.length()) a.getString(i) else null

    private fun hostHttp(method: String, url: String, headersJson: String?, bodyJson: String?): String {
        return try {
            val builder = Request.Builder().url(url)
            runCatching {
                json.parseToJsonElement(headersJson ?: "{}").jsonObject.forEach { (k, v) ->
                    builder.header(k, v.jsonPrimitive.content)
                }
            }
            if (method.equals("POST", true)) {
                builder.post((bodyJson ?: "").toRequestBody("application/json".toMediaType()))
            }
            http.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                buildString {
                    append("{\"status\":").append(resp.code)
                    append(",\"body\":").append(jsonEscape(body))
                    append("}")
                }
            }
        } catch (e: Throwable) {
            """{"status":0,"body":${jsonEscape(e.message ?: "http error")}}"""
        }
    }

    private fun jsonEscape(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
            '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    fun close() {
        executor.execute {
            runCatching { context?.close() }
            context = null
        }
        executor.shutdown()
    }

    companion object {
        /** 桥接层：注入 http/log，定义把 SOURCE 方法调用包成 JSON 的 __callExport */
        private const val PRELUDE = """
            var http = {
              get: function(url, opts) { return JSON.parse(__http('GET', url, JSON.stringify((opts&&opts.headers)||{}), null)); },
              post: function(url, body, opts) {
                var b = (typeof body === 'string') ? body : JSON.stringify(body == null ? '' : body);
                return JSON.parse(__http('POST', url, JSON.stringify((opts&&opts.headers)||{}), b));
              }
            };
            function log(m) { __log('SOURCE', String(m)); }
            function __callExport(name, argsJson) {
              try {
                var args = JSON.parse(argsJson || '[]');
                var S = (typeof SOURCE !== 'undefined') ? SOURCE : null;
                var fn = S && S[name];
                if (typeof fn !== 'function') return JSON.stringify({ok:false, error:'源没有实现 ' + name + '()'});
                var r = fn.apply(null, args);
                return JSON.stringify({ok:true, value: (r === undefined ? null : r)});
              } catch (e) {
                return JSON.stringify({ok:false, error: String((e && (e.stack||e.message)) || e)});
              }
            };
        """
    }
}
