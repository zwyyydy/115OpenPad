package com.open115.pad.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 递归（或只看一层）走一个目录子树，把每个目录的条目交给 [onDir]。
 *
 * 抽出来是因为已经有第二处要用了：重复文件查找（[DuplicateFinder]）和"同片多版本"查找
 * 干的是同一件事 —— **把目录一层层列一遍**，只有后面的分组逻辑不同。
 * 限速由调用方通过 [beforeDir] 挂（各自有各自的档位规则）。
 *
 * 停止靠调用方在 [onDir] 里抛异常（两只 finder 都用同一个 StopSignal 套路），
 * 这里只负责遍历与协程取消检查。
 */
internal suspend fun walkDirs(
    api: OpenApi,
    rootCid: String,
    rootPath: String,
    recursive: Boolean,
    beforeDir: suspend () -> Unit = {},
    onDir: suspend (cid: String, path: String, items: List<FileItem>) -> Unit,
) {
    val queue = ArrayDeque<Pair<String, String>>()
    queue.addLast(rootCid to rootPath)
    var first = true
    while (queue.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (cid, path) = queue.removeFirst()
        // 第一层之外才限速：进目录的第一枪没必要等（与扫描器"第一个目录不等"同一口径）
        if (!first) beforeDir()
        first = false
        val page = listDirPaged(api, cid) { beforeDir() }
        onDir(cid, path, page.items)
        if (recursive) {
            for (item in page.items) {
                if (item.isDir) item.fid?.let { queue.addLast(it to "$path/${item.fn}") }
            }
        }
    }
}
