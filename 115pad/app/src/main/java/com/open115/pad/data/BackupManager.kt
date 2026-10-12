package com.open115.pad.data

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.open115.pad.data.media.MediaDatabase
import com.open115.pad.util.APP_USER_AGENT
import com.open115.pad.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 云端备份管理器：三类数据各自打包成 zip 上传到云端备份目录（每类保留最近 [KEEP] 份），
 * 并支持从云端恢复。
 *
 * | kind | 内容 | 载体 | 恢复方式 |
 * |------|------|------|----------|
 * | CONFIG | 应用配置 | 8 个 DataStore 的 preferences_pb | 覆盖文件 + **重启进程生效** |
 * | MEDIA | 媒体库（结构数据 + 记录 + 图片缓存） | media.db 十张表导出的 db + media_img 目录整包 | 表按列交集回插、图片合并放回，立即生效 |
 * | OPLOG | 文件操作记录 | op_log DataStore | 覆盖文件 + 重启生效 |
 *
 * MEDIA 合成一类是用户定的：图片/数据/记录只在换机时一起用，分开三个开关太繁琐。
 * 首次成功的 MEDIA 备份会顺带清掉旧版拆分三类（pad_library_/pad_medialog_/pad_img_）的遗留文件。
 *
 * 备份目录固定在 115 根目录下的 [BACKUP_DIR_NAME]（首次备份时自动找/建并记住 cid）。
 * 文件名 `pad_<kind>_<yyyyMMdd-HHmmss>.zip`：时间戳即文件名，字符串排序就是时间序。
 */
class BackupManager(
    private val context: Context,
    private val api: OpenApi,
    private val prefs: BackupPrefs,
    private val resolver: ImageUrlResolver,
    private val mediaDb: MediaDatabase,
    private val okHttpClient: OkHttpClient,
    private val transferLog: TransferLog,
    /**
     * 媒体库图片缓存的**根目录**（App115.cacheDir = context.cacheDir/images），
     * media_img 挂在它下面。⚠️ 不是 context.cacheDir —— 那样会指到没有图片的上一层。
     */
    private val mediaCacheRoot: File,
) {

    sealed class BackupResult {
        data class Ok(val fileName: String, val bytes: Long) : BackupResult()
        data class Failed(val message: String) : BackupResult()

        override fun toString(): String = when (this) {
            is Ok -> "成功：$fileName（${Format.size(bytes)}）"
            is Failed -> "失败：$message"
        }
    }

    sealed class RestoreResult {
        data class Ok(val message: String, val needsRestart: Boolean) : RestoreResult()
        data class Failed(val message: String) : RestoreResult()
    }

    /** 该 kind 是否到了自动备份的时间（开关 + 间隔 + 上次时间） */
    suspend fun isDue(kind: BackupKind): Boolean {
        if (!prefs.enabled(kind).first()) return false
        val interval = prefs.intervalHours(kind).first().toLong()
        if (interval <= 0L) return false
        val last = prefs.lastAt(kind).first()
        return last <= 0L || System.currentTimeMillis() - last >= interval * 3_600_000L
    }

    /**
     * 定时检查入口：对所有开了自动备份且到期的 kind 依次备份。
     * App115 登录后每小时调一次（启动时也先调一次）。
     */
    suspend fun autoBackupIfDue() {
        val due = mutableListOf<BackupKind>()
        for (k in BackupKind.entries) {
            if (isDue(k)) due += k
        }
        for (k in due) {
            backup(k)
        }
    }

    /**
     * 执行一次备份：打包 → 上传 → 清理旧份（保留最近 [KEEP] 个）→ 记时间。
     * 只在成功时推进 lastAt（失败下次重试）。
     */
    suspend fun backup(kind: BackupKind): BackupResult {
        val dir = ensureBackupDir()
            ?: return BackupResult.Failed("无法定位云端备份目录（115 根目录下找/建 $BACKUP_DIR_NAME 失败）")
        val stamp = SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date())
        val fileName = "pad_${kind.key}_$stamp.zip"
        val tmp = File(context.cacheDir, "backup/$fileName")
        try {
            val packed = withContext(Dispatchers.IO) {
                runCatching { buildPayload(kind, tmp) }
                    .onFailure { Log.e(TAG, "备份打包异常（${kind.key}）", it) }
                    .getOrElse { false }
            }
            if (!packed || !tmp.exists() || tmp.length() == 0L) {
                Log.w(TAG, "备份未产出文件（${kind.key}）：packed=$packed 存在=${tmp.exists()} 大小=${tmp.length()}")
                return BackupResult.Failed("打包失败（见日志）")
            }
            val size = tmp.length()
            withContext(Dispatchers.IO) {
                if (size <= SMALL_FILE_BYTES) {
                    Uploader.uploadSmallLogged(
                        log = transferLog, api = api, fileName = fileName,
                        bytes = tmp.readBytes(), targetCid = dir, targetName = null,
                    )
                } else {
                    ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        Uploader.uploadLargeLogged(
                            log = transferLog, api = api, fileName = fileName,
                            pfd = pfd, size = size, targetCid = dir, targetName = null,
                        )
                    }
                }
            }
            pruneOldBackups(dir, kind)
            prefs.setLastAt(kind, System.currentTimeMillis())
            Log.i(TAG, "备份 ${kind.key} 完成：$fileName（${Format.size(size)}）")
            return BackupResult.Ok(fileName, size)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "备份 ${kind.key} 失败: ${e.message}")
            return BackupResult.Failed(e.message ?: e.toString())
        } finally {
            tmp.delete()
        }
    }

    /** 云端该 kind 的备份文件（新→旧） */
    suspend fun listBackups(kind: BackupKind): List<FileItem> {
        val dir = ensureBackupDir() ?: return emptyList()
        return runCatching { backupsIn(dir, kind) }.getOrDefault(emptyList())
    }

    /**
     * 从云端恢复 [kind] 的**最新一份**备份。
     *
     * - 配置 / 操作记录：覆盖 DataStore 文件，**需要重启进程生效**（DataStore 是进程级单例，
     *   不重启的话内存里的旧值随时会把刚恢复的文件覆盖回去）——调用方按 [RestoreResult.needsRestart] 提示。
     * - 媒体库：结构数据与记录按列名交集回插、图片缓存合并放回，立即生效（不需要重启）。
     */
    suspend fun restoreLatest(kind: BackupKind): RestoreResult {
        val latest = listBackups(kind).firstOrNull()
            ?: return RestoreResult.Failed("云端没有 ${kind.label} 的备份文件")
        return restore(kind, latest)
    }

    suspend fun restore(kind: BackupKind, file: FileItem): RestoreResult {
        val pc = file.pc?.takeIf { it.isNotBlank() }
            ?: return RestoreResult.Failed("该备份文件缺少提取码")
        val restoreDir = File(context.cacheDir, "backup_restore").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val zipFile = withContext(Dispatchers.IO) {
                val url = resolver.downloadUrl(pc)
                val req = Request.Builder().url(url).header("User-Agent", APP_USER_AGENT).build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("下载失败 HTTP ${resp.code}")
                    val body = resp.body ?: error("下载失败：响应为空")
                    val out = File(restoreDir, "backup.zip")
                    body.byteStream().use { input ->
                        FileOutputStream(out).use { input.copyTo(it) }
                    }
                    out
                }
            }
            if (zipFile.length() == 0L) return RestoreResult.Failed("下载的备份文件为空")

            val result = withContext(Dispatchers.IO) {
                when (kind) {
                    BackupKind.CONFIG, BackupKind.OPLOG ->
                        restoreDatastores(kind, zipFile)
                    BackupKind.MEDIA ->
                        restoreMedia(zipFile)
                }
            }
            return result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "恢复 ${kind.key} 失败: ${e.message}")
            return RestoreResult.Failed("恢复失败：${e.message ?: e.toString()}")
        } finally {
            restoreDir.deleteRecursively()
        }
    }

    // ---------------- 打包 ----------------

    private fun buildPayload(kind: BackupKind, out: File): Boolean {
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        ZipOutputStream(FileOutputStream(out).buffered()).use { zip ->
            when (kind) {
                BackupKind.CONFIG -> packDatastores(
                    zip,
                    // 会话/队列/传输记录不算"配置"：登录态换设备无意义，队列与传输是过程数据
                    listOf("app", "filter", "files", "download", "pinned", "player", "media", "quick_dirs"),
                )
                BackupKind.OPLOG -> packDatastores(zip, listOf("op_log"))
                BackupKind.MEDIA -> {
                    // 换机一次恢复全量到位：结构数据 + 记录 + 图片缓存合在一个 zip。
                    // 图片为空不算失败（新装机/刚清缓存也可能没图，结构数据仍然有价值）
                    exportTables(
                        zip,
                        tables = listOf(
                            "libraries", "movies", "episodes", "actors",
                            "movie_actors", "tags", "movie_tags", "scan_state",
                            "scan_log", "watch_history",
                        ),
                        entryName = "library.db",
                    )
                    if (!packImageCache(zip)) {
                        Log.w(TAG, "图片缓存为空（media_img 不存在或没有文件），本次备份只含结构数据与记录")
                    }
                }
            }
        }
        return out.exists() && out.length() > 0L
    }

    /** 把指定 DataStore 的 preferences_pb 打进 zip（DataStore 名白名单，防路径穿越） */
    private fun packDatastores(zip: ZipOutputStream, names: List<String>) {
        val dir = File(context.filesDir, "datastore")
        for (n in names) {
            val f = File(dir, "$n.preferences_pb")
            if (!f.exists()) continue
            zip.putNextEntry(ZipEntry("datastore/$n.preferences_pb"))
            FileInputStream(f).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /**
     * 打包媒体库图片缓存（media_img：海报/背景/剧照/头像/裁切兜底海报，全部按 pick_code 命名）。
     * 换机恢复后缓存键不变，海报墙/详情页直接命中，不用重新解析直链逐张重下。
     * 目录体积由 LRU 上限管着（设置里可调，默认几百 MB 级），这里流式写 zip，不整包进内存。
     * 目录不存在或没有文件返回 false（调用方按"打包失败"报给用户，日志里有原因）。
     */
    private fun packImageCache(zip: ZipOutputStream): Boolean {
        val dir = ImageUrlResolver.mediaCacheDir(mediaCacheRoot)
        if (!dir.isDirectory) return false
        var count = 0
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            zip.putNextEntry(ZipEntry("media_img/$rel"))
            FileInputStream(f).use { it.copyTo(zip) }
            zip.closeEntry()
            count++
        }
        if (count > 0) Log.i(TAG, "图片缓存打包完成：$count 个文件")
        return count > 0
    }

    // 把 media.db 的表子集导出成独立 db 文件：ATTACH 一个临时库，对每张表
    // CREATE TABLE bak.t AS SELECT * FROM main.t（CTAS，只做数据载体，不复制主键/索引；
    // 恢复时目标表由 Room 建好，按列名交集回插即可）。
    private fun exportTables(zip: ZipOutputStream, tables: List<String>, entryName: String) {
        val db = mediaDb.openHelper.writableDatabase
        val tmp = File(context.cacheDir, "backup/$entryName")
        tmp.parentFile?.mkdirs()
        if (tmp.exists()) tmp.delete()
        var detached = false
        try {
            db.execSQL("ATTACH DATABASE '${tmp.absolutePath.replace("'", "''")}' AS bak")
            for (t in tables) {
                db.execSQL("CREATE TABLE bak.`$t` AS SELECT * FROM main.`$t`")
            }
            // 先 DETACH 再读文件：DETACH 会把附属库落盘，否则 zip 里可能少最后一批页
            db.execSQL("DETACH DATABASE bak")
            detached = true
            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(tmp).use { it.copyTo(zip) }
            zip.closeEntry()
        } finally {
            // 正常路径上面已经 DETACH 过，再来一次会刷一条 "no such database: bak" 的
            // SQLiteLog 报错 —— 看着像失败其实无害，只在没成功 detach 时才兜底
            if (!detached) runCatching { db.execSQL("DETACH DATABASE bak") }
            tmp.delete()
        }
    }

    // ---------------- 恢复 ----------------

    /**
     * 恢复 DataStore 类（CONFIG/OPLOG）：解 zip，按白名单覆盖对应 DataStore 的
     * preferences_pb 文件。
     * 调用方要在拿到 needsRestart=true 后提示重启进程。
     *
     * ⚠️ 别在这类 KDoc 里写"目录 + 斜杠 + 星号 + 后缀"这种通配符 —— 斜杠紧跟星号会
     * 开启 Kotlin 的【嵌套块注释】，直接吞掉后面整个文件（本文件实测踩过）。
     */
    private fun restoreDatastores(kind: BackupKind, zipFile: File): RestoreResult {
        val allowed = when (kind) {
            BackupKind.CONFIG -> setOf(
                "app", "filter", "files", "download", "pinned", "player", "media", "quick_dirs",
            )
            else -> setOf("op_log")
        }
        val targetDir = File(context.filesDir, "datastore")
        var restored = 0
        ZipInputStream(FileInputStream(zipFile).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val m = Regex("^datastore/([a-z_]+)\\.preferences_pb$").find(entry.name)
                if (m != null && m.groupValues[1] in allowed) {
                    File(targetDir, "${m.groupValues[1]}.preferences_pb").outputStream().use {
                        zip.copyTo(it)
                    }
                    restored++
                }
                zip.closeEntry()
            }
        }
        return if (restored > 0) {
            RestoreResult.Ok("已恢复 ${kind.label}（覆盖 $restored 个配置文件），重启 App 后生效", true)
        } else {
            RestoreResult.Failed("备份文件里没有可恢复的内容")
        }
    }

    /**
     * 恢复媒体库（MEDIA）：一个 zip 里同时有结构数据（library.db）与图片缓存（media_img 下的文件）。
     * 一趟解压 —— db 落到 backup_restore，图片**合并**放回 media_img（不清现有；mtime 按 zip 还原，
     * media_img 的 LRU 淘汰看 mtime，不还原淘汰顺序就乱了）；然后逐表按列名交集清空回插。
     * ⚠️ 两处防线：entry 名按 canonicalPath 必须落在目标目录内（zip 从云端下来，防穿越）；
     * db 没解出来就报错返回（旧实现忘了解压这一步，ATTACH 的目标永远不存在，恢复必失败 —— 已修）。
     */
    private fun restoreMedia(zipFile: File): RestoreResult {
        val tables = listOf(
            "libraries", "movies", "episodes", "actors",
            "movie_actors", "tags", "movie_tags", "scan_state",
            "scan_log", "watch_history",
        )
        val dbFile = File(context.cacheDir, "backup_restore/library.db")
        val imgDir = ImageUrlResolver.mediaCacheDir(mediaCacheRoot)
        imgDir.mkdirs()
        val imgPrefix = imgDir.canonicalPath + File.separator
        var imgCount = 0
        var imgBytes = 0L
        ZipInputStream(FileInputStream(zipFile).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == "library.db" && !entry.isDirectory -> {
                        dbFile.outputStream().use { zip.copyTo(it) }
                    }
                    entry.name.startsWith("media_img/") && !entry.isDirectory -> {
                        val out = File(imgDir, entry.name.removePrefix("media_img/"))
                        if (out.canonicalPath.startsWith(imgPrefix)) {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                            if (entry.time > 0) out.setLastModified(entry.time)
                            imgCount++
                            imgBytes += out.length()
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (!dbFile.exists() || dbFile.length() == 0L) {
            return RestoreResult.Failed("备份文件里没有媒体库结构数据")
        }

        val db = mediaDb.openHelper.writableDatabase
        var rows = 0L
        try {
            db.execSQL("ATTACH DATABASE '${dbFile.absolutePath.replace("'", "''")}' AS bak")
            db.beginTransaction()
            try {
                for (t in tables) {
                    val mainCols = pragmaColumns(db, "main", t)
                    val bakCols = pragmaColumns(db, "bak", t)
                    val cols = bakCols.filter { it in mainCols }
                    if (cols.isEmpty()) continue
                    val colList = cols.joinToString(",") { "`$it`" }
                    db.execSQL("DELETE FROM main.`$t`")
                    db.execSQL("INSERT INTO main.`$t` ($colList) SELECT $colList FROM bak.`$t`")
                    rows += db.query("SELECT changes()").use { c ->
                        c.moveToFirst(); c.getLong(0)
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                // ★ 成功路径也必须 endTransaction。只标记成功不提交的话，事务一直挂在写连接上：
                // 界面已经报"已恢复"，数据却要等连接关闭时**整批回滚**掉；而且事务没结束前
                // DETACH 会报 "database bak is locked"，紧接着 restoreDir 被删，等于把一个
                // 还挂着的库文件从底下删了。这个 bug 让恢复"看着成功、实则没生效"。
                db.endTransaction()
            }
        } finally {
            runCatching { db.execSQL("DETACH DATABASE bak") }
                .onFailure { Log.w(TAG, "DETACH DATABASE bak 失败：${it.message}") }
        }
        val imgPart = if (imgCount > 0) "，合并 $imgCount 张图片（${Format.size(imgBytes)}）" else "（备份里没有图片缓存）"
        return RestoreResult.Ok("已恢复媒体库（$rows 行$imgPart），立即生效", false)
    }

    /** 某库某表的列名列表 */
    private fun pragmaColumns(db: androidx.sqlite.db.SupportSQLiteDatabase, alias: String, table: String): List<String> {
        val cols = mutableListOf<String>()
        db.query("PRAGMA $alias.table_info(`$table`)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) cols += c.getString(nameIdx)
        }
        return cols
    }

    // ---------------- 云端目录与文件管理 ----------------

    /** 找/建云端备份目录，返回 cid（记住它，之后直接用） */
    private suspend fun ensureBackupDir(): String? {
        val saved = prefs.dirCid.first()
        if (saved.isNotBlank()) return saved
        val root = runCatching { parseFilesResponse(api.files(cid = "0", limit = 1000)).items }.getOrNull()
            ?: return null
        var found = root.firstOrNull { it.isDir && it.fn == BACKUP_DIR_NAME }?.fid
        if (found == null) {
            val resp = api.addFolder("0", BACKUP_DIR_NAME)
            found = resp.envData()?.optStr("file_id")?.takeIf { it.isNotBlank() }
            if (found == null) {
                // folder/add 失败（典型为目录已存在）：列根目录找回来
                found = parseFilesResponse(api.files(cid = "0", limit = 1000)).items
                    .firstOrNull { it.isDir && it.fn == BACKUP_DIR_NAME }?.fid
            }
        }
        if (found != null) prefs.setDir(found, BACKUP_DIR_NAME)
        return found
    }

    private suspend fun backupsIn(dir: String, kind: BackupKind): List<FileItem> =
        parseFilesResponse(api.files(cid = dir, limit = 1000)).items
            .filter { !it.isDir && it.fn.startsWith("pad_${kind.key}_") && it.fn.endsWith(".zip") }
            .sortedByDescending { it.fn }

    /**
     * 保留最近 [KEEP] 份，更旧的删掉。MEDIA 还会清掉旧版拆分三类
     * （pad_library_/pad_medialog_/pad_img_）的遗留文件 —— 合并份已完全取代它们；
     * prune 只在对应备份**上传成功之后**跑，删遗留不会落到"新备份没传上去旧的全没了"。
     */
    private suspend fun pruneOldBackups(dir: String, kind: BackupKind) {
        runCatching {
            val stale = backupsIn(dir, kind).drop(KEEP).toMutableList()
            if (kind == BackupKind.MEDIA) {
                val all = parseFilesResponse(api.files(cid = dir, limit = 1000)).items
                    .filter { !it.isDir && it.fn.endsWith(".zip") }
                stale += all.filter {
                    it.fn.startsWith("pad_library_") || it.fn.startsWith("pad_medialog_") || it.fn.startsWith("pad_img_")
                }
            }
            if (stale.isNotEmpty()) {
                api.deleteFiles(stale.mapNotNull { it.fid }.joinToString(","), dir)
                Log.i(TAG, "清理旧备份 ${stale.size} 个（${kind.key}）")
            }
        }
    }

    companion object {
        private const val TAG = "BackupManager"

        /** 云端备份目录名（115 根目录下，首次备份自动创建） */
        const val BACKUP_DIR_NAME = "115Pad备份"

        /** 每类保留的最近备份份数 */
        const val KEEP = 3

        /** 小文件直传阈值（与 FolderUploader 一致），超了走分片上传 */
        private const val SMALL_FILE_BYTES = 32L * 1024 * 1024

        private const val STAMP_PATTERN = "yyyyMMdd-HHmmss"
    }
}
