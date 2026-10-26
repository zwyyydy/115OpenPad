package com.open115.tv.ui

import com.open115.tv.receiver.PlayRequest
import com.open115.tv.receiver.QueueItem
import com.open115.tv.receiver.QueueRequest

/**
 * 连播队列的纯逻辑（单测覆盖）：投屏开始时只有当前这一条，后续集由平板异步补推。
 *
 * 队列在电视本地是"只增不减"的：已经播过的条目留着，用户才好在选集面板里往回点，
 * 当前播放位置用下标表示（见 PlayScreen 的 queueIndex）。
 */

/** 起播种子：当前这一条就是队列的第一条（外挂字幕也只挂在这一条上） */
internal fun seedQueue(request: PlayRequest): List<QueueItem> = listOf(
    QueueItem(
        url = request.url,
        title = request.title,
        mime = request.mime,
        pickCode = request.pickCode,
        subs = request.subs,
    ),
)

/** 队列条目的身份：优先 pick_code（换直链后不变），没有就用地址 */
internal fun queueItemKey(item: QueueItem): String =
    item.pickCode?.takeIf { it.isNotBlank() } ?: item.url

/**
 * 把平板补推的队列并进本地队列：只追加没见过的新条目，不打断正在播的这一条。
 *
 * 必须认锚点：迟到的 `/queue`（上一场投屏、或平板换了目录重投）里如果没有本地队列中的任何一条，
 * 直接丢掉——否则会把不相干的片接在当前这一集后面。
 */
internal fun mergeQueue(current: List<QueueItem>, update: QueueRequest): List<QueueItem> {
    val incoming = update.queue.filter { it.url.isNotBlank() }
    if (incoming.isEmpty()) return current
    val known = current.mapTo(HashSet()) { queueItemKey(it) }
    val anchor = update.anchor?.takeIf { it.isNotBlank() } ?: incoming.first().pickCode
    if (anchor.isNullOrBlank() || anchor !in known) return current
    val seen = known
    val additions = incoming.filter { seen.add(queueItemKey(it)) }
    return if (additions.isEmpty()) current else current + additions
}
