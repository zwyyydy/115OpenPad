package com.open115.pad.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生成 baseline / startup profile。
 *
 * 拆成两个用例是刻意的：
 * - `startup` 只到首帧，标记 `includeInStartupProfile = true` —— 这些方法参与 Android 12+
 *   的 DEX 布局优化；
 * - `fileListScroll` 只进 baseline profile。如果把滚动也算进 startup，几千个列表/图片方法
 *   会被塞进启动布局，反而稀释真正该前置的启动代码。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    private val packageName = "com.open115.pad"

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = packageName,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun fileListScroll() = rule.collect(
        packageName = packageName,
        includeInStartupProfile = false,
    ) {
        pressHome()
        startActivityAndWait()

        // 首屏是文件页：等可滚动列表出现，再上下甩一次，把 LazyColumn / 缩略图加载
        // 的热路径带进 baseline profile。找不到也不失败（启动部分仍然有效）。
        val list = device.wait(Until.findObject(By.scrollable(true)), 10_000)
        list?.let {
            runCatching {
                it.fling(Direction.DOWN)
                it.fling(Direction.UP)
            }
        }
        device.waitForIdle()
    }
}
