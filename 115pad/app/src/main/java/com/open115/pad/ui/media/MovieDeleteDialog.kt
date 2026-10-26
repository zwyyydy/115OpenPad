package com.open115.pad.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.open115.pad.ui.components.FadeAlertDialog

/**
 * 媒体库删除的三种模式。语义与**文件页同一套**（用户定的统一口径）：
 *
 * - [LibraryOnly]「仅移除媒体库」：只删本机索引与缓存，云盘一个不动（原来叫"仅移除"）
 * - [Delete]「删除」：云端文件进回收站，**不清回收站**，可在回收站还原
 * - [Purge]「彻底删除」：删除 + **清掉对应的回收站项**，不可恢复
 */
enum class MovieDeleteMode { LibraryOnly, Delete, Purge }

/** 单个选项的展示文案（单部/批量两个弹窗共用一份，措辞分叉比多传参数更难对齐） */
private data class MovieDeleteChoice(val mode: MovieDeleteMode, val label: String, val hint: String)

private val MOVIE_DELETE_CHOICES = listOf(
    MovieDeleteChoice(
        MovieDeleteMode.LibraryOnly, "仅移除媒体库",
        "只删本机索引与海报缓存，云盘文件一个不动（重扫会回来）",
    ),
    MovieDeleteChoice(
        MovieDeleteMode.Delete, "删除",
        "云端文件进回收站，可在回收站还原",
    ),
    MovieDeleteChoice(
        MovieDeleteMode.Purge, "彻底删除",
        "云端删除并清掉对应的回收站项，不可恢复",
    ),
)

/** 一行选项：单选钮 + 动作名 + 一行小字说明（三个选项摆在一起才看得出差别在哪） */
@Composable
private fun DeleteModeRow(
    choice: MovieDeleteChoice,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(choice.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                choice.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 选中"彻底删除"时确认键变红、文字带省略号（它还要过一道二次确认，见各弹窗的 stage 2） */
@Composable
private fun DeleteConfirmButton(mode: MovieDeleteMode, onConfirm: (MovieDeleteMode) -> Unit) {
    TextButton(
        onClick = { onConfirm(mode) },
        colors = if (mode == MovieDeleteMode.Purge) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) {
        Text(if (mode == MovieDeleteMode.Purge) "彻底删除…" else "确定")
    }
}

/**
 * 批量删除的确认框：与单部**同一个三选一**，只是标题带数量、彻底删除过二道确认，
 * 文案点明"逐部进行、可停"。
 *
 * 为什么不复用 [MovieDeleteDialog]：那边标题里带片名与集数，这里带的是数量；
 * 措辞的分叉比多传两个参数的 if 更好读。
 */
@Composable
fun MovieBatchDeleteDialog(
    count: Int,
    onDismiss: () -> Unit,
    onDelete: (MovieDeleteMode) -> Unit,
) {
    var mode by remember { mutableStateOf(MovieDeleteMode.Delete) }
    var confirmingPurge by remember { mutableStateOf(false) }

    if (!confirmingPurge) {
        FadeAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("删除选中的 $count 部作品") },
            text = {
                Column {
                    MOVIE_DELETE_CHOICES.forEach { c ->
                        DeleteModeRow(c, selected = mode == c.mode) { mode = c.mode }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "逐部进行，过程中可以停止；结果在操作记录里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { DeleteConfirmButton(mode) { m -> if (m == MovieDeleteMode.Purge) confirmingPurge = true else onDelete(m) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    } else {
        FadeAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("彻底删除选中的 $count 部作品？") },
            text = {
                Text(
                    "「彻底删除」= 删除 + 清掉对应的回收站项：云端的视频、同名 .nfo、海报/背景图" +
                        "与删空目录里的剧照/头像/花絮预告一起清，不可恢复。\n\n" +
                        "只删文件、不删目录：全部删完可能剩下空目录，需要的话在文件页里自己删。\n\n" +
                        "每一部都是「云端删成功之后才删本机索引」 —— 某部云端失败，那一部原样保留。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(MovieDeleteMode.Purge) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("确认彻底删除") }
            },
            dismissButton = { TextButton(onClick = { confirmingPurge = false }) { Text("返回") } },
        )
    }
}

/**
 * 单部删除的确认框，三种模式（语义与文件页统一，见 [MovieDeleteMode]）：
 *
 * - **仅移除媒体库**：只删本机索引与海报/nfo 缓存，云盘文件一个不动（可逆 —— 重扫就回来）
 * - **删除**：云端文件进回收站（可还原，不清回收站）
 * - **彻底删除**：云端删除 + 清掉对应的回收站项（**不可逆**，所以要二次确认）
 *
 * 二次确认不是走过场：这条路上的误触代价是云盘文件没了，而重扫救不回来。
 *
 * [episodeCount] > 0 时（系列卡）文案会点明"连同名下的 N 集"—— 系列卡删的就是整季，
 * 不写清楚的话用户以为只删了那张卡。
 */
@Composable
fun MovieDeleteDialog(
    name: String,
    episodeCount: Int,
    onDismiss: () -> Unit,
    onDelete: (MovieDeleteMode) -> Unit,
) {
    var mode by remember { mutableStateOf(MovieDeleteMode.Delete) }
    var confirmingPurge by remember { mutableStateOf(false) }
    val scope = if (episodeCount > 0) "（连同名下的 $episodeCount 集）" else ""

    if (!confirmingPurge) {
        FadeAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("删除「$name」$scope") },
            text = {
                Column {
                    MOVIE_DELETE_CHOICES.forEach { c ->
                        DeleteModeRow(c, selected = mode == c.mode) { mode = c.mode }
                    }
                }
            },
            confirmButton = { DeleteConfirmButton(mode) { m -> if (m == MovieDeleteMode.Purge) confirmingPurge = true else onDelete(m) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    } else {
        FadeAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("彻底删除「$name」$scope？") },
            text = {
                Text(
                    "「彻底删除」= 删除 + 清掉对应的回收站项：云端的视频、同名 .nfo、海报/背景图" +
                        "与删空目录里的剧照/头像/花絮预告一起清，不可恢复。\n\n" +
                        "只删文件、不删目录：全部删完可能剩一个空目录，需要的话在文件页里自己删。\n\n" +
                        "云端删成功之后才会删本机索引 —— 云端失败的话条目原样保留，可以直接重试。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(MovieDeleteMode.Purge) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("确认彻底删除") }
            },
            dismissButton = { TextButton(onClick = { confirmingPurge = false }) { Text("返回") } },
        )
    }
}
