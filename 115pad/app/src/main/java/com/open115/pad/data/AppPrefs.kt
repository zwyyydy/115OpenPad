package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    private companion object {
        val KEY_START_PAGE = stringPreferencesKey("start_page")
    }
}
