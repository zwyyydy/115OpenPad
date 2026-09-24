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
) {
    val elapsedMs: Long get() = (finishedAt - startedAt).coerceAtLeast(0L)

    /** 一个目录都没跑完（列目录阶段就中断/被停）—— 这种结果与"扫完了没新增"必须区分开 */
    val aborted: Boolean get() = doneDirs <= 0

    /** 摘要一行。顺序按用户最关心的：新增 → 目录 → 索引 → 海报 → 用时 */
    fun summary(): String = buildString {
        if (aborted) {
            append(if (stopped) "已停止（未开始索引）" else "未完成（列目录中断）")
        } else {
            if (stopped) append("已停止 · ")
            append("新增 $newCount 部")
            append(" · 目录 $doneDirs/$totalDirs")
            if (skippedDirs > 0) append("（跳过 $skippedDirs）")
            append(" · 索引 $indexed 项")
            if (postersFetched > 0) append(" · 缓存海报 $postersFetched 张")
        }
        append(" · 用时 ").append(Format.duration(elapsedMs / 1000).ifEmpty { "0:00" })
    }

    /** 记录详情里那行"完成时间" */
    fun finishedText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(finishedAt))
}
