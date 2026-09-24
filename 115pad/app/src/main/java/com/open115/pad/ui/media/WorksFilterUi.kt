package com.open115.pad.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import com.open115.pad.data.media.FilterFacets
import com.open115.pad.data.media.MAX_ACTOR_CHOICES
import com.open115.pad.data.media.WorksFilter

/**
 * 列表的筛选按钮（表头用）：勾了几项就在图标上显示数字。
 *
 * 只画按钮，弹窗由外面按 [onOpen] 打开 —— 表头那一行不该由这里决定弹窗挂在哪。
 */
@Composable
internal fun FilterButton(filter: WorksFilter, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOpen) {
            Icon(
                Icons.Outlined.FilterList,
                contentDescription = if (filter.isEmpty) "筛选" else "筛选（已选 ${filter.selectedCount} 项）",
                tint = if (filter.isEmpty) Color.Unspecified else MaterialTheme.colorScheme.primary,
            )
        }
        if (!filter.isEmpty) {
            Text(
                "${filter.selectedCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
        }
    }
}

/**
 * 筛选弹窗：年份 / 演员 / 类型·标签 三组，**横向滑动切换**（顶部标签页跟着走）。
 *
 * - 弹窗用系统默认宽度（约 560dp）：之前试过"三列并排"，要撑到屏宽 92% 才放得下
 *   长选项（`发行: xxx`），太宽了；这里改成一次只显示一组、左右滑切换，宽度就回到正常。
 * - **点一下就生效**（不做"确定/取消"）：筛选是本地过滤，没有网络代价，即时反馈比两步确认顺手；
 *   想回退点「清除」。
 * - 选项是"这个页面里实际有的"（外面用 [com.open115.pad.data.media.facetsOf] 现算好后传进来），
 *   所以勾了必有结果，也不会出现空选项。某组一个选项都没有时那个标签页不出现。
 */
@Composable
internal fun WorksFilterDialog(
    facets: FilterFacets,
    filter: WorksFilter,
    onChange: (WorksFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("筛选", style = MaterialTheme.typography.titleMedium)
                    if (facets.actorsTruncated) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "演员只列出现最多的 $MAX_ACTOR_CHOICES 位",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onChange(WorksFilter()) },
                        enabled = !filter.isEmpty,
                    ) { Text("清除") }
                    TextButton(onClick = onDismiss) { Text("完成") }
                }

                if (facets.isEmpty) {
                    Text(
                        "这个列表里没有可筛的年份/类型/标签",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    return@Column
                }

                // 只给"真有选项"的分组建标签页：某组为空时不该占一个空标签
                val tabs = listOfNotNull(
                    TAB_YEARS.takeIf { facets.years.isNotEmpty() },
                    TAB_ACTORS.takeIf { facets.actors.isNotEmpty() },
                    TAB_KINDS.takeIf { facets.kinds.isNotEmpty() },
                )
                val pager = rememberPagerState { tabs.size }
                val scope = rememberCoroutineScope()

                TabRow(selectedTabIndex = pager.currentPage) {
                    tabs.forEachIndexed { index, label ->
                        Tab(
                            selected = pager.currentPage == index,
                            onClick = { scope.launch { pager.animateScrollToPage(index) } },
                            text = { Text(label) },
                        )
                    }
                }
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxWidth().height(FACET_PAGE_HEIGHT),
                ) { page ->
                    when (tabs[page]) {
                        TAB_YEARS -> ChipFlow(
                            options = facets.years.map { it.toString() to it },
                            selected = filter.years,
                            onToggle = { year -> onChange(filter.copy(years = filter.years.toggle(year))) },
                        )

                        TAB_ACTORS -> ChipFlow(
                            options = facets.actors.map { it to it },
                            selected = filter.actors,
                            onToggle = { actor -> onChange(filter.copy(actors = filter.actors.toggle(actor))) },
                        )

                        else -> ChipFlow(
                            options = facets.kinds.map { it to it },
                            selected = filter.kinds,
                            onToggle = { kind -> onChange(filter.copy(kinds = filter.kinds.toggle(kind))) },
                        )
                    }
                }
            }
        }
    }
}

/** 一页筛选项：自动换行 + 页内竖直滚动（标题由上面的标签页承担） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipFlow(
    options: List<Pair<String, T>>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 10.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = value in selected,
                onClick = { onToggle(value) },
                label = { Text(label, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

/** 筛选页的高度。**固定**：三组长度差着几十倍，wrap 的话滑一页高度就变一次 */
private val FACET_PAGE_HEIGHT: Dp = 300.dp

private const val TAB_YEARS = "年份"
private const val TAB_ACTORS = "演员"
private const val TAB_KINDS = "类型 · 标签"

/** 多选集合取反：勾上/取消一个 */
private fun <T> Set<T>.toggle(value: T): Set<T> =
    if (value in this) this - value else this + value
