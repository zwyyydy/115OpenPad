package com.open115.pad.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.open115.pad.MainActivity
import com.open115.pad.R

/**
 * 后台任务（媒体库扫描 / 批量重命名 / 分类整理）**跑完**时的系统通知。
 *
 * 只在主界面不可见时发（调用点见 ui/AppRoot 的 finishNotice）：人正看着主界面时 Snackbar 已经报了，
 * 再响一声是噪音；切到别的 App、锁屏、或者在播放器里（另一个 Activity）才需要它补位。
 *
 * 与 [KeepAliveService] 的「正在后台运行」是两条独立渠道：那条是 IMPORTANCE_LOW 的常驻状态
 * （别杀我，全静默），这条是 DEFAULT 的一次性提醒（活干完了，响一声）—— 用户可以单独关掉后者。
 */
object TaskDoneNotifier {

    private const val CHANNEL_ID = "task_done"

    /** 固定 id：完成提醒只留最新一条，多类任务先后跑完时不会在通知栏堆一串 */
    private const val NOTIF_ID = 2001

    /**
     * 发一条完成提醒。
     *
     * 没有通知权限（Android 13+ 被拒）时静默失败 —— 不影响任务本身，
     * 与 [KeepAliveService] 的 notify 同一个处理方式。
     */
    fun notifyDone(context: Context, text: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // 系统自带的"下载完成"小图标：语义对得上（有活干完了），省一个自绘资源
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIF_ID, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "任务完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "扫描 / 重命名 / 整理这些任务跑完时提醒一声"
            },
        )
    }
}
