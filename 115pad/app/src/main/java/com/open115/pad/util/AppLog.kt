package com.open115.pad.util

import android.content.Context
import android.os.Build
import com.open115.pad.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 应用日志门面：照常写 logcat，同时（开关打开时）追加到 `filesDir/logs/app-YYYY-MM-DD.log`。
 *
 * 为什么需要它：`android.util.Log` 没有 hook 点，崩溃/线上问题只能靠 adb。这里把全仓的
 * `import android.util.Log` 换成 `import com.open115.pad.util.AppLog as Log`，调用点一行不用改。
 *
 * 约束：
 * - **只在本机**：日志不上传；导出走系统分享，用户自己决定发给谁；
 * - 写入前脱敏 access_token / refresh_token / Authorization / Cookie 等字段；
 * - 总量上限 [MAX_TOTAL_BYTES]、保留 [KEEP_DAYS] 天，超了从最旧的删；
 * - 写文件在单独的守护线程里做，调用方（包括主线程）不会被磁盘 IO 卡住；
 * - 队列满了丢行并记账，绝不阻塞业务；崩溃由 uncaught handler 单独落一个文件（带最近日志）。
 */
object AppLog {

    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024
    private const val KEEP_DAYS = 7L
    private const val RING_LINES = 200
    private const val QUEUE_CAPACITY = 4096
    private const val MAX_MESSAGE = 4000

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val FULL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 脱敏：Authorization: Bearer xxx / Authorization=xxx —— 整段值都吃掉 */
    private val AUTH = Regex(
        "(?i)\\b(Authorization)\\b(\\s*[:=]\\s*)(?:Bearer\\s+)?[A-Za-z0-9._~+/=-]+",
    )

    /** 脱敏：key=value / key: value 形式的凭据字段 */
    private val KEY_SECRET = Regex(
        "(?i)\\b(access_token|refresh_token|cookie|seid|kid|sign_key|sign_val|client_secret)\\b(\\s*[=:]\\s*)([^\\s&,;\"']+)",
    )

    /** 脱敏：孤立的 Bearer xxx（多半是日志里手拼的请求头） */
    private val BEARER = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")

    @Volatile
    private var enabled = true

    @Volatile
    private var logDir: File? = null

    private val started = AtomicBoolean(false)
    private val crashInstalled = AtomicBoolean(false)
    private val crashing = AtomicBoolean(false)
    private val dropped = AtomicLong(0)

    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
    private val ring = ArrayDeque<String>()

    data class Stats(val files: Int, val bytes: Long)

    /** 进程启动时调用一次：定目录、起写线程、装崩溃 handler */
    @Synchronized
    fun init(context: Context) {
        if (logDir != null) return
        val dir = File(context.filesDir, "logs")
        runCatching { dir.mkdirs() }
        logDir = dir
        if (started.compareAndSet(false, true)) {
            Thread({ writerLoop() }, "app-log-writer").apply { isDaemon = true }.start()
        }
        installCrashHandler()
        runCatching { prune() }
        i(
            "AppLog",
            "==== 启动 v${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}" +
                " (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} ====",
        )
    }

    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        if (!on) {
            i("AppLog", "日志文件已关闭")
            enabled = false
        } else {
            enabled = true
            i("AppLog", "日志文件已开启")
        }
    }

    fun isEnabled(): Boolean = enabled

    // ---------------- android.util.Log 的等价入口 ----------------

    fun d(tag: String, msg: String) = emit('D', tag, msg, null)

    fun i(tag: String, msg: String) = emit('I', tag, msg, null)

    fun w(tag: String, msg: String) = emit('W', tag, msg, null)

    fun w(tag: String, msg: String, tr: Throwable?) = emit('W', tag, msg, tr)

    fun e(tag: String, msg: String) = emit('E', tag, msg, null)

    fun e(tag: String, msg: String, tr: Throwable?) = emit('E', tag, msg, tr)

    private fun emit(level: Char, tag: String, msg: String, tr: Throwable?) {
        val safe = redact(msg)
        // logcat 也写脱敏后的版本：adb 能看，但令牌不会出现在日志里
        when (level) {
            'D' -> android.util.Log.d(tag, safe)
            'I' -> android.util.Log.i(tag, safe)
            'W' -> if (tr == null) android.util.Log.w(tag, safe) else android.util.Log.w(tag, safe, tr)
            'E' -> if (tr == null) android.util.Log.e(tag, safe) else android.util.Log.e(tag, safe, tr)
        }
        if (!enabled) return
        val line = formatLine(level, tag, safe, tr)
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > RING_LINES) ring.removeFirst()
        }
        if (!queue.offer(line)) dropped.incrementAndGet()
    }

    private fun formatLine(level: Char, tag: String, msg: String, tr: Throwable?): String {
        val capped = if (msg.length <= MAX_MESSAGE) msg else msg.take(MAX_MESSAGE) + "…(截断)"
        val head = "${LocalDateTime.now().format(TIME)} $level/$tag: $capped"
        return if (tr == null) head else head + "\n" + android.util.Log.getStackTraceString(tr)
    }

    /** 凭据字段打码。公开给单测用（不依赖 Context） */
    internal fun redact(msg: String): String {
        // 顺序有讲究：先处理 Authorization 整段（带 Bearer 的情况），再处理 key=value，
        // 最后兜底孤立的 Bearer；反过来会把 "Bearer" 先换成 ***，后面就认不出它后面的令牌了。
        var s = AUTH.replace(msg) { m -> "${m.groupValues[1]}${m.groupValues[2]}***" }
        s = KEY_SECRET.replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]}***" }
        s = BEARER.replace(s, "Bearer ***")
        return s
    }

    // ---------------- 文件写入 ----------------

    private fun writerLoop() {
        val batch = ArrayList<String>(256)
        var sincePrune = 0
        while (true) {
            val first = try {
                queue.take()
            } catch (_: InterruptedException) {
                continue
            }
            batch.clear()
            batch.add(first)
            queue.drainTo(batch, 255)
            if (enabled) runCatching { appendBatch(batch) }
            if (++sincePrune >= 50) {
                sincePrune = 0
                runCatching { prune() }
            }
            val lost = dropped.getAndSet(0)
            if (lost > 0 && enabled) {
                runCatching {
                    appendBatch(
                        listOf(
                            "${LocalDateTime.now().format(TIME)} W/AppLog: 日志队列满，丢弃 $lost 行",
                        ),
                    )
                }
            }
        }
    }

    private fun appendBatch(lines: List<String>) {
        val dir = logDir ?: return
        val file = File(dir, "app-${LocalDate.now()}.log")
        runCatching { file.parentFile?.mkdirs() }
        OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8).use { w ->
            for (line in lines) {
                w.write(line)
                w.write("\n")
            }
            w.flush()
        }
    }

    private fun prune() {
        val dir = logDir ?: return
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedBy { it.lastModified() }
            ?: return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600_000L
        files.filter { it.lastModified() < cutoff }.forEach { runCatching { it.delete() } }
        var total = files.filter { it.exists() }.sumOf { it.length() }
        for (f in files.filter { it.exists() }.sortedBy { it.lastModified() }) {
            if (total <= MAX_TOTAL_BYTES) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) total -= len
        }
    }

    fun stats(): Stats {
        val dir = logDir ?: return Stats(0, 0)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }.orEmpty()
        return Stats(files.size, files.sumOf { it.length() })
    }

    /** 清空本机日志（包括崩溃文件与已导出的 zip）。不动其他数据 */
    fun clear(context: Context) {
        val dir = logDir ?: return
        dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.forEach { runCatching { it.delete() } }
        // 导出过的 zip 也含日志，清空时一起删，免得"清空后还能在 cache 里翻到旧日志"
        runCatching { File(context.cacheDir, "diag").deleteRecursively() }
        synchronized(ring) { ring.clear() }
        dropped.set(0)
    }

    /** 打包日志到 cache/diag/，供系统分享。没有日志返回 null */
    suspend fun exportZip(context: Context): File? = withContext(Dispatchers.IO) {
        val dir = logDir ?: return@withContext null
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedBy { it.name }
            ?: return@withContext null
        if (files.isEmpty()) return@withContext null
        val outDir = File(context.cacheDir, "diag")
        runCatching { outDir.mkdirs() }
        // 只留最新一份导出包，避免 cache 里越积越多
        outDir.listFiles()?.forEach { runCatching { it.delete() } }
        val zip = File(outDir, "115OpenPad-logs-${LocalDateTime.now().format(FILE_STAMP)}.zip")
        runCatching {
            ZipOutputStream(FileOutputStream(zip).buffered()).use { z ->
                for (f in files) {
                    z.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }.onFailure {
            runCatching { zip.delete() }
            return@withContext null
        }
        return@withContext zip.takeIf { it.exists() && it.length() > 0 }
    }

    // ---------------- 崩溃 ----------------

    private fun installCrashHandler() {
        if (!crashInstalled.compareAndSet(false, true)) return
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, tr ->
            if (crashing.compareAndSet(false, true)) {
                runCatching { writeCrash(thread, tr) }
            }
            if (prev != null) {
                prev.uncaughtException(thread, tr)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun writeCrash(thread: Thread, tr: Throwable) {
        if (!enabled) return
        val dir = logDir ?: return
        runCatching { dir.mkdirs() }
        val file = File(dir, "crash-${LocalDateTime.now().format(FILE_STAMP)}.log")
        OutputStreamWriter(FileOutputStream(file, false), Charsets.UTF_8).use { w ->
            w.write("==== CRASH ${LocalDateTime.now().format(FULL_TIME)} ====\n")
            w.write(
                "v${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}" +
                    " (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n",
            )
            w.write("thread: ${thread.name}\n")
            w.write(android.util.Log.getStackTraceString(tr))
            w.write("\n---- 最近日志 ----\n")
            synchronized(ring) {
                for (line in ring) {
                    w.write(line)
                    w.write("\n")
                }
            }
            w.flush()
        }
        runCatching { prune() }
    }
}
