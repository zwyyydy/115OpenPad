package com.open115.pad.data

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 全局限速的节拍器（设置 → 后台任务 → 「请求限速」）。
 *
 * 语义是"**相邻两次请求**至少隔 [intervalMs]"：只在"距上次请求还不够久"时才等，
 * 所以正常的人手点击（间隔通常远大于几百毫秒）完全感觉不到，被拉平的只有连续快速点击
 * 与一次动作触发的一串请求。
 *
 * 为什么用"预约下一个槽位"而不是"睡完再看时钟"：并发请求会同时读到旧的时刻、
 * 一起算出 wait=0 然后一起发出去（等于没限速）。这里在**同一把锁里**把 `nextAt` 往后推，
 * 于是 N 个并发请求排成 0、interval、2×interval… 真正的串行节拍。
 *
 * 时钟做成可注入的：单测直接喂假时钟打边界，不依赖真实时间。
 */
class ApiPacer(
    /** 相邻两次请求的最小间隔（毫秒）；0 或负数 = 不限速。取 lambda：设置改完立刻生效 */
    private val intervalMs: () -> Long,
    /** 时钟（毫秒），单测注入假时钟 */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()

    /** 下一个可以发请求的时刻 */
    private var nextAt = 0L

    /** 本次请求该等多少毫秒；顺带把下一个槽位预约出去 */
    fun reserve(): Long {
        val interval = intervalMs()
        if (interval <= 0) return 0
        val now = clock()
        return synchronized(lock) {
            // 不早于"上次预约 + 间隔"；很久没请求过（nextAt 在过去）就直接发
            val at = maxOf(nextAt, now)
            nextAt = at + interval
            at - now
        }
    }
}

/**
 * 给"打 115 资源接口"的那个 OkHttpClient 挂的限速拦截器（见 App115 的 `apiOkHttpClient`）。
 *
 * ★ 只挂在**那一个** client 上，这是刻意的：
 *  - 图片字节（Coil 用的是 `okHttpClient`）走的是 115 的 CDN，跟 open 接口不是同一套频控；
 *  - 大文件上传分片是用 HttpURLConnection 直连 OSS 的，根本不过这里（见 Uploader）；
 *  - 视频播放与缓冲走 ExoPlayer 自己的传输栈，也不过这里。
 *  把上面这些都一起限速，只会白白拖慢海报墙、上传和起播，对"别被 115 频控"毫无帮助。
 *
 * ★ 已经自带节流的后台任务（媒体库扫描、分类整理）用的是**不带本拦截器**的 client：
 *  它们那套"按目录数选档"的间隔是专门调过的（一个目录会产生好几次请求，按请求数选档
 *  会让小库早早掉到最慢档），再叠一层全局间隔会让两套时钟互相顶。
 *
 * 拦截器是同步的：`Thread.sleep` 会占住一个 OkHttp 工作线程 —— 这正是"串行化"要的效果，
 * 而且这些线程本来就会为每个并发请求各自开一个，不会卡住调用方（调用方是挂起协程）。
 */
class RateLimitInterceptor(private val pacer: ApiPacer) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val wait = pacer.reserve()
        if (wait > 0) {
            // 只在真的等了才打日志：排查"某个操作莫名变慢"时，这一行直接指出是谁在被限速
            Log.i(TAG, "限速等待 ${wait}ms：${chain.request().url.encodedPath}")
            Thread.sleep(wait)
        }
        return chain.proceed(chain.request())
    }

    private companion object {
        const val TAG = "ApiRateLimit"
    }
}
