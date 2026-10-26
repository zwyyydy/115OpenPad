package com.open115.tv.ui

import kotlin.math.abs

/**
 * 电视端可选的倍速档位。
 *
 * 与 115pad 播放器和投屏控制面板同一套（0.5~3.0）：平板上选过的档位在电视这边也能点亮，
 * 反过来在电视上改完，平板 /status 里读到的也是同一个值。
 */
internal val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

/**
 * 控制条按钮文案：正常速度只写「倍速」，其余与平板播放器同口径（「倍速 x1.5」）。
 * 平板协议允许 0.25~4.0，超出档位表的值也如实显示——不能显示成一个假的档位。
 */
internal fun speedButtonLabel(speed: Float): String =
    if (speed == 1f) "倍速" else "倍速 x$speed"

/** 档位胶囊文案；Float.toString 的口径与平板面板一致（x0.75 / x2.0） */
internal fun speedChipLabel(speed: Float): String = "x$speed"

/** 当前倍速是不是这一档（用于点亮胶囊；浮点只做容差比较） */
internal fun isCurrentSpeed(current: Float, step: Float): Boolean = abs(current - step) < 0.001f

/** 打开面板时焦点落哪一档：离当前倍速最近的那档（平板可能设过档位表里没有的值） */
internal fun nearestSpeedIndex(current: Float): Int =
    PLAYBACK_SPEEDS.indices.minByOrNull { abs(PLAYBACK_SPEEDS[it] - current) } ?: 0
