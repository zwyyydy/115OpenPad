package com.open115.tv.ui

import com.open115.tv.receiver.PlayRequest
import com.open115.tv.receiver.QueueItem
import com.open115.tv.receiver.QueueRequest
import com.open115.tv.receiver.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 连播队列的合并规则：补推是异步、分段的，还可能迟到，规则错一条就会出现
 * "下一集播了别的片"或"队列被上一场投屏污染"。
 */
class PlayQueueTest {

    private fun item(pc: String, url: String = "http://x/$pc.mp4") =
        QueueItem(url = url, title = pc, pickCode = pc)

    @Test
    fun seed_startsWithCurrentItem() {
        val seed = seedQueue(
            PlayRequest(
                url = "http://x/a.mp4",
                title = "A",
                mime = "video/mp4",
                pickCode = "a",
            ),
        )
        assertEquals(1, seed.size)
        assertEquals("a", seed[0].pickCode)
        assertEquals("http://x/a.mp4", seed[0].url)
        assertEquals("video/mp4", seed[0].mime)
    }

    @Test
    fun seed_carriesSubtitlesOntoFirstItem() {
        // 外挂字幕只随 /play 的播种条目走：合并队列是"只增不改"，这条字幕不会被补推抹掉
        val seed = seedQueue(
            PlayRequest(
                url = "http://x/a.mkv",
                subs = listOf(SubtitleTrack(url = "http://x/a.chs.srt", mime = "application/x-subrip", name = "简体中文")),
            ),
        )
        assertEquals(1, seed[0].subs.size)
        assertEquals("http://x/a.chs.srt", seed[0].subs[0].url)
        assertEquals("简体中文", seed[0].subs[0].name)
    }

    @Test
    fun merge_appendsNewItemsInOrder() {
        val merged = mergeQueue(
            current = listOf(item("a")),
            update = QueueRequest(queue = listOf(item("a"), item("b"), item("c")), anchor = "a"),
        )
        assertEquals(listOf("a", "b", "c"), merged.map { it.pickCode })
    }

    @Test
    fun merge_isIdempotentForRepeatedPushes() {
        // 补推是"整段下发"的，同一批会重复出现；重复合并不能长出重复条目
        val first = mergeQueue(
            listOf(item("a")),
            QueueRequest(queue = listOf(item("a"), item("b")), anchor = "a"),
        )
        val second = mergeQueue(
            first,
            QueueRequest(queue = listOf(item("a"), item("b"), item("b"), item("c")), anchor = "a"),
        )
        assertEquals(listOf("a", "b", "c"), second.map { it.pickCode })
    }

    @Test
    fun merge_rejectsLateQueueFromAnotherCast() {
        val current = listOf(item("b"))
        val merged = mergeQueue(
            current,
            QueueRequest(queue = listOf(item("a"), item("c")), anchor = "a"),
        )
        assertEquals(current, merged)
    }

    @Test
    fun merge_usesFirstItemAsAnchorWhenMissing() {
        val merged = mergeQueue(
            current = listOf(item("a")),
            update = QueueRequest(queue = listOf(item("a"), item("b"))),
        )
        assertEquals(listOf("a", "b"), merged.map { it.pickCode })
    }

    @Test
    fun merge_skipsBlankUrls() {
        val merged = mergeQueue(
            current = listOf(item("a")),
            update = QueueRequest(
                queue = listOf(item("a"), QueueItem(url = "", pickCode = "b"), item("c")),
                anchor = "a",
            ),
        )
        assertEquals(listOf("a", "c"), merged.map { it.pickCode })
    }

    @Test
    fun key_fallsBackToUrlWithoutPickCode() {
        assertEquals("http://x/1.mp4", queueItemKey(QueueItem(url = "http://x/1.mp4")))
        assertEquals("a", queueItemKey(item("a", url = "http://x/other.mp4")))
    }
}
