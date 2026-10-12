package com.open115.pad.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * 全屏文本文件编辑器：顶部「取消 / 标题 / 保存」，下面一个文件名输入框 + 一个占满剩余高度的多行内容框。
 *
 * 用于「新建文本文件」和（将来）编辑已有文本文件。保存只把 name/content 交回调用方，
 * 上传/覆盖由调用方决定（新建走上传、编辑走删旧+重传），这里不碰网络。
 * 文件名不校验后缀（调用方补 .txt），只挡空白名（保存按钮置灰）。
 */
@Composable
fun TextFileEditorDialog(
    title: String,
    initialName: String,
    initialContent: String,
    saveLabel: String = "保存",
    onDismiss: () -> Unit,
    onSave: (name: String, content: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var content by remember { mutableStateOf(initialContent) }
    FadeDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxSize(),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.weight(1f))
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onSave(name.trim(), content) },
                        enabled = name.isNotBlank(),
                    ) { Text(saveLabel, fontWeight = FontWeight.SemiBold) }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("文件名") },
                    placeholder = { Text("如 笔记.txt") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                // 字数/字节数：UTF-8 下一个汉字 3 字节，给个直观的量感（文本都是 KB 级，远不到 32MB 上限）
                val bytes = content.toByteArray(Charsets.UTF_8).size
                Text(
                    "$bytes 字节 · UTF-8",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
