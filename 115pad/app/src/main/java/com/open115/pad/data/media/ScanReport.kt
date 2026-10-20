package com.open115.pad.data.media

import com.open115.pad.util.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次媒体库扫描的结果 —— 「扫描记录」里那一条的全部内容（**文案只在这里拼**）。
 *
 * 用户要看的是"这次扫描干了什么、新增了多少部"，所以：
 *  - [summary] 一行摘要（列表里每条记录的第二行）：「新增 2 部 · 目录 84/84（跳过 83） · 用时 01:27」
 *  - 新增的**影片本身**不进文案 —— 存的是 [`ScanLogEntity.newKeys`]，界面拿它去 join `movies`
 *    现取标题与海报，用图片展示（库里改了名换了海报，记录里也跟着变）
 *
 * 三种结束方式必须区分开，所以有 [stopped] 与 [aborted]：`新增 0 部` ≠ `未完成（列目录中断）` ——
 * 后者是网络问题，用户看到才知道该再扫一次。
 */
data class ScanReport(
    /** 媒体库名（没有库时是根目录最后一段） */
    val libraryName: String,
    val startedAt: Long,
    val finishedAt: Long,
    /** 本轮要处理的目录总数；列目录阶段就中断时为 0 */
    val totalDirs: Int,
    /** 真正跑完的目录数（含被跳过的） */
    val doneDirs: Int,
    /** 其中因"目录没变化"被跳过的 */
    val skippedDirs: Int,
    /** 入库/更新的条目数（分集也算，所以一般大于新增影片数） */
    val indexed: Int,
    /** 顺手落盘的海报数 */
    val postersFetched: Int,
    /** 本次**新增**的影片数（顶层条目：影片 / 系列卡；分集不单列） */
    val newCount: Int,
    /** 是被用户/系统停掉的（没跑完全部目录） */
    val stopped: Boolean,
    /** 扫描方式（"全量"/"增量"/"快速"）。空串 = 老记录，摘要里不显示 */
    val mode: String = "",
    /** 本轮**列目录请求次数** —— 快速扫描省了多少就看它（增量模式约等于目录数），0 = 没统计 */
    val listRequests: Int = 0,
) {
    val elapsedMs: Long get() = (finishedAt - startedAt).coerceAtLeast(0L)

    /** 一个目录都没跑完（列目录阶段就中断/被停）—— 这种结果与"扫完了没新增"必须区分开 */
    val aborted: Boolean get() = doneDirs <= 0

    /** 摘要一行。顺序按用户最关心的：方式 → 新增 → 目录 → 索引 → 海报 → 用时 */
    fun summary(): String = buildString {
        if (aborted) {
            append(if (stopped) "已停止（未开始索引）" else "未完成（列目录中断）")
        } else {
            if (stopped) append("已停止 · ")
            if (mode.isNotBlank()) append(mode).append(" · ")
            append("新增 $newCount 部")
            append(" · 目录 $doneDirs/$totalDirs")
            if (skippedDirs > 0) append("（跳过 $skippedDirs）")
            append(" · 索引 $indexed 项")
            if (postersFetched > 0) append(" · 缓存海报 $postersFetched 张")
        }
        if (listRequests > 0) append(" · 列目录 $listRequests 次")
        append(" · 用时 ").append(Format.duration(elapsedMs / 1000).ifEmpty { "0:00" })
    }

    /** 记录详情里那行"完成时间" */
    fun finishedText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(finishedAt))
}

/**
 * 「流水线同步」记录**跨轮合并**的时间窗：同一库的连续两轮之间不超过这么久，就并成一条。
 *
 * 为什么需要：一轮流水线同步的形状是**一份信号一个任务**（手动同步一份 = 一条记录），
 * 而服务器一次刮削往往连着写几份信号，自动轮询还会把手动同步过的那份再顺序消费一次 ——
 * 不合并的话「扫描记录」里就是一串「目录 1/1 · 索引 1 项 · 无新增」的同款记录，
 * 用户看不出这是同一批（用户报的"为什么会有一堆新增 0 的"）。
 *
 * 取 30 分钟：自动轮询默认 10 分钟一轮，够把同一批信号收拢；服务器真正的新一轮
 * （几小时一次）远在这个窗口之外，会自己重新开一条。
 */
internal const val PIPELINE_LOG_MERGE_WINDOW_MS = 30 * 60_000L

/**
 * 把新的一轮流水线同步并进上一条记录（同库 + 同模式 + 时间挨得够近），返回合并后的那一行；
 * 不该合并就返回 null，调用方另起一条。
 *
 * 只有**流水线同步**用它：那是"服务器刮削搬进来的"，同一批分几轮跑完，用户要看的是
 * "这一批同步动了什么"，不是每轮一条。用户自己点的扫描不并 —— 那是他一次明确的操作，一轮一条。
 *
 * [ScanLogEntity.id] 沿用上一条：界面上那是同一个 LazyColumn key，合并时列表不会闪。
 * 时间取**新一轮那刻**（列表按它倒序，"最近一次"才说得通）；用时与各项计数逐轮相加、
 * 新增键取并集 —— 合并后这一条就是"这一批同步的总账"。
 */
internal fun mergePipelineLog(prev: ScanLogEntity, next: ScanLogEntity, windowMs: Long): ScanLogEntity? {
    if (prev.libraryId != next.libraryId) return null
    // 模式必须一样：mode 就是"流水线同步"这个身份本身，混进别的扫描就说不清这条是什么了
    if (prev.mode != next.mode) return null
    // 时间倒着来（时钟回拨/记录顺序异常）就不并 —— 宁多一条，也不把两批揉成一条
    if (next.at < prev.at || next.at - prev.at > windowMs) return null
    val keys = LinkedHashSet(prev.newKeyList).apply { addAll(next.newKeyList) }
    return next.copy(
        id = prev.id,
        elapsedMs = prev.elapsedMs + next.elapsedMs,
        totalDirs = prev.totalDirs + next.totalDirs,
        doneDirs = prev.doneDirs + next.doneDirs,
        skippedDirs = prev.skippedDirs + next.skippedDirs,
        indexed = prev.indexed + next.indexed,
        postersFetched = prev.postersFetched + next.postersFetched,
        stopped = prev.stopped || next.stopped,
        listRequests = prev.listRequests + next.listRequests,
        newKeys = keys.joinToString("\n"),
    )
}
