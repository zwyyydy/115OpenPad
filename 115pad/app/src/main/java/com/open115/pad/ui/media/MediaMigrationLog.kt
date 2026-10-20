package com.open115.pad.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.data.media.MigrationItemStatus
import com.open115.pad.data.media.MigrationTask
import com.open115.pad.ui.components.GlassCard
import com.open115.pad.ui.components.MetaChip
import com.open115.pad.util.Format

/**
 * 「迁移记录」列表：一批一张卡 —— 迁到哪儿、共几部、成功/跳过/失败各多少、当前状态，
 * 展开能看逐部结果，还能**继续没跑完的**、**重试失败的**、删记录。
 *
 * 为什么不把这段塞进扫描记录那一页的同一个列表：两件事的数据源、字段、能做的操作都不一样，
 * 混在一个列表里每张卡都要先判类型。分成两个页签（同一页的两个列表）最省事。
 */
@Composable
internal fun MigrationLogList(
    tasks: List<MigrationTask>,
    /** 正在跑的那一批（界面上要标"进行中"，并且不给重复点"继续"） */
    activeTaskId: Long?,
    onResume: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp,
        ),
    ) {
        items(tasks, key = { it.id }) { task ->
            MigrationTaskCard(
                task = task,
                active = task.id == activeTaskId,
                onResume = { onResume(task.id) },
                onRetry = { onRetry(task.id) },
                onRemove = { onRemove(task.id) },
            )
        }
    }
}

@Composable
private fun MigrationTaskCard(
    task: MigrationTask,
    active: Boolean,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    GlassCard {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "迁到 ${task.targetLabel}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    MetaChip("进行中", tint = MaterialTheme.colorScheme.primary, bold = true)
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    Format.ago(task.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaChip(task.summaryLine)
                if (task.hasPending && !active) MetaChip("还有 ${task.items.count { it.st == MigrationItemStatus.PENDING }} 部没跑")
            }
            // 收尾那句话（"已停止，剩 N 部（迁移记录里可以继续）"、目标库被删…）
            task.note?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                task.items.forEach { item ->
                    Text(
                        "${statusMark(item.st)} ${item.title}" +
                            (item.message?.takeIf { s -> s.isNotBlank() }?.let { m -> " · $m" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.st == MigrationItemStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起明细" else "明细（${task.total}）")
                }
                Spacer(Modifier.weight(1f))
                if (task.hasFailed && !active) {
                    TextButton(onClick = onRetry) { Text("重试失败") }
                }
                if (task.hasPending && !active) {
                    TextButton(onClick = onResume) { Text("继续") }
                }
                if (!active && task.finishedAt != null) {
                    TextButton(onClick = onRemove) { Text("删除记录") }
                }
            }
        }
    }
}

/** 逐部结果的记号：用字符而不是图标 —— 一行一条，扫一眼就知道哪个失败了 */
private fun statusMark(st: MigrationItemStatus): String = when (st) {
    MigrationItemStatus.DONE -> "✓"
    MigrationItemStatus.SKIPPED -> "⊘"
    MigrationItemStatus.FAILED -> "✕"
    MigrationItemStatus.PENDING -> "…"
}
