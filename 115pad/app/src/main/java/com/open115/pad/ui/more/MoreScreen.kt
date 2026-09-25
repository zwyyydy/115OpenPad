package com.open115.pad.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.ui.theme.AppCard
import com.open115.pad.ui.theme.AppColors

/** 「更多」收纳页的一个入口（手机模式）：route 对应既有导航路由 */
data class MoreEntry(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** 一行说明，让入口自解释（也为以后新增的低频功能预留） */
    val desc: String = "",
)

private val entryDesc: Map<String, String> = mapOf(
    "filter" to "文件列表的隐藏/筛选规则与方案管理",
    "rename" to "批量重命名：新建任务、查看进度与结果",
)

/**
 * 手机模式的「更多」页：底部导航放不下的低频功能都收在这里。
 * 入口清单由 AppRoot 传入（数据驱动），以后新增功能只要往 moreRoutes 里追加 route。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    entries: List<MoreEntry>,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("更多") })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { it.route }) { entry ->
                AppCard(Modifier.fillMaxWidth(), onClick = { onOpen(entry.route) }) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppColors.BlueBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                entry.icon,
                                contentDescription = null,
                                tint = AppColors.AccentDeep,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.label,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.TextPrimary,
                            )
                            if (entryDesc[entry.route] != null) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    entryDesc[entry.route]!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = AppColors.TextTertiary,
                        )
                    }
                }
            }
        }
    }
}
