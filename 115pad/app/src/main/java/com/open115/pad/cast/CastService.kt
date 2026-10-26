package com.open115.pad.cast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.open115.pad.util.AppLog as Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.open115.pad.MainActivity
import com.open115.pad.R

/**
 * 投屏期间的保活服务。
 *
 * 它**不负责搬运视频**（中转在本进程的 [CastRelayServer] 里跑），职责只有两件：
 * 1. 前台通知 —— 让系统不把进程回收，电视的流才不会断；
 * 2. 唤醒锁 + Wi-Fi 锁 —— 锁屏后 CPU/Wi-Fi 不休眠。视频是从本机经 Wi-Fi 转发的，
 *    屏幕一关就掉速/断流是这类中转最典型的"投一会儿就卡"。
 *
 * 用户可以正常锁屏、切到别的 App；只有手动强行停止或重启才会断。
 * 通知上的「停止投屏」走 [CastSession.stopFromService]，与播放页里的停止是同一套清理。
 */
class CastService : Service() {

    private var foregroundReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * 是否需要保活（唤醒锁 + Wi-Fi 锁）。
     *
     * DLNA 通道必须保活：视频是从本机经 Wi-Fi 转发的，屏幕一关就掉速。
     * PadTv 通道不用：流是电视端自己拉的，这条通知只负责"回到遥控器"这个入口。
     */
    private var keepAlive = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CastSession.stopFromService(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val device = intent?.getStringExtra(EXTRA_DEVICE).orEmpty()
        keepAlive = intent?.getBooleanExtra(EXTRA_KEEP_ALIVE, true) ?: keepAlive
        if (!foregroundReady) {
            // 与 KeepAliveService 同一条纪律：startForegroundService 之后必须尽快
            // startForeground，超时会被系统判死；失败就干脆退场，别占着坑。
            // ★ 顺序：先 startForeground，再看"会话还在不在"—— 反过来在等待前台化期间
            //   stopSelf 会让系统按超时把进程判死（KeepAliveService 实测踩过）。
            val ok = runCatching {
                startForeground(NOTIF_ID, buildNotification(device))
            }.onFailure { Log.w(TAG, "startForeground 失败: ${it.message}") }.isSuccess
            if (!ok) {
                stopSelf()
                return START_NOT_STICKY
            }
            foregroundReady = true
            foregrounded = true
        } else {
            // 换设备/重新投屏：更新通知文案即可
            runCatching {
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIF_ID, buildNotification(device))
            }
        }
        // 会话已经结束（启动/停止抢跑、或进程被回收后重新投递）：别挂一条空通知占着锁
        if (!CastSession.isActive()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (keepAlive) acquireLocks() else releaseLocks()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        onDestroyed()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = runCatching {
                (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "115pad:cast")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire(MAX_LOCK_MS)
                    }
            }.getOrNull()
        }
        if (wifiLock == null) {
            wifiLock = runCatching {
                val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return@runCatching null
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wm.createWifiLock(mode, "115pad:cast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun buildNotification(device: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            CastSession.remoteIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val to = if (device.isBlank()) "电视" else device
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_notification)
            .setContentTitle("正在投屏到 $to")
            .setContentText(
                if (keepAlive) "平板可以锁屏，投屏会继续" else "点击打开遥控器；平板退出也不影响投屏",
            )
            .setContentIntent(open)
            .addAction(0, "停止投屏", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "投屏", NotificationManager.IMPORTANCE_LOW).apply {
                description = "投屏到电视期间保持本机网络连接"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL_ID = "cast_session"
        private const val NOTIF_ID = 0x5A02
        private const val EXTRA_DEVICE = "device"

        /** true = 顺带保活（DLNA 中转必须）；false = 只挂一条"回到遥控器"的通知（PadTv） */
        private const val EXTRA_KEEP_ALIVE = "keep_alive"
        private const val ACTION_STOP = "com.open115.pad.cast.STOP"

        /** 唤醒锁定时上限：异常退出时最多多耗 6 小时，必须在 onDestroy 里主动放 */
        private const val MAX_LOCK_MS = 6 * 60 * 60 * 1000L

        @Volatile
        private var running = false

        /** 已经真的 startForeground 过了吗：没前台化就 stopService 会把进程判死 */
        @Volatile
        private var foregrounded = false

        fun start(context: Context, deviceName: String, keepAlive: Boolean = true) {
            val intent = Intent(context, CastService::class.java)
                .putExtra(EXTRA_DEVICE, deviceName)
                .putExtra(EXTRA_KEEP_ALIVE, keepAlive)
            if (running) {
                // 已经在跑：换设备/换通道只更新通知文案与锁策略
                runCatching { context.startService(intent) }
                return
            }
            running = true
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    running = false
                    Log.w(TAG, "投屏前台服务起不来: ${it.message}")
                }
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            // 还没前台化就别停：那等于"拆一个正在等 startForeground 的服务"，
            // 系统会按超时把进程判死（与 KeepAliveService 同一条教训）
            if (!foregrounded) return
            foregrounded = false
            runCatching { context.stopService(Intent(context, CastService::class.java)) }
        }

        fun onDestroyed() {
            running = false
            foregrounded = false
        }
    }
}
