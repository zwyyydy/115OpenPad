package com.open115.pad.ui.media

import android.content.Context
import com.open115.pad.data.PlaylistEntry
import com.open115.pad.data.media.MediaDao
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.episodeSortKey
import com.open115.pad.player.PlayerActivity
import androidx.compose.material3.SnackbarHostState

/**
 * 「随机播放」按钮的共用逻辑（海报墙 / 演员·标签作品页两处）：
 * 从**当前可见**的卡片（已套搜索与筛选）里随机挑一部直接开播。
 *
 *  - 普通卡（自己带视频）→ 直接播；队列 = 当前列表里所有能播的卡（与详情页的
 *    库内连播同款：播完自动接下一部）。
 *  - 系列卡（顶层没有视频）→ 随机挑它名下**一集**；队列 = 该系列的全部分集
 *    （按集号数值排，与详情页选集同一份排序）。
 *
 * 抽成函数而不是两处各写一遍：播放参数、队列语义、排序规则三样必须完全一致，
 * 分开写迟早漂移。返回值不需要 —— 播不了就 Snackbar 说明。
 */
internal suspend fun playRandomMovie(
    context: Context,
    dao: MediaDao,
    cards: List<MovieCard>,
    snackbar: SnackbarHostState,
) {
    if (cards.isEmpty()) {
        snackbar.showSnackbar("没有可播放的作品")
        return
    }
    val picked = cards.random()
    if (picked.videoPickCode != null) {
        val pc = picked.videoPickCode ?: return
        val entries = cards.mapNotNull { c -> c.videoPickCode?.let { PlaylistEntry(it, c.title) } }
        val index = entries.indexOfFirst { it.pc == pc }.coerceAtLeast(0)
        context.startActivity(
            PlayerActivity.intent(
                context, pc, picked.title, entries, index,
                // 媒体库的播放要进「观影历史」（与详情页/续播同款口径）
                recordHistory = true,
            ),
        )
        return
    }
    // 系列卡：按集号排（按标题字符串排会让 S01E2 排到 S01E10 后面，与详情页同坑）
    val episodes = dao.episodesOfSeries(picked.mediaKey)
        .filter { !it.videoPickCode.isNullOrEmpty() }
        .sortedWith(
            compareBy(
                { episodeSortKey(it.videoName ?: it.title) },
                { it.videoName ?: it.title },
            ),
        )
    if (episodes.isEmpty()) {
        snackbar.showSnackbar("「${picked.title}」没有可播放的视频")
        return
    }
    val ep = episodes.random()
    val pc = ep.videoPickCode ?: return
    val entries = episodes.map { PlaylistEntry(it.videoPickCode!!, it.title) }
    val index = entries.indexOfFirst { it.pc == pc }.coerceAtLeast(0)
    context.startActivity(
        PlayerActivity.intent(
            context, pc, ep.title, entries, index,
            recordHistory = true,
        ),
    )
}
