package com.open115.tv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * 通知渠道在 Application 里建：接收服务可能被系统在任意时序拉起（START_STICKY 重启、
 * 界面启动前的服务启动），渠道必须先于通知存在，放在服务里建会漏掉早期时序。
 */
class TvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "投屏接收", NotificationManager.IMPORTANCE_LOW).apply {
                description = "待机等待投屏、以及正在播放时的常驻提示"
                setShowBadge(false)
            },
        )
        // 后台拉起界面被系统拦住时的"点击播放"提示：要高优先级才浮出来
        if (nm.getNotificationChannel(ATTENTION_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    ATTENTION_CHANNEL_ID,
                    "投屏提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "收到投屏但电视端不在前台时，提醒点击播放"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "receiver"
        const val ATTENTION_CHANNEL_ID = "receiver_attention"
    }
}
