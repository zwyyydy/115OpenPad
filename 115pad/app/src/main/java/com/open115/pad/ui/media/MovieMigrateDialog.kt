package com.open115.pad.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.open115.pad.appContainer
import com.open115.pad.data.media.MediaDao
import com.open115.pad.data.media.MediaLibraryEntity
import com.open115.pad.data.media.MigrationResult
import com.open115.pad.ui.components.FadeAlertDialog
import com.open115.pad.ui.filter.DirMultiPickerDialog
import com.open115.pad.ui.theme.AppChip
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「迁移到其他媒体库」：选目标库（多根时再选一根，还可以往下钻一个子目录）→ 确认 → 报结果。
 *
 * 两种用法：
 * - **单部**（详情页进来）：就地跑、就地报结果（`busy` → `result`）。搬完这一页就该退，交给 [onMigrated]。
 * - **批量**（海报墙多选进来）：只把任务发出去就关对话框 —— 逐条搬是长任务，进度归海报墙那张进度卡，
 *   结果归操作记录，不能拿对话框杵着用户（30 部片子一部一部等下去就是十几分钟）。
 *
 * 对话框自己拿 dao / migrator（与作品页、详情页同一个写法：从 applicationContext 取 container），
 * 这样调用方只需要一个 `if (showMigrate) MovieMigrateDialog(...)`。
 */
@Composable
fun MovieMigrateDialog(
    mediaKeys: List<String>,
    /** 单部时是片名；批量时是「已选 N 部」这类现成标题 */
    title: String,
    dao: MediaDao,
    onDismiss: () -> Unit,
    /** 索引已经认到新位置（只有单部就地跑完才会回调）：外面 +1 刷新，旧库的海报墙上这张卡应当立刻消失 */
    onMigrated: () -> Unit,
) {
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    val single = mediaKeys.size == 1
    val key = mediaKeys.firstOrNull().orEmpty()

    // 库列表 + 这部片现在挂在哪个路径下（标出"本片现在这里"，免得用户对着当前库点半天）
    val loaded by produceState<Pair<List<MediaLibraryEntity>, String>?>(null) {
        value = dao.libraries().first() to dao.movie(key)?.dirPath.orEmpty()
    }
    var targetId by remember { mutableStateOf<Long?>(null) }
    var rootIndex by remember { mutableStateOf(0) }
    /** 选中的目标子目录（相对库根的路径 + 它的 cid）；null = 就落在库根 */
    var subDirCid by remember { mutableStateOf<String?>(null) }
    var subPath by remember { mutableStateOf("") }
    var pickingDir by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MigrationResult?>(null) }

    val libraries = loaded?.first.orEmpty()
    val srcPath = loaded?.second.orEmpty()
    val target = libraries.firstOrNull { it.id == targetId }
    val currentLibraryId = libraries.firstOrNull { lib ->
        lib.rootPaths.any { p -> p.isNotBlank() && (srcPath == p || srcPath.startsWith("$p/")) }
    }?.id
    val rootPaths = target?.rootPaths.orEmpty()

    fun runSingle() {
        val t = target ?: return
        busy = true
        scope.launch {
            // 走 migrateSingle 而不是 migrateOne：前者跑完会补一条「迁移记录」
            //（单部这条链不经过 worker，不补的话记录里查不到这次迁移）
            val r = container.mediaMigrator.migrateSingle(
                mediaKey = key,
                target = t,
                rootIndex = rootIndex,
                subDirCid = subDirCid,
                subPath = subPath,
                title = title,
            )
            busy = false
            result = r
            if (r is MigrationResult.Ok) onMigrated()
        }
    }

    fun startBatch() {
        val t = target ?: return
        scope.launch {
            // 入队（落盘）之后就交给 worker：切页/退出 App 都不会断，重启还会接着跑
            container.mediaMigrator.enqueue(
                mediaKeys = mediaKeys,
                target = t,
                rootIndex = rootIndex,
                subDirCid = subDirCid,
                subPath = subPath,
            )
            onDismiss()
        }
    }

    val done = result
    FadeAlertDialog(
        // 单部就地跑的时候不给关：云端那一步已经发出去了，这时候关掉只会让用户以为撤回了
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (done == null) "迁移到其他媒体库" else "迁移结果") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when {
                    done != null -> MigrationReport(result = done)

                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text("正在搬云端文件夹并更新索引…")
                    }

                    else -> {
                        Text(
                            if (single) {
                                "把「$title」在云盘上的文件搬到目标库下 —— 视频、nfo、海报、剧照、演员头像都跟着走。" +
                                    "它独占一个文件夹时整夹搬；和别的片混在一起时，会在目标位置给它建一个文件夹再搬它自己的文件。\n\n" +
                                    "索引会随后自动更新；观影历史、续播进度、弹幕都按文件本身记着，不受影响。"
                            } else {
                                "把选中的 ${mediaKeys.size} 部影片搬到目标库下（云端真的搬）。" +
                                    "逐部进行，过程中可以在海报墙上看到进度、随时停止。\n\n" +
                                    "独占一个文件夹的整夹搬；和别的片混在一起的，会在目标位置给它单独建文件夹再搬它自己的文件。"
                            },
                        )
                        Spacer(Modifier.height(12.dp))
                        if (libraries.isEmpty()) {
                            Text("还没有别的媒体库可以搬过去")
                        } else {
                            Text("目标媒体库", style = MaterialTheme.typography.labelLarge)
                            libraries.forEach { lib ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (lib.id != targetId) {
                                                targetId = lib.id
                                                rootIndex = 0
                                                // 换了库（或换了根）之后原来的子目录就不成立了
                                                subDirCid = null
                                                subPath = ""
                                            }
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = lib.id == targetId,
                                        onClick = {
                                            if (lib.id != targetId) {
                                                targetId = lib.id
                                                rootIndex = 0
                                                subDirCid = null
                                                subPath = ""
                                            }
                                        },
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            lib.name + if (lib.id == currentLibraryId) "（现在这里）" else "",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (lib.id == targetId) FontWeight.SemiBold else null,
                                        )
                                        // 多根库把每一根都摆出来，用户得知道会落到哪儿
                                        Text(
                                            lib.rootPaths.filter { it.isNotBlank() }.joinToString(" · ")
                                                .ifBlank { "（没记根路径）" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            // 多根库：再选一根
                            if (rootPaths.size > 1) {
                                Spacer(Modifier.height(8.dp))
                                Text("目标库有多个根，搬到哪一个：", style = MaterialTheme.typography.labelLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    rootPaths.forEachIndexed { i, p ->
                                        AppChip(
                                            label = p.ifBlank { "根 $i" },
                                            selected = i == rootIndex && subPath.isBlank(),
                                            onClick = {
                                                rootIndex = i
                                                // 换了根，原来挑的子目录（相对上一根）就不成立了
                                                subDirCid = null
                                                subPath = ""
                                            },
                                        )
                                    }
                                }
                            }
                            // 目标位置：库根 / 库根下某一级子目录
                            Spacer(Modifier.height(8.dp))
                            val rootNow = rootPaths.getOrNull(rootIndex).orEmpty()
                            Text(
                                "目标位置：" + rootNow + if (subPath.isBlank()) "" else "/$subPath",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = { pickingDir = true },
                                    enabled = target != null && !busy,
                                ) { Text(if (subPath.isBlank()) "选择子目录…" else "换个子目录…") }
                                if (subPath.isNotBlank()) {
                                    TextButton(
                                        onClick = { subDirCid = null; subPath = "" },
                                        enabled = !busy,
                                    ) { Text("改回库根") }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "不支持搬的会逐部跳过并说明原因（和别的片混在同一目录、文件散在多个目录、" +
                                    "认不出文件分组等等），云端一个字节都不动。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                done != null -> TextButton(onClick = onDismiss) { Text("关闭") }
                single -> TextButton(onClick = { runSingle() }, enabled = target != null && !busy) { Text("迁移") }
                else -> TextButton(onClick = { startBatch() }, enabled = target != null) { Text("开始迁移") }
            }
        },
        dismissButton = if (done == null && !busy) {
            { TextButton(onClick = onDismiss) { Text("取消") } }
        } else {
            null
        },
    )

    if (pickingDir) {
        val root = target?.rootPaths?.getOrNull(rootIndex).orEmpty()
        DirMultiPickerDialog(
            api = container.openApi,
            title = "在「${target?.name.orEmpty()}」里选一个子目录（勾一个即可）",
            // 从目标根往下钻：回传的 fullPath 就是**相对目标根**的路径，拼 dirPath 时不会有写法冲突
            startCid = target?.rootCids?.getOrNull(rootIndex),
            startName = root.ifBlank { "库根" },
            onDismiss = { pickingDir = false },
            onConfirm = { dirs ->
                // 多选控件但这里只要一个：取最近勾的那个（列表顺序即勾选顺序）
                dirs.lastOrNull()?.let {
                    subDirCid = it.cid
                    subPath = it.fullPath.trim('/')
                }
                pickingDir = false
            },
        )
    }
}

/** 结果那一段文案：几种结果各说各的，不合并（"搬了但没落地"和"没搬"完全是两回事） */
@Composable
private fun MigrationReport(result: MigrationResult) {
    when (result) {
        is MigrationResult.Ok -> Column {
            Text("已迁移到「${result.libraryName}」", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("新位置：${result.landedPath}", style = MaterialTheme.typography.bodySmall)
            result.renamedTo?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    "目标里本来就有同名文件夹，云端把搬过来的这个改名为「$it」（不影响识别与播放）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "索引已经认到新位置，旧库里就看不到它了。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is MigrationResult.Pending -> Column {
            Text("云端已经搬好了", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "目标库的索引更新还排在队里（可能在扫别的库），扫完它自己就会换到「${result.libraryName}」。" +
                    "在它扫完之前，旧库里那张卡可能还在。",
            )
        }

        is MigrationResult.Refused -> Column {
            Text("没有搬（云盘一个字节都没动）", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(result.reason)
        }

        is MigrationResult.CloudFailed -> Column {
            Text("云端移动失败（本地没动，可以原样重试）", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(result.message)
        }
    }
}
