package com.open115.pad.ui.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.open115.pad.ui.components.FadeAlertDialog

/**
 * 批量删除的确认框：与单部**同一套两段式**（先"仅移除"，要彻底删得再点一次），
 * 只是文案得说清是三件事：删的是几部、彻底删除不可逆、过程可停。
 *
 * 为什么不复用 [MovieDeleteDialog]：那边标题里带片名与集数，这里带的是数量；
 * 措辞的分叉比多传两个参数的 if 更好读。
 */
@Composable
fun MovieBatchDeleteDialog(
    count: Int,
    onDismiss: () -> Unit,
    onDelete: (alsoCloud: Boolean) -> Unit,
) {
    var confirmingCloud by remember { mutableStateOf(false) }

    if (!confirmingCloud) {
        FadeAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("删除选中的 $count 部作品") },
            text = {
                Column {
                    Text("仅从媒体库移除：只删本机索引与海报缓存，云盘文件一个不动。之后重扫还会回来。")
                    Spacer(Modifier.height(10.dp))
                    // 同单部：只陈述"会删云端文件"，不替 115 承诺回收站，也不用"无法恢复"吓唬人
                    Text("彻底删除：连云端的视频、同名 .nfo 与海报/背景图一起删。请确认不再需要。")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "逐部进行，过程中可以停止；结果在操作记录里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onDelete(false) }) { Text("仅移除") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = { confirmingCloud = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("彻底删除") }
                }
            },
        )
    } else {
        FadeAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("彻底删除选中的 $count 部作品？") },
            text = {
                Text(
                    "云端的视频、同名 .nfo 与海报/背景图会被逐部删除，请确认不再需要。\n\n" +
                        "只删文件、不删目录：全部删完可能剩下空目录，需要的话在文件页里自己删。\n\n" +
                        "每一部都是「云端删成功之后才删本机索引」 —— 某部云端失败，那一部原样保留。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(true) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("确认彻底删除") }
            },
            dismissButton = { TextButton(onClick = { confirmingCloud = false }) { Text("返回") } },
        )
    }
}

/**
 * 单部删除的确认框，两种模式：
 *
 * - **仅从媒体库移除**：只删本机索引与海报/nfo 缓存，云盘文件一个不动（可逆 —— 重扫就回来）
 * - **彻底删除**：连云端的视频、同名 .nfo 与海报/背景/缩略图一起删（**不可逆**，所以要二次确认）
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
    onDelete: (alsoCloud: Boolean) -> Unit,
) {
    var confirmingCloud by remember { mutableStateOf(false) }
    val scope = if (episodeCount > 0) "（连同名下的 $episodeCount 集）" else ""

    if (!confirmingCloud) {
        FadeAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("删除「$name」$scope") },
            text = {
                Column {
                    Text("仅从媒体库移除：只删本机索引与海报缓存，云盘文件一个不动。之后重扫还会回来。")
                    Spacer(Modifier.height(10.dp))
                    // 不写"无法恢复"：115 的删除一般会先进回收站（本应用自己就有回收站页），
                    // 但那是它的行为、不是我们的承诺 —— 措辞不替它担保，也不吓唬用户
                    Text("彻底删除：连云端的视频、同名 .nfo 与海报/背景图一起删。请确认不再需要。")
                }
            },
            confirmButton = {
                TextButton(onClick = { onDelete(false) }) { Text("仅移除") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = { confirmingCloud = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("彻底删除") }
                }
            },
        )
    } else {
        FadeAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("彻底删除「$name」$scope？") },
            text = {
                Text(
                    "云端的视频、同名 .nfo 与海报/背景图会被删除，请确认不再需要。\n\n" +
                        "只删文件、不删目录：全部删完可能剩一个空目录，需要的话在文件页里自己删。\n\n" +
                        "云端删成功之后才会删本机索引 —— 云端失败的话条目原样保留，可以直接重试。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(true) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("确认彻底删除") }
            },
            dismissButton = { TextButton(onClick = { confirmingCloud = false }) { Text("返回") } },
        )
    }
}
