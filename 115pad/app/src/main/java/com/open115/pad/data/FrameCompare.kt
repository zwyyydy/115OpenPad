package com.open115.pad.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs

/**
 * 帧画面比对（感知哈希 dHash）——「同片多版本」的 C 段判据。
 *
 * 前两段只比**结构**（宽高比 + 时长毫秒级一致），能筛掉"时长凑巧相同的别的片子"，
 * 但剩下的是"结构一致 ≠ 画面相同"：同一部剧的两集、同一片源的不同剪辑都过得了 B 段。
 * 这里把抽出来的帧比一遍，才是真的"这两份是同一画面"。
 *
 * 判据与阈值来自 2026-09-30 的实测（见 memory: video-version-detection-experiments）：
 * 同一内容降质后 dHash 距离 **4~11**、不同内容 **24~39** → 取 14 当中线。
 *
 * 纯函数（灰度矩阵 → 哈希 → 距离 → 结论）与 Bitmap 解码分开放：前者能在 JVM 单测里跑。
 */

/** 判"同一帧"的 dHash 汉明距离上限 */
const val DHASH_SAME_THRESHOLD = 14

/** dHash 的输入尺寸：9x8 灰度（横向 8 个相邻比较 × 8 行 = 64 bit） */
private const val HASH_W = 9
private const val HASH_H = 8

/**
 * 9x8 灰度 → 64 位 dHash：每行比较相邻像素，左边更亮记 1。
 * 用 Long 存（高位补 0），距离用 [hamming]。
 */
internal fun dHashOf(gray: IntArray, w: Int = HASH_W, h: Int = HASH_H): Long {
    var hash = 0L
    var bit = 0
    for (y in 0 until h) {
        for (x in 0 until w - 1) {
            val left = gray.getOrElse(y * w + x) { 0 }
            val right = gray.getOrElse(y * w + x + 1) { 0 }
            if (left > right) hash = hash or (1L shl bit)
            bit++
        }
    }
    return hash
}

/** 两个哈希的汉明距离（不同 bit 的个数） */
internal fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

/**
 * 一组帧比一组帧：**逐位对齐比较**（两边必须来自同一组取样比例），
 * 距离 ≤ [threshold] 的算"同一画面"，多数（≥ ceil(n/2)）一致即判"画面相同"。
 * 任一边帧数不足（抓帧失败）返回 null —— 结论**不能**由"没比成"推出来。
 */
internal fun compareFrameHashes(
    a: List<Long>,
    b: List<Long>,
    threshold: Int = DHASH_SAME_THRESHOLD,
): FrameComparison? {
    val n = minOf(a.size, b.size)
    if (n == 0) return null
    val dists = (0 until n).map { hamming(a[it], b[it]) }
    val same = dists.count { it <= threshold }
    return FrameComparison(
        distances = dists,
        sameFrames = same,
        totalFrames = n,
        same = same * 2 >= n,
    )
}

data class FrameComparison(
    /** 每个取样点的汉明距离 */
    val distances: List<Int>,
    val sameFrames: Int,
    val totalFrames: Int,
    /** 多数帧一致 → 判"画面相同" */
    val same: Boolean,
)

/** Bitmap → 9x8 灰度（先缩到 9x8 再读像素；`filter=true` 让缩放变成均值，抗噪） */
internal fun grayOf(bmp: Bitmap): IntArray {
    val small = if (bmp.width == HASH_W && bmp.height == HASH_H) {
        bmp
    } else {
        Bitmap.createScaledBitmap(bmp, HASH_W, HASH_H, true)
    }
    val out = IntArray(HASH_W * HASH_H)
    for (y in 0 until HASH_H) {
        for (x in 0 until HASH_W) {
            val p = small.getPixel(x, y)
            // 亮度用整数近似（0.299R + 0.587G + 0.114B），避免浮点
            out[y * HASH_W + x] = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
        }
    }
    if (small !== bmp) small.recycle()
    return out
}

/** JPEG 字节 → dHash；解不出来返回 null */
internal fun dHashOfJpeg(bytes: ByteArray): Long? = runCatching {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    try {
        dHashOf(grayOf(bmp))
    } finally {
        bmp.recycle()
    }
}.getOrNull()

/**
 * 对同一组候选视频逐个抓帧并算哈希（「同片多版本」点「画面比对」时调用）。
 *
 * 取样点固定用 [COMPARE_ATS]，**所有候选必须用同一组**，否则第 i 帧不是同一时刻、比不了。
 * 抓不到帧（未转码/网络失败）返回空表，由调用方按"信息不足"处理。
 */
suspend fun frameHashesOf(grabber: VideoFrameGrabber, pickCode: String): List<Long> =
    grabber.grabMany(pickCode, COMPARE_ATS).mapNotNull { bytes -> bytes?.let { dHashOfJpeg(it) } }

/** 距离的粗略说法（结果卡片上给用户看的） */
internal fun distanceHint(d: Int): String = when {
    d <= DHASH_SAME_THRESHOLD -> "同一画面（$d）"
    d <= 22 -> "有差异（$d）"
    else -> "明显不同（$d）"
}
