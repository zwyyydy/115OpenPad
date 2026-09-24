package com.open115.pad.data.media

/**
 * 作品列表的排序方式（作品页 / 以后别处也能用）。
 *
 * [sql] 是给 SQL `ORDER BY` 用的序号 —— DAO 里用带 `CASE` 的一条查询按它分派，
 * 而不是每种排序写一条查询（六条几乎一样的 SQL 迟早改漏一条）。
 * 序号**不能随便改**：它存在 DataStore 里（见 MediaPrefs.worksSort）。
 */
enum class WorksSort(val label: String, val sql: Int) {
    /** 评分高的在前；没评分的沉底 */
    Rating("评分", 0),

    /** 首映时间新的在前（`premiered` 是 `yyyy-MM-dd`，字符串序就是时间序） */
    Premiered("首映时间", 1),

    /** 入库时间新的在前（nfo 的 `<dateadded>`；没有就用本机索引时间兜底） */
    DateAdded("入库时间", 2),

    /** 标题（按字典序，中文按 Unicode 码位 —— 不是拼音序，但稳定） */
    Title("标题", 3),

    /** 随机。每次查询都重排（SQLite 的 RANDOM() 逐行求值） */
    Random("随机", 4);

    companion object {
        val DEFAULT = Rating

        /** 从 DataStore 里存的字符串还原；认不出来（老值/手改）就用默认 */
        fun ofName(name: String?): WorksSort = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 解析 nfo 里的日期（`<premiered>` / `<dateadded>`）成 **epoch 毫秒**。
 *
 * 实测刮削器写法不统一：
 *  - `2024-05-31 11:28:16`（TMM / Emby）
 *  - `2024-05-31T11:28:16Z`（ISO，Jellyfin 常带 T 和 Z）
 *  - `2024-05-31`（只有日期）
 *
 * ★ 存成毫秒而不是原字符串：排序要跟 `scannedAt`（毫秒）兜底混用，
 *   一个是字符串一个是数字没法比大小（字符串序里 `2024-5-9` 会排在 `2024-10-1` 后面，也是错的）。
 * 认不出来返回 null（不猜，交给调用方兜底）。
 */
fun parseNfoDateMillis(raw: String?): Long? {
    val s = raw?.trim()?.replace('T', ' ')?.removeSuffix("Z")?.trim() ?: return null
    if (s.isEmpty()) return null
    val normalized = when {
        // 只有日期：补零点
        Regex("""^\d{4}-\d{2}-\d{2}$""").matches(s) -> "$s 00:00:00"
        // 有日期没秒：补秒（SimpleDateFormat 对缺字段是宽容的，这里只是统一形状）
        Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$""").matches(s) -> "$s:00"
        else -> s.take(19)
    }
    return runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .apply { isLenient = true }
            .parse(normalized)
            ?.time
    }.getOrNull()
}
