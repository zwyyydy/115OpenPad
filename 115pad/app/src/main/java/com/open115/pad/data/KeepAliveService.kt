package com.open115.pad.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.open115.pad.MainActivity
import com.open115.pad.R
import com.open115.pad.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 任务常驻服务（设置 → 后台任务 → 「任务进行时防止被杀」）：有任务在跑时挂一条常驻通知，
 * 进程就不容易被系统回收。
 *
 * 为什么需要它：扫描媒体库 / 上传 / 重命名都是**进程内的协程**，切后台或锁屏之后
 * 系统随时可能回收进程，任务就断在半路（扫描靠 scan_state、上传靠断点续传能接着跑，
 * 但白等一轮；重命名队列同理）。前台服务是 Android 上唯一官方支持的"别杀我"手段 ——
 * 国产 ROM 还会按自己的省电策略杀，所以设置页那个开关会顺手申请电池优化白名单。
 *
 * 职责很小：**只负责显示与常驻**。显示什么由 [AppContainer.taskActivity] 给（那才是
 * "现在有什么在跑"的唯一来源），空闲了（null）自己停掉 —— 谁启谁停不用外面记着。
 * 由 [AppContainer] 在"开关开着且有任务"时启动、否则停止。
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    /** 本实例已经 startForeground 过了吗（每次 onStartCommand 都要有，但只做一次就够） */
    private var foregroundReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须先 startForeground：startForegroundService 之后系统给的时间很短（约 5 秒），
        // 超时不是"服务起不来"这么轻 —— 系统会往主线程抛 ForegroundServiceDidNotStartInTimeException
        // **把进程判死**（实测踩过一次）。所以这里一失败就干脆自己退场，宁可这次没有保护。
        if (!foregroundReady) {
            val ok = runCatching {
                startForeground(NOTIF_ID, buildNotification(getString(R.string.app_name) + " 正在后台运行"))
            }.onFailure { Log.w(TAG, "startForeground 失败，退场不占坑: ${it.message}") }.isSuccess
            if (!ok) {
                running = false
                stopSelf()
                return START_NOT_STICKY
            }
            foregroundReady = true
            foregrounded = true
            Log.i(TAG, "前台服务就绪（任务跑完会自己退场）")
        }
        watchJob?.cancel()
        watchJob = scope.launch {
            appContainer.taskActivity.collect { activity ->
                if (activity == null) {
                    // 空闲：等一下再确认再退场 —— 队列在两条任务之间有毫秒级空档，
                    // 一闪就退场会让下一轮马上又起一次服务（来回拉扯）
                    delay(IDLE_GRACE_MS)
                    if (appContainer.taskActivity.value == null) {
                        Log.i(TAG, "任务都跑完了，服务退场")
                        stopSelf()
                    }
                } else {
                    notify(buildNotification(activity))
                }
            }
        }
        // START_STICKY：万一真被系统杀了，它会把服务拉回来（拉回来时 intent 为 null，
        // 上面这条 collector 立刻会把真实状态补上）
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        foregrounded = false
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(notification: Notification) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        // 通知权限被拒（Android 13+）时 notify 会静默失败 —— 不影响服务本身，
        // 我们本来就只是要"进程别被杀"，通知只是顺带给用户看的
        runCatching { nm.notify(NOTIF_ID, notification) }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // 系统自带的"下载中"小图标：省一个自绘图标资源，语义也对得上（本机在跑任务）
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name) + " 正在后台运行")
            .setContentText(text)
            .setContentIntent(open)
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
            NotificationChannel(CHANNEL_ID, "后台任务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "有任务（扫描/上传/重命名）在后台跑时显示"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "task_keepalive"
        private const val NOTIF_ID = 0x5A01

        /** 空闲确认时长：任务与任务之间的空档不该让服务一闪一闪 */
        private const val IDLE_GRACE_MS = 1500L

        /**
         * 服务现在应该在跑吗（进程内标记，[start] 先占坑、[onDestroy] 放开）。
         * 为什么要它：`startForegroundService` 每叫一次，系统就记一笔"待办"等着 startForeground ——
         * 重复叫几十次（状态流高频变化时很容易发生）会把系统的记账搞乱，实测能弄到进程被判死。
         */
        @Volatile
        private var running = false

        /** 已经真的 `startForeground` 过了吗（[stop] 据此判断"能不能安全地拆"） */
        @Volatile
        private var foregrounded = false

        /** 服务现在在跑吗（只读；[AppContainer] 据此决定要不要打日志/要不要去停） */
        val isRunning: Boolean get() = running

        /**
         * 起服务（已经在跑就只是送一次 intent）。**可能抛异常**：Android 12+ 不允许
         * 在后台起前台服务，所以调用点都在"有任务"那一刻（那时 App 通常就在前台）；
         * 真被拒了就记一条日志 —— 顶多这次没有保护，不该把调用方搞崩。
         */
        fun start(context: Context) {
            if (running) return
            // 先占坑：startForegroundService 一返回，系统就在等 startForeground，
            // 这一段空窗里再送一次 start 只会多记一笔待办（毫无好处）
            running = true
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, KeepAliveService::class.java),
                )
            }.onFailure {
                running = false
                Log.w(TAG, "前台服务起不来（后台限制？）: ${it.message}")
            }
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            // 还没 startForeground 就别 stopService：那等于"拆一个正在等 startForeground 的服务"，
            // 系统会按超时把进程判死（实测踩过）。这种服务自己会在空闲检测里退场，不用外面操心。
            if (!foregrounded) return
            foregrounded = false
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }
    }
}
