package com.open115.pad.data

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 列目录每页要多少项。**115 一页真给得下的上限就是 1150**：2026-10-15 实测一个 1019 项的目录，
 * `limit=1150` 一次全给回，`limit=1200`/`2000` 都被**静默裁到 1150** —— 与开放平台文档"最大1150"一致。
 * 早先按 200 取，同一个目录要 6 次请求；大目录（剧照、多语言字幕）正是扫描最容易卡住的地方。
 *
 * 三个翻页实现（这里 / `MediaScanner.listFiles` / `FileOrganizer.listAll`）共用这一个上限。
 */
internal const val LIST_PAGE_SIZE = 1150

/** 一个目录最多翻多少页（病态目录宁可少列，也不能把频控额度耗光）。8×1150 ≈ 9200 项封顶 */
private const val LIST_MAX_PAGES = 8

/**
 * 分页取全**一个目录**的条目。
 *
 * 为什么必须有：115 一页最多 [LIST_PAGE_SIZE] 项，只取第一页会让大目录（一季带多语言字幕、
 * 或者几百个文件的目录）**静默丢内容**，界面上完全看不出异常。
 *
 * 工程里已经有两段同款翻页（`MediaScanner.listFiles`、`FileOrganizer.listAll`），这是第三处 ——
 * 所以抽出来：**新代码一律用它**，那两处等下次动到时再换过来（它们的调用点都在调过的扫描/整理
 * 主路径上，单独为"少一段重复代码"去动它们不划算）。
 *
 * [order] / [asc] 透传给接口：**目录选择器与重命名面板要按文件名排**（用户是照着列表顺序挑目录、
 * 改名的），传 null 就是接口默认排序。⚠ 这两个参数必须排在 [beforePage] 前面 —— 尾随 lambda 绑的是它。
 *
 * [beforePage] 在每次请求前调用，调用方拿它挂分级限速（扫描/整理/查重各有自己的档位）。
 */
internal suspend fun listDirPaged(
    api: OpenApi,
    cid: String,
    order: String? = null,
    asc: Int? = null,
    beforePage: suspend () -> Unit = {},
): FilesPage {
    val first = parseFilesResponse(
        run {
            beforePage()
            api.files(cid = cid, limit = LIST_PAGE_SIZE, order = order, asc = asc)
        },
    )
    if (first.count <= first.items.size) return first

    val all = first.items.toMutableList()
    var pages = 1
    while (all.size < first.count && pages < LIST_MAX_PAGES) {
        currentCoroutineContext().ensureActive()
        val page = parseFilesResponse(
            run {
                beforePage()
                api.files(cid = cid, limit = LIST_PAGE_SIZE, offset = all.size, order = order, asc = asc)
            },
        )
        if (page.items.isEmpty()) break
        all += page.items
        pages++
    }
    if (all.size < first.count) {
        Log.w("ListPaging", "目录 $cid 只取到 ${all.size}/${first.count} 项（分页上限 $LIST_MAX_PAGES 页）")
    }
    return first.copy(items = all)
}
