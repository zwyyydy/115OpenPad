package com.open115.pad.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 「忽略电池优化」白名单的查询与申请（设置 → 后台任务用它）。
 *
 * 为什么单独一档：Android 官方的那层是"前台服务"（见 [KeepAliveService]），
 * 但国产 ROM（华为/小米/OPPO/vivo…）还有自家的省电策略 —— 进了这个白名单才少一层被杀的
 * 理由。系统里还可能有独立的「自启动」「后台弹出界面」开关，那些本应用看不到、也改不了，
 * 设置页的说明里写清了让用户自己去看。
 *
 * 这里**只做系统交互**，不存状态：白名单在系统那边，每次用之前现查
 * （用户可能刚去系统设置里加/删，缓存一份只会显示成旧值）。
 */
object BatteryOptimization {

    private const val TAG = "BatteryOptimization"

    /** 本应用现在是否已在"忽略电池优化"白名单里 */
    fun isWhitelisted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * 拉起系统的申请界面。
     *
     * 还没进白名单 → 弹"是否允许忽略电池优化"的确认框；已经在里面 → 直接打开白名单列表页
     * （让用户能看到/自己撤销，而不是点一下什么都不发生）。有些 ROM 这两个页面都没有，
     * 兜底退到应用详情页（那里通常能直接看到"电池"/"省电策略"入口）。
     */
    fun request(context: Context) {
        val ctx = context.applicationContext
        val whitelisted = isWhitelisted(ctx)
        val action = if (whitelisted) {
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        } else {
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!whitelisted) intent.data = Uri.parse("package:${ctx.packageName}")
        runCatching { ctx.startActivity(intent) }.onFailure { e ->
            Log.w(TAG, "电池优化页面打不开（${action}）: ${e.message}")
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w(TAG, "应用详情页也打不开: ${it.message}") }
        }
    }
}
