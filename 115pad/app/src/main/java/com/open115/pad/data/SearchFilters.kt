package com.open115.pad.data

import java.time.LocalDate

/**
 * 文件搜索的时间范围。
 *
 * 开放平台 `open/ufile/search` 只接受 `gte_day`/`lte_day`（YYYY-MM-DD），没有"最近 N 天"
 * 这种相对写法 —— 所以界面给固定档位，请求前在这里换算成日期。
 *
 * **实测（2026-10-23 真接口）：只传 `gte_day` 会被服务端静默忽略，必须和 `lte_day`
 * 成对传才生效**。比如 `gte_day=2026-10-16` 仍返回全部 21 条；加上
 * `lte_day=2026-10-23` 后只返回 1 条。所以这里只暴露 [range]，不单独提供起点。
 */
object SearchFilters {

    /** (天数, 文案)。null（不限）由界面单独表示，不进这个表 */
    val DAY_PRESETS: List<Pair<Int, String>> = listOf(
        7 to "7 天",
        30 to "30 天",
        365 to "1 年",
    )

    /** 最近 [days] 天：返回 (gte_day, lte_day)，两端都含，ISO `yyyy-MM-dd`。 */
    fun range(days: Int, today: LocalDate = LocalDate.now()): Pair<String, String> =
        today.minusDays(days.toLong()).toString() to today.toString()

    /** 单测/调试用：只要起点时也走同一个换算 */
    fun gteDay(days: Int, today: LocalDate = LocalDate.now()): String =
        range(days, today).first
}
