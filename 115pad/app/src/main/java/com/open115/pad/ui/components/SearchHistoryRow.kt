package com.open115.pad.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.open115.pad.ui.theme.AppChip

/**
 * 搜索框下方的「最近搜索」：文件页 / 媒体库共用。
 *
 * 只在搜索词为空时显示（有输入时应该让位给结果）。点一条直接用它再搜一次；
 * 末尾「清空」清掉本作用域的历史（不影响另一个页面）。
 */
@Composable
fun SearchHistoryRow(
    history: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "最近",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        history.take(8).forEach { q ->
            AppChip(
                // 长关键词只截显示文本，点进去用的是完整 query
                label = if (q.length <= 16) q else q.take(16) + "…",
                selected = false,
                onClick = { onPick(q) },
            )
        }
        TextButton(onClick = onClear) { Text("清空") }
    }
}
