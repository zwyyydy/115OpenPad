package com.open115.pad.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 文件夹上传汇总（文件级成败 + 建不出来的目录数） */
data class FolderUploadSummary(
    /** 发现的文件总数 */
    val files: Int,
    val succeeded: Int,
    val failed: Int,
    /** 秒传数（含在 succeeded 里） */
    val reused: Int,
    /** 远端建目录失败的目录数（其下文件全部计入 failed） */
    val dirsFailed: Int,
)

/**
 * SAF 文件夹上传：递归遍历所选目录树，远端按结构建目录，逐文件走现有
 * *Logged 上传链路——并发排队/断点续传/暂停/取消全部复用，每个文件是
 * 一条独立的传输记录，传输中心可单独暂停/取消/重试。
 *
 * 目录幂等：folder/add 失败（典型为同名目录已存在）时列出父目录找回
 * 同名文件夹的 cid；进程内再用 dirCache 避免重复请求。目录建不出来
 * （网络错误等）则跳过整个子树并计入失败。
 */
suspend fun uploadFolder(
    context: Context,
    log: TransferLog,
    api: OpenApi,
    treeUri: Uri,
    targetCid: String,
    targetPath: String,
): FolderUploadSummary {
    val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: error("无法访问所选文件夹（权限可能已失效）")
    val rootName = root.name?.takeIf { it.isNotBlank() } ?: "文件夹"

    // 进程内目录缓存：cid 映射避免对同一目录重复 folder/add / 重复列目录
    val dirCache = HashMap<String, String>()
    var filesTotal = 0
    var succeeded = 0
    var failed = 0
    var reused = 0
    var dirsFailed = 0

    /** 列父目录找同名文件夹的 cid（folder/add 对已存在目录报错时的兜底） */
    suspend fun findExistingDir(parentCid: String, name: String): String? = try {
        val resp = api.files(cid = parentCid, limit = 1000)
        parseFilesResponse(resp).items
            .firstOrNull { it.isDir && it.fn == name }?.fid
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    /** 确保远端 parentCid/name 目录存在并返回 cid；建不出返回 null */
    suspend fun ensureDir(parentCid: String, name: String, cacheKey: String): String? {
        dirCache[cacheKey]?.let { return it }
        currentCoroutineContext().ensureActive()
        return try {
            val resp = api.addFolder(parentCid, name)
            val cid = if (resp.envOk()) {
                resp.envData()?.optStr("file_id")
            } else null
            if (cid != null) {
                dirCache[cacheKey] = cid
                cid
            } else {
                findExistingDir(parentCid, name)?.also { dirCache[cacheKey] = it }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    fun countFiles(dir: DocumentFile): Int =
        dir.listFiles().sumOf { if (it.isDirectory) countFiles(it) else 1 }

    suspend fun uploadOne(file: DocumentFile, name: String, remoteCid: String, displayPath: String) {
        val size = file.length()
        if (size <= 32L * 1024 * 1024) {
            // 小文件：整文件读内存直传（与单文件上传同规则）
            val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?: error("无法读取文件")
            val r = Uploader.uploadSmallLogged(
                log = log, api = api, fileName = name, bytes = bytes,
                targetCid = remoteCid, targetName = displayPath,
            )
            if (r.reused) reused++
        } else {
            // 大文件：流式分片上传；uri 落库供重启续传（tree 权限持久化，子文档可读）
            context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                val r = Uploader.uploadLargeLogged(
                    log = log, api = api, fileName = name, pfd = pfd, size = size,
                    targetCid = remoteCid, targetName = displayPath, uri = file.uri.toString(),
                )
                if (r.reused) reused++
            } ?: error("无法打开文件")
        }
    }

    suspend fun walk(dir: DocumentFile, remoteCid: String, displayPath: String) {
        val children = dir.listFiles().sortedBy { it.name ?: "" }
        for (child in children) {
            currentCoroutineContext().ensureActive()
            val name = child.name?.takeIf { it.isNotBlank() } ?: continue
            if (child.isDirectory) {
                val cid = ensureDir(remoteCid, name, "$remoteCid/$name")
                if (cid == null) {
                    // 目录建不出：整个子树计为失败（本地数一下文件量，汇总才诚实）
                    val n = countFiles(child)
                    dirsFailed++
                    filesTotal += n
                    failed += n
                    continue
                }
                walk(child, cid, "$displayPath/$name")
            } else {
                filesTotal++
                try {
                    uploadOne(child, name, remoteCid, displayPath)
                    succeeded++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            }
        }
    }

    // 根目录：在当前目录下建同名文件夹，再递归其内容
    val rootCid = ensureDir(targetCid, rootName, "$targetCid/$rootName")
    if (rootCid == null) {
        val n = countFiles(root)
        dirsFailed++
        filesTotal += n
        failed += n
        return FolderUploadSummary(filesTotal, succeeded, failed, reused, dirsFailed)
    }
    walk(root, rootCid, "$targetPath/$rootName")
    return FolderUploadSummary(filesTotal, succeeded, failed, reused, dirsFailed)
}
