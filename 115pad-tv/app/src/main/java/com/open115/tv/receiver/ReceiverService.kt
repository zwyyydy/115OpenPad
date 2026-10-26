package com.open115.tv.receiver

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.open115.tv.MainActivity
import com.open115.tv.R
import com.open115.tv.TvApp
import com.open115.tv.util.deviceName
import com.open115.tv.util.lanIpv4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "TvReceiverService"

/**
 * 待机常驻服务：把 [ReceiverServer] 挂起来，并在收到投屏时把播放界面带到前台。
 *
 * 与 115pad 的 CastService 是同一套纪律：
 * - startForegroundService 之后必须尽快 startForeground，失败干脆退场别占坑；
 * - 还没前台化就不许 stopService（那等于把一个正等 startForeground 的服务拆掉，系统会按超时判死）；
 * - Wi-Fi 锁防待机时收不到 UDP/HTTP（电视夜里息屏但服务还得在）。
 *
 * 这里**不搬视频**——流是播放器自己从 115 CDN 拉的，服务只负责"随时能被叫醒"。
 */
class ReceiverService : Service() {

    private var foregroundReady = false
    private var receiver: ReceiverServer? = null

    @Volatile
    private var starting = false
    private var wifiLock: WifiManager.WifiLock? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statusObserved = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ★ 顺序：先 startForeground，再碰别的。反过来在等待前台化期间 stopSelf 会被系统判死
        if (!ensureForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureServer()
        acquireWifiLock()
        observeStatus()
        // START_STICKY：被国产电视的激进省电杀过之后，系统尽量把它拉回来（拉回也只是重新收投屏）
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.stop()
        receiver = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        scope.cancel()
        onDestroyed()
        super.onDestroy()
    }

    // ---------- 前台与通知 ----------

    private fun ensureForeground(): Boolean {
        if (foregroundReady) return true
        val ok = runCatching {
            startForeground(NOTIF_ID, buildNotification(PlayBus.status.value))
        }.onFailure { Log.w(TAG, "startForeground 失败: ${it.message}") }.isSuccess
        if (!ok) return false
        foregroundReady = true
        foregrounded = true
        return true
    }

    private fun observeStatus() {
        if (statusObserved) return
        statusObserved = true
        scope.launch {
            PlayBus.status.collect { st ->
                updateNotification(st)
                if (st.state != TvProtocol.STATE_IDLE) cancelAttention()
            }
        }
    }

    private fun updateNotification(st: StatusResponse) {
        if (!foregroundReady) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIF_ID, buildNotification(st)) }
    }

    private fun buildNotification(st: StatusResponse): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val playingTitle = st.title.ifBlank { "视频" }
        val title = when (st.state) {
            TvProtocol.STATE_PLAYING, TvProtocol.STATE_BUFFERING -> "正在播放 $playingTitle"
            TvProtocol.STATE_PAUSED -> "已暂停 $playingTitle"
            TvProtocol.STATE_ERROR -> "播放出错"
            else -> "115 openpad电视端 · 待命中"
        }
        val text = when (st.state) {
            TvProtocol.STATE_PLAYING, TvProtocol.STATE_BUFFERING, TvProtocol.STATE_PAUSED ->
                "平板可以退出，投屏会继续"
            TvProtocol.STATE_ERROR -> st.error ?: "看下电视屏幕上的提示"
            else -> {
                val ip = lanIpv4()
                if (ip != null) "$ip · 等平板投屏" else "未连接局域网"
            }
        }
        return NotificationCompat.Builder(this, TvApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** 后台拉起界面被系统拦下时的兜底：一条可点的高优先级通知 */
    private fun notifyAttention(req: PlayRequest) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, TvApp.ATTENTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在投屏：${req.title.ifBlank { "视频" }}")
            .setContentText("点击在电视上播放")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify(ATTENTION_NOTIF_ID, notif) }
    }

    private fun cancelAttention() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(ATTENTION_NOTIF_ID) }
    }

    // ---------- 接收端 ----------

    private fun ensureServer() {
        if (receiver != null || starting) return
        starting = true
        scope.launch {
            var attempt = 0
            while (receiver == null) {
                val srv = ReceiverServer(
                    deviceName = deviceName(this@ReceiverService),
                    statusProvider = { PlayBus.status.value },
                    onPlay = { onPlayReceived(it) },
                    onControl = { PlayBus.postControl(it) },
                    onQueue = { PlayBus.postQueue(it) },
                    onSubsPush = { PlayBus.postSubsPush(it) },
                )
                if (runCatching { srv.start() }.isSuccess) {
                    receiver = srv
                    Log.i(TAG, "接收端已就绪")
                    break
                }
                attempt++
                // 启动早期网络可能还没就绪（电视开机自启场景），退避重试、永不放弃
                val wait = when {
                    attempt < 3 -> 3_000L
                    attempt < 6 -> 15_000L
                    else -> 60_000L
                }
                Log.w(TAG, "接收端启动失败，${wait / 1000}s 后重试（第 $attempt 次）")
                delay(wait)
            }
            starting = false
        }
    }

    /**
     * 网络线程里被调用：登记待播请求 → 尝试把界面带到前台。
     * 界面本来就在（待机页）时什么都不用做——它 collect 到 [PlayBus.pending] 会自己切播放页。
     */
    private fun onPlayReceived(req: PlayRequest) {
        PlayBus.post(req)
        updateNotification(
            StatusResponse(state = TvProtocol.STATE_BUFFERING, title = req.title),
        )
        if (MainActivity.isVisible) return
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }.onFailure { Log.w(TAG, "拉起界面失败: ${it.message}") }
        scope.launch {
            // Android 10+ 的后台启动限制是"静默拦截"：startActivity 不抛异常，只是没发生。
            // 等两秒看界面是否真起来了，没起来就发通知兜底
            delay(2_500)
            if (!MainActivity.isVisible) {
                Log.w(TAG, "界面未拉起（后台启动被限制），改走通知")
                notifyAttention(req)
            }
        }
    }

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        wifiLock = runCatching {
            val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return@runCatching null
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wm.createWifiLock(mode, "115tv:receiver").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "WifiLock 获取失败: ${it.message}") }.getOrNull()
    }

    companion object {
        private const val NOTIF_ID = 0x7A01
        private const val ATTENTION_NOTIF_ID = 0x7A02

        @Volatile
        private var running = false

        /** 已经真的 startForeground 过了吗：没前台化就 stopService 会把进程判死 */
        @Volatile
        private var foregrounded = false

        fun start(context: Context) {
            if (running) return
            running = true
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
            }.onFailure {
                running = false
                Log.w(TAG, "接收服务起不来: ${it.message}")
            }
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            if (!foregrounded) return
            foregrounded = false
            runCatching { context.stopService(Intent(context, ReceiverService::class.java)) }
        }

        fun onDestroyed() {
            running = false
            foregrounded = false
        }
    }
}
