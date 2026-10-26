package com.open115.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 倍速档位的纯逻辑：文案口径要与平板播放器/投屏面板一致（两边对不上就会像是"设了没生效"）。
 */
class PlaybackSpeedsTest {

    @Test
    fun buttonLabel_hidesNormalSpeed() {
        assertEquals("倍速", speedButtonLabel(1f))
        assertEquals("倍速 x1.5", speedButtonLabel(1.5f))
        assertEquals("倍速 x0.75", speedButtonLabel(0.75f))
        // 平板协议收 0.25~4.0：档位表外的值也要如实显示
        assertEquals("倍速 x4.0", speedButtonLabel(4f))
    }

    @Test
    fun chipLabel_matchesTabletPanel() {
        assertEquals("x0.5", speedChipLabel(0.5f))
        assertEquals("x2.0", speedChipLabel(2f))
        assertEquals("x3.0", speedChipLabel(3f))
    }

    @Test
    fun currentSpeed_comparesWithTolerance() {
        assertTrue(isCurrentSpeed(1.5f, 1.5f))
        assertTrue(isCurrentSpeed(1.5f, 1.5f + 1e-5f))
        assertFalse(isCurrentSpeed(1.5f, 1.25f))
        // 4.0 不在档位表里：不能点亮 3.0
        assertFalse(isCurrentSpeed(4f, 3f))
    }

    @Test
    fun nearestIndex_picksClosestStep() {
        assertEquals(2, nearestSpeedIndex(1f))
        assertEquals(6, nearestSpeedIndex(2f))
        assertEquals(2, nearestSpeedIndex(0.9f))
        // 0.5 是表里最小值；4.0 落到最大档
        assertEquals(0, nearestSpeedIndex(0f))
        assertEquals(7, nearestSpeedIndex(4f))
    }
}
