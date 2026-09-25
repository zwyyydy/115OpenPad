package com.open115.pad.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Science
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 设置页的**数据描述**：整页由这份列表驱动渲染，不再是一长串手写 Composable。
 *
 * 三个好处：
 *  - 加一个设置 = 在对应分类里加一行（switch / slider / choice / action），不用碰布局；
 *  - 分类（[SettingsCategory]）本身就是导航项 —— 平板两栏与手机「列表 → 二级页」共用同一份数据；
 *  - [SettingItem.detail] 把**长解释**和**一行短说明**分开：短说明留在行内，
 *    技术细节收进 ⓘ 折叠里，整页不再是连片的小灰字（这是原来"看着乱"的最大来源）。
 */
sealed interface SettingItem {
    /** 稳定 id：给以后的搜索/收藏/去重用，**别用 title 当 id**（文案会改） */
    val id: String
    val title: String
    /** 一行短说明，太长会被截断（长解释放 [detail]） */
    val subtitle: String
    /** 点 ⓘ 展开的长解释；null = 没有折叠内容 */
    val detail: String?
}

/** 开/关 */
data class SwitchItem(
    override val id: String,
    override val title: String,
    override val subtitle: String,
    override val detail: String? = null,
    val checked: Boolean,
    val onChange: (Boolean) -> Unit,
) : SettingItem

/** 连续取值（滑块） */
data class SliderItem(
    override val id: String,
    override val title: String,
    override val subtitle: String,
    override val detail: String? = null,
    /** 行内显示、也是拖动气泡里的文本（如 "1024 MB"） */
    val valueText: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val steps: Int,
    val onChange: (Float) -> Unit,
) : SettingItem

/** 多选一（右侧一排胶囊）。用胶囊而不是开关：「文件 / 媒体库」是平级选项，没有开/关语义 */
data class ChoiceItem<T>(
    override val id: String,
    override val title: String,
    override val subtitle: String,
    override val detail: String? = null,
    val options: List<Pair<String, T>>,
    val selected: T,
    val onSelect: (T) -> Unit,
) : SettingItem

/**
 * 动作行（点一下执行）。
 *
 * [confirm] 非空则先弹二次确认 —— **破坏性操作必须给**（清空缓存这类原来点一下就真删了）。
 * [danger] 只影响文字配色（退出登录、清空），不影响是否确认。
 */
data class ActionItem(
    override val id: String,
    override val title: String,
    override val subtitle: String,
    override val detail: String? = null,
    val danger: Boolean = false,
    val confirm: String? = null,
    val confirmText: String = "确定",
    val onClick: () -> Unit,
) : SettingItem

/** 只读信息行（版本号、只读状态），不可点 */
data class InfoItem(
    override val id: String,
    override val title: String,
    override val subtitle: String,
    override val detail: String? = null,
) : SettingItem

/**
 * 设置分类 = 导航项。顺序即展示顺序。
 *
 * [blurb] 是该类顶部的一句话说明：分类名往往词不达意（"界面"到底管什么），
 * 一句话说清能省掉用户点进去再退出来的来回。
 */
enum class SettingsCategory(
    val label: String,
    val icon: ImageVector,
    val blurb: String,
) {
    PLAYER("播放器", Icons.Outlined.PlayCircle, "播放行为、手势与外挂字幕"),
    UI("界面", Icons.Outlined.Palette, "启动落在哪一页、全屏播放时右上角显示什么"),
    DOWNLOAD("下载", Icons.Outlined.CloudDownload, "链接识别与外部唤起"),
    STORAGE("存储与缓存", Icons.Outlined.FolderOpen, "各类本机缓存的开关、上限与清理"),
    LAB("实验室", Icons.Outlined.Science, "尝鲜功能，不稳定，随时可以关掉"),
    ACCOUNT("账号", Icons.Outlined.PersonOutline, "授权信息与退出登录"),
    ABOUT("关于", Icons.Outlined.Info, "版本与说明"),
}

/** 分类内的一个小节（[label] 为空表示接着上一节的条目往下排） */
data class SettingGroup(val label: String?, val items: List<SettingItem>)

data class SettingsSection(val category: SettingsCategory, val groups: List<SettingGroup>)

/**
 * 分类内容的构建器：`section(CAT) { group("小节"); switch(...) ... }`。
 *
 * 写成 DSL 而不是直接拼 list，是为了让"声明一个设置"只占一行 ——
 * 一个设置项平均要 3~5 个参数，裸构造器会把整页撑成参数墙。
 */
class SettingsSectionBuilder(private val category: SettingsCategory) {
    private val groups = mutableListOf<SettingGroup>()
    private val current = mutableListOf<SettingItem>()
    private var currentLabel: String? = null

    /** 开一个小节（带灰色小标题）；不传标题就只是收口上一节 */
    fun group(label: String? = null) {
        flush()
        currentLabel = label
    }

    fun switch(
        id: String,
        title: String,
        subtitle: String,
        detail: String? = null,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        current += SwitchItem(id, title, subtitle, detail, checked, onChange)
    }

    fun slider(
        id: String,
        title: String,
        valueText: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
        subtitle: String = "",
        detail: String? = null,
        onChange: (Float) -> Unit,
    ) {
        current += SliderItem(id, title, subtitle, detail, valueText, value, range, steps, onChange)
    }

    fun <T> choice(
        id: String,
        title: String,
        subtitle: String,
        options: List<Pair<String, T>>,
        selected: T,
        detail: String? = null,
        onSelect: (T) -> Unit,
    ) {
        current += ChoiceItem(id, title, subtitle, detail, options, selected, onSelect)
    }

    fun action(
        id: String,
        title: String,
        subtitle: String,
        detail: String? = null,
        danger: Boolean = false,
        confirm: String? = null,
        confirmText: String = "确定",
        onClick: () -> Unit,
    ) {
        current += ActionItem(id, title, subtitle, detail, danger, confirm, confirmText, onClick)
    }

    fun info(id: String, title: String, subtitle: String, detail: String? = null) {
        current += InfoItem(id, title, subtitle, detail)
    }

    fun build(): SettingsSection {
        flush()
        return SettingsSection(category, groups.toList())
    }

    private fun flush() {
        if (current.isNotEmpty()) {
            groups += SettingGroup(currentLabel, current.toList())
            current.clear()
        }
    }
}

fun section(category: SettingsCategory, block: SettingsSectionBuilder.() -> Unit): SettingsSection =
    SettingsSectionBuilder(category).apply(block).build()
