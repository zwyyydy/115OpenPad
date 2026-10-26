package com.open115.tv.util

import android.content.Context
import com.open115.tv.data.DEF_ORIGINAL_FILE
import com.open115.tv.receiver.TvProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 电视端的播放偏好：解码方式（硬解/软解）、播放器（内置 / 电视自带）、自动连播、清晰度与迷你进度条。
 *
 * 为什么用 SharedPreferences 而不是 DataStore：本工程刻意不引 DataStore（电视端就这几项设置），
 * 系统自带的那套够用。
 *
 * 内存里镜像成 StateFlow：播放页要跟着设置变化重建播放器、设置页要显示当前值、/status 要上报，
 * 三处都必须立刻看到改了什么（与 115pad 的 playerPrefs 同一套做法）。
 */
internal object TvPrefs {
    private const val FILE = "tv_prefs"
    private const val KEY_DECODE = "decode"
    private const val KEY_PLAYER = "player"
    private const val KEY_MINI_PROGRESS = "mini_progress"
    private const val KEY_AUTO_NEXT = "auto_next"
    private const val KEY_QUALITY = "quality_def"

    private val _decode = MutableStateFlow(TvProtocol.DECODE_HW)
    val decode: StateFlow<String> = _decode.asStateFlow()

    private val _player = MutableStateFlow(TvProtocol.PLAYER_BUILTIN)
    val player: StateFlow<String> = _player.asStateFlow()

    /** 控制条收起时是否在屏幕最下方常驻一条细进度线 */
    private val _miniProgress = MutableStateFlow(true)
    val miniProgress: StateFlow<Boolean> = _miniProgress.asStateFlow()

    /** 一集播完是否自动接下一集（队列由平板在投屏时带过来） */
    private val _autoNext = MutableStateFlow(true)
    val autoNext: StateFlow<Boolean> = _autoNext.asStateFlow()

    /**
     * 首选清晰度档位（999 = 原盘）：在播放器的画质菜单里选，跨集/跨投屏沿用。
     * 某一集没有这个档位时退回原盘（见 pickTier）。
     */
    private val _qualityDef = MutableStateFlow(DEF_ORIGINAL_FILE)
    val qualityDef: StateFlow<Int> = _qualityDef.asStateFlow()

    @Volatile
    private var loaded = false

    /** 首次读取在 Application 或首个界面出现时调用（幂等） */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _decode.value = sp.getString(KEY_DECODE, TvProtocol.DECODE_HW) ?: TvProtocol.DECODE_HW
        _player.value = sp.getString(KEY_PLAYER, TvProtocol.PLAYER_BUILTIN) ?: TvProtocol.PLAYER_BUILTIN
        _miniProgress.value = sp.getBoolean(KEY_MINI_PROGRESS, true)
        _autoNext.value = sp.getBoolean(KEY_AUTO_NEXT, true)
        _qualityDef.value = sp.getInt(KEY_QUALITY, DEF_ORIGINAL_FILE)
    }

    fun setDecode(context: Context, value: String) {
        val v = if (value == TvProtocol.DECODE_SW) TvProtocol.DECODE_SW else TvProtocol.DECODE_HW
        _decode.value = v
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_DECODE, v).apply()
    }

    fun setPlayer(context: Context, value: String) {
        val v = if (value == TvProtocol.PLAYER_SYSTEM) TvProtocol.PLAYER_SYSTEM else TvProtocol.PLAYER_BUILTIN
        _player.value = v
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_PLAYER, v).apply()
    }

    fun setMiniProgress(context: Context, value: Boolean) {
        _miniProgress.value = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MINI_PROGRESS, value).apply()
    }

    fun setAutoNext(context: Context, value: Boolean) {
        _autoNext.value = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_NEXT, value).apply()
    }

    fun setQualityDef(context: Context, def: Int) {
        _qualityDef.value = def
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_QUALITY, def).apply()
    }
}
