package com.open115.pad.player

import java.util.Locale

/**
 * 视频滤镜参数（实验室功能）。
 *
 * 字段语义与默认值都照搬 [dkfilter](https://github.com/Doikki/DKVideoPlayer) 那套实验管线：
 * 默认构造 = 原图（无滤镜），每个字段都是"相对中性值的偏移"，这样"要不要真的过 GL"
 * 只要跟默认值比一次就知道（见 [isNeutral]）。
 *
 * 分两组：
 *  - 滑块组（亮度/对比度/饱和度/色温/磨皮/暗角）在播放器面板里可实时调；
 *  - 风格组（阴影色、高光色、色调、gamma、褪色、单色）只由预设给值，不单独暴露滑块 ——
 *    预设之所以有"风格"，靠的就是这几个，开放出去反而会把预设调糊。
 */
internal data class FilterParams(
    var brightness: Float = 0f,
    var contrast: Float = 1f,
    var saturation: Float = 1f,
    var temperature: Float = 0f,
    var smooth: Float = 0f,
    var vignette: Float = 0f,
    // ---- 风格组 ----
    var tint: Float = 0f,
    var shadowTintR: Float = 0f,
    var shadowTintG: Float = 0f,
    var shadowTintB: Float = 0f,
    var highlightTintR: Float = 0f,
    var highlightTintG: Float = 0f,
    var highlightTintB: Float = 0f,
    var gamma: Float = 1f,
    var fade: Float = 0f,
    var mono: Float = 0f,
) {
    /**
     * 是否等于"原图"。
     *
     * 这是**渲染路径的开关**：中性参数时播放器仍走原来的 TextureView 路径，
     * 一帧 GL 都不跑 —— 实验室开关打开但选了"原图"的用户不该为此付任何代价。
     */
    fun isNeutral(): Boolean = this == FilterParams()

    /** 存进 DataStore 的紧凑写法（16 个 float 逗号分隔）；读回见 [fromPrefString] */
    fun toPrefString(): String = listOf(
        brightness, contrast, saturation, temperature, smooth, vignette,
        tint, shadowTintR, shadowTintG, shadowTintB,
        highlightTintR, highlightTintG, highlightTintB,
        gamma, fade, mono,
    ).joinToString(",")

    companion object {
        /** 字段个数即持久化格式的字段数，**顺序不要重排**（重排会让已存参数错位） */
        private const val FIELD_COUNT = 16

        /**
         * 解析持久化串；空串、字段数不符、坏数字一律退回默认值（= 原图）。
         *
         * 宁可退回原图也不要抛：这是播放路径上的数据，参数串坏掉时用户看到的应该是
         * "滤镜没生效"，而不是播放器打不开。
         */
        fun fromPrefString(s: String?): FilterParams {
            val parts = s?.split(',') ?: return FilterParams()
            if (parts.size != FIELD_COUNT) return FilterParams()
            val v = parts.map { it.trim().toFloatOrNull() ?: return FilterParams() }
            return FilterParams(
                brightness = v[0], contrast = v[1], saturation = v[2], temperature = v[3],
                smooth = v[4], vignette = v[5],
                tint = v[6],
                shadowTintR = v[7], shadowTintG = v[8], shadowTintB = v[9],
                highlightTintR = v[10], highlightTintG = v[11], highlightTintB = v[12],
                gamma = v[13], fade = v[14], mono = v[15],
            )
        }
    }
}

/** 预设风格：一个名字 + 一组参数 */
internal data class FilterPreset(val name: String, val params: FilterParams)

/**
 * 内置风格预设，与参考项目（dkfilter）保持一致：原图 + 7 种风格。
 *
 * 第 0 个必须是原图（中性参数），播放器面板默认选它 —— 与 [FilterParams.isNeutral]
 * 一起保证"没选风格 = 不走 GL"。
 */
internal object FilterPresets {

    val all: List<FilterPreset> = listOf(
        FilterPreset("原图", FilterParams()),
        FilterPreset(
            "鲜艳",
            FilterParams(brightness = 0.02f, contrast = 1.12f, saturation = 1.38f, gamma = 0.98f),
        ),
        FilterPreset(
            "清新",
            FilterParams(
                brightness = 0.05f, contrast = 1.06f, saturation = 1.16f,
                temperature = -0.05f,
                shadowTintB = 0.05f, shadowTintG = 0.02f,
                gamma = 0.99f,
            ),
        ),
        FilterPreset(
            "暖阳",
            FilterParams(
                brightness = 0.03f, contrast = 1.08f, saturation = 1.14f,
                temperature = 0.08f, tint = 0.01f,
                highlightTintR = 0.03f,
                gamma = 1.02f,
            ),
        ),
        FilterPreset(
            "复古胶片",
            FilterParams(
                brightness = 0.01f, contrast = 0.94f, saturation = 0.86f,
                temperature = 0.05f, fade = 0.13f,
                shadowTintR = 0.03f, shadowTintB = 0.02f,
                highlightTintR = 0.06f, highlightTintB = -0.05f,
                gamma = 1.06f, vignette = 0.25f,
            ),
        ),
        FilterPreset(
            "电影感",
            FilterParams(
                contrast = 1.2f, saturation = 0.9f,
                shadowTintG = 0.02f, shadowTintB = 0.08f,
                highlightTintR = 0.07f, highlightTintB = -0.04f,
                gamma = 1.01f, vignette = 0.34f,
            ),
        ),
        FilterPreset(
            "黑白",
            FilterParams(
                contrast = 1.18f, saturation = 0f, mono = 1f,
                gamma = 0.97f, vignette = 0.2f,
            ),
        ),
        FilterPreset(
            "粉嫩美颜",
            FilterParams(
                brightness = 0.07f, contrast = 0.95f, saturation = 1.12f,
                temperature = 0.03f, smooth = 0.8f,
                highlightTintR = 0.05f, highlightTintB = 0.02f,
                gamma = 1.0f, vignette = 0.12f,
            ),
        ),
    )

    /**
     * 参数命中的预设下标；一个都不等（用户拖过滑块）返回 -1。
     *
     * 用整组参数比而不是记"上次点了哪个预设"：拖过滑块之后就该显示成"自定义"，
     * 否则滑块值已经和预设不符、胶囊却还高亮着，用户会以为没生效。
     */
    fun indexOf(params: FilterParams): Int = all.indexOfFirst { it.params == params }
}

/**
 * 面板滑块定义：参数取值与显示格式都由这里给，免得面板里散落一堆魔数。
 *
 * @param get 读当前值
 * @param set 写入新值（原地改）
 * @param format 显示文本（各参数的量纲不同：倍数、偏移、强度）
 */
internal class FilterSlider(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val get: (FilterParams) -> Float,
    val set: (FilterParams, Float) -> Unit,
    val format: (Float) -> String,
)

/** 面板暴露的 6 个滑块（顺序即显示顺序，两列一行两个） */
internal val FILTER_SLIDERS: List<FilterSlider> = listOf(
    FilterSlider(
        "亮度", -0.3f..0.3f, { it.brightness }, { p, v -> p.brightness = v },
        { String.format(Locale.US, "%+.2f", it) },
    ),
    FilterSlider(
        "对比度", 0.6f..1.6f, { it.contrast }, { p, v -> p.contrast = v },
        { String.format(Locale.US, "%.2f", it) },
    ),
    FilterSlider(
        "饱和度", 0f..2f, { it.saturation }, { p, v -> p.saturation = v },
        { String.format(Locale.US, "%.2f", it) },
    ),
    FilterSlider(
        "色温", -0.3f..0.3f, { it.temperature }, { p, v -> p.temperature = v },
        { String.format(Locale.US, "%+.2f", it) },
    ),
    FilterSlider(
        "磨皮", 0f..1f, { it.smooth }, { p, v -> p.smooth = v },
        { String.format(Locale.US, "%.2f", it) },
    ),
    FilterSlider(
        "暗角", 0f..0.6f, { it.vignette }, { p, v -> p.vignette = v },
        { String.format(Locale.US, "%.2f", it) },
    ),
)
