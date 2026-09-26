package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private val Context.appDataStore by preferencesDataStore(name = "app")

/**
 * 程序启动后落在哪一页（设置 → 界面显示 → 「启动首页」）。
 *
 * [route] 就是 AppRoot 导航图里的路由名 —— 这里是**唯一**的对应关系来源，
 * AppRoot 别再硬编码 "files"/"media"。
 */
enum class StartPage(val route: String) {
    FILES("files"),
    MEDIA("media"),
    ;

    companion object {
        /**
         * 存的是枚举**名字**不是序号：以后调整枚举顺序/加页面不会把老设置串味
         * （与作品排序 WorksSort 同一套做法）。
         */
        fun fromName(name: String?): StartPage = entries.firstOrNull { it.name == name } ?: FILES
    }
}

/** 外观模式（设置 → 界面显示 → 「外观」）：明亮 / 黑暗 / 跟随系统。媒体库恒暗不受影响 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
    ;

    companion object {
        /** 默认明亮：与历版观感一致，黑暗由用户显式开启（SYSTEM 也可能一上来就翻暗） */
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: LIGHT
    }
}

/**
 * 应用级偏好：目前只有「启动首页」。
 *
 * 单独一个 DataStore 而不是塞进 FilesPrefs —— 它不是文件页的状态（早先误存过
 * 「文件页的记忆」类字段的教训：文件页清筛选不该影响启动行为），
 * 与 player/download/media 几套偏好也是各存各的。
 */
class AppPrefs(private val context: Context) {

    val startPage: Flow<StartPage> = context.appDataStore.data.map { StartPage.fromName(it[KEY_START_PAGE]) }

    suspend fun setStartPage(page: StartPage) = context.appDataStore.edit { it[KEY_START_PAGE] = page.name }

    val themeMode: Flow<ThemeMode> = context.appDataStore.data.map { ThemeMode.fromName(it[KEY_THEME]) }

    suspend fun setThemeMode(mode: ThemeMode) = context.appDataStore.edit { it[KEY_THEME] = mode.name }

    // ---- 壁纸（仅明亮模式显示；URI 来自 SAF，需持久化读权限） ----
    val wallpaperUri: Flow<String?> = context.appDataStore.data.map { it[KEY_WALLPAPER] }
    val wallpaperMask: Flow<Float> = context.appDataStore.data.map { (it[KEY_WALLPAPER_MASK] ?: 45) / 100f }
    val wallpaperBlur: Flow<Float> = context.appDataStore.data.map { (it[KEY_WALLPAPER_BLUR] ?: 30) / 100f }

    suspend fun setWallpaper(uri: String?) = context.appDataStore.edit {
        if (uri == null) it.remove(KEY_WALLPAPER) else it[KEY_WALLPAPER] = uri
    }

    suspend fun setWallpaperMask(v: Float) = context.appDataStore.edit {
        it[KEY_WALLPAPER_MASK] = (v.coerceIn(0f, 1f) * 100).roundToInt()
    }

    suspend fun setWallpaperBlur(v: Float) = context.appDataStore.edit {
        it[KEY_WALLPAPER_BLUR] = (v.coerceIn(0f, 1f) * 100).roundToInt()
    }

    // ---- 壁纸玻璃卡（列表行）三参数：设置里可调 ----
    val glassCardAlpha: Flow<Float> = context.appDataStore.data.map { (it[KEY_GLASS_ALPHA] ?: 78) / 100f }
    val glassGapDp: Flow<Int> = context.appDataStore.data.map { it[KEY_GLASS_GAP] ?: 8 }
    val glassRadiusDp: Flow<Int> = context.appDataStore.data.map { it[KEY_GLASS_RADIUS] ?: 14 }

    suspend fun setGlassCardAlpha(v: Float) = context.appDataStore.edit {
        it[KEY_GLASS_ALPHA] = (v.coerceIn(0f, 1f) * 100).roundToInt()
    }

    suspend fun setGlassGapDp(v: Int) = context.appDataStore.edit { it[KEY_GLASS_GAP] = v.coerceIn(0, 24) }

    suspend fun setGlassRadiusDp(v: Int) = context.appDataStore.edit { it[KEY_GLASS_RADIUS] = v.coerceIn(0, 28) }

    private companion object {
        val KEY_START_PAGE = stringPreferencesKey("start_page")
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_WALLPAPER = stringPreferencesKey("wallpaper_uri")
        val KEY_WALLPAPER_MASK = intPreferencesKey("wallpaper_mask")
        val KEY_WALLPAPER_BLUR = intPreferencesKey("wallpaper_blur")
        val KEY_GLASS_ALPHA = intPreferencesKey("glass_card_alpha")
        val KEY_GLASS_GAP = intPreferencesKey("glass_gap_dp")
        val KEY_GLASS_RADIUS = intPreferencesKey("glass_radius_dp")
    }
}
