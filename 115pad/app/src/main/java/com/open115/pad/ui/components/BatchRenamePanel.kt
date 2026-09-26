package com.open115.pad.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.open115.pad.data.FileItem
import com.open115.pad.data.RenameQueue
import com.open115.pad.data.RenameSpeed
import com.open115.pad.data.RenameTask
import com.open115.pad.data.RenameTaskItem
import com.open115.pad.ui.files.BatchRenameRequest
import com.open115.pad.ui.files.FindPattern
import com.open115.pad.ui.files.InsertKind
import com.open115.pad.ui.files.InsertPosition
import com.open115.pad.ui.files.NumberConfig
import com.open115.pad.ui.files.NumberKind
import com.open115.pad.ui.files.RenameConfig
import com.open115.pad.ui.files.RenameMode
import com.open115.pad.ui.files.RenameRule
import com.open115.pad.ui.files.computeNewNames
import com.open115.pad.ui.files.duplicateIndices
import com.open115.pad.ui.files.nameError
import com.open115.pad.ui.theme.AppColors
import com.open115.pad.ui.theme.AppChip
import com.open115.pad.ui.theme.AppCheckbox
import com.open115.pad.ui.theme.KindBadge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 批量重命名面板：**配置 + 入队**，不在这里跑。
 *
 * 执行交给 [RenameQueueWorker]，所以点完「执行重命名」就可以直接关面板走人 ——
 * 进度在「重命名」任务页看，关面板/切页/进程被杀都不影响任务继续跑。
 *
 * 由调用方渲染在**内容区之内**（与 NavHost 同层），所以左侧导航栏仍然可见 ——
 * 批量改名时经常要换目录挑文件，把导航藏掉反而挡路。
 * 刻意不用 Dialog：Dialog 窗口会被侧栏宽度内缩，在平板布局下位置和尺寸都不对。
 *
 * @param scope 入队等轻量操作用；真正的执行在 AppContainer 的队列 worker 上
 */
@Composable
fun BatchRenamePanel(
    request: BatchRenameRequest,
    queue: RenameQueue,
    scope: CoroutineScope,
    onGotoTasks: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = request.items
    if (selected.isEmpty()) return
    BackHandler { onDismiss() }

    /** 参与改名的行：初始是文件页选中的那些，行内可移除、也可一键换成整个目录 */
    var rows by remember(selected) { mutableStateOf(selected) }
    var config by remember(selected) { mutableStateOf(RenameConfig()) }
    var speed by remember { mutableStateOf(RenameSpeed.NORMAL) }
    /** 是否已把整个目录放进来了，决定「全选本目录」按钮的文案与行为 */
    var expandedToDir by remember(selected) { mutableStateOf(false) }
    /** 已入队的任务 id：非空即进入"已提交"状态 */
    var queuedTaskId by remember { mutableStateOf<Long?>(null) }

    val previews = remember(rows, config) { computeNewNames(rows, config) }
    val errors = remember(previews) { previews.map { nameError(it.newName) } }
    val dups = remember(previews) { duplicateIndices(previews) }
    /** 有问题的行：名字非法，或与批内另一行撞名 */
    val blocked = remember(errors, dups) {
        errors.indices.filter { errors[it] != null || it in dups }.toSet()
    }
    val runnable = previews.filterIndexed { i, p -> p.changed && i !in blocked }

    // 入队后仍然实时显示这个任务的进度（队列是 StateFlow，等于白拿）——
    // 小批量时用户就在这一屏看着它跑完，不用特意跳去任务页
    val allTasks by queue.tasks.collectAsState()
    val queuedTask = queuedTaskId?.let { id -> allTasks.firstOrNull { it.id == id } }
    val queuedPosition = queuedTask?.let { t ->
        allTasks.filter { it.finishedAt == null && !it.paused && it.hasPending }
            .sortedBy { it.createdAt }
            .indexOfFirst { it.id == t.id }
            .takeIf { it >= 0 }
            ?.plus(1)
    }

    // 全屏覆盖面板：实底（scheme 的 surface 在壁纸激活时是半透明的，会透出底下的重命名页）
    Surface(Modifier.fillMaxSize(), color = AppColors.Card) {
        Column(Modifier.fillMaxSize()) {
            TopBar(onDismiss)

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                item { ModeChips(config) { config = config.copy(mode = it) } }
                item { FormArea(config, rows.size) { config = it } }
                item {
                    PreviewHeader(
                        runnable = runnable.size,
                        blocked = blocked.size,
                        total = rows.size,
                        expandedToDir = expandedToDir,
                        // 整目录和已选一样多时这个按钮没意义，不显示
                        canExpand = request.allFiles.size > selected.size,
                        onToggleAll = {
                            expandedToDir = !expandedToDir
                            rows = if (expandedToDir) request.allFiles else selected
                        },
                    )
                }
                items(previews, key = { (it.item.fid ?: "") + "#" + it.oldName }) { p ->
                    val index = previews.indexOf(p)
                    PreviewRow(
                        oldName = p.oldName,
                        newName = p.newName,
                        isDir = p.item.isDir,
                        changed = p.changed,
                        error = errors.getOrNull(index) ?: if (index in dups) "与批内其他行重名" else null,
                        // 手动编辑模式下这一行本身就是输入框
                        manual = if (config.mode == RenameMode.MANUAL) {
                            {
                                val key = p.item.fid.orEmpty()
                                ManualField(
                                    value = config.manualByFid[key] ?: p.oldName,
                                    onChange = { next -> config = config.copy(manualByFid = config.manualByFid + (key to next)) },
                                    onMultiLine = { lines ->
                                        // 粘贴多行（比如从 Excel 复制的一列）：从当前行开始往下填
                                        val start = index
                                        val updated = HashMap(config.manualByFid)
                                        lines.forEachIndexed { k, line ->
                                            val target = previews.getOrNull(start + k) ?: return@forEachIndexed
                                            updated[target.item.fid.orEmpty()] = line
                                        }
                                        config = config.copy(manualByFid = updated)
                                    },
                                )
                            }
                        } else null,
                        onRemove = { rows = rows.filterNot { it.fid == p.item.fid } },
                    )
                }
            }

            BottomBar(
                totalCount = rows.size,
                runnableCount = runnable.size,
                speed = speed,
                onSpeed = { speed = it },
                queued = queuedTask,
                queuedPosition = queuedPosition,
                onExecute = {
                    scope.launch {
                        val id = queue.enqueue(
                            items = runnable.map {
                                RenameTaskItem(
                                    fid = it.item.fid.orEmpty(),
                                    oldName = it.oldName,
                                    newName = it.newName,
                                )
                            },
                            cid = request.cid,
                            dirName = request.dirName,
                            intervalMs = speed.intervalMs,
                        )
                        if (id != null) queuedTaskId = id
                    }
                },
                onGotoTasks = onGotoTasks,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun TopBar(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            "批量重命名",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
        )
    }
}

@Composable
private fun ModeChips(config: RenameConfig, onPick: (RenameMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RenameMode.entries.forEach { mode ->
            AppChip(mode.label, config.mode == mode, onClick = { onPick(mode) })
        }
    }
}

@Composable
private fun FormArea(config: RenameConfig, rowCount: Int, onChange: (RenameConfig) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        when (config.mode) {
            RenameMode.FIND_REPLACE -> FindReplaceForm(config, onChange)
            RenameMode.INSERT -> InsertForm(config, rowCount, onChange)
            RenameMode.AUTO_NUMBER -> NumberConfigForm(config.number, rowCount) { onChange(config.copy(number = it)) }
            RenameMode.MANUAL -> Text(
                "提示：粘贴多行文本（如从 Excel 复制的一列），会从当前行开始自动向下填充",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

// ---------------- 查找替换 ----------------

@Composable
private fun FindReplaceForm(config: RenameConfig, onChange: (RenameConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        config.rules.forEachIndexed { i, rule ->
            RuleEditor(
                rule = rule,
                removable = config.rules.size > 1,
                onRule = { next ->
                    onChange(config.copy(rules = config.rules.toMutableList().also { it[i] = next }))
                },
                onRemove = {
                    onChange(config.copy(rules = config.rules.filterIndexed { k, _ -> k != i }))
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(config.copy(rules = config.rules + RenameRule())) }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加替换规则", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppCheckbox(config.includeExt, onCheckedChange = { onChange(config.copy(includeExt = !config.includeExt)) })
                Spacer(Modifier.width(4.dp))
                Text(
                    "替换文件扩展名",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun RuleEditor(
    rule: RenameRule,
    removable: Boolean,
    onRule: (RenameRule) -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(AppColors.GraySoft, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 匹配方式：七种「选一段」的写法都收在这个下拉里
        Box {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.clickable { menuOpen = true },
            ) {
                Text(
                    rule.pattern.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                FindPattern.entries.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.label) },
                        onClick = {
                            // 换成字面量方式时清掉「某字符」，否则会拿旧字符继续圈区间
                            onRule(rule.copy(pattern = p, find = if (p.usesChar) rule.find else ""))
                            menuOpen = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = rule.find,
            onValueChange = { onRule(rule.copy(find = it)) },
            label = { Text(if (rule.pattern.usesChar) "某字符" else "查找") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (rule.pattern.usesN || rule.pattern.usesX) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rule.pattern.usesX) {
                    IntField("第 X 位", rule.x, Modifier.weight(1f)) { onRule(rule.copy(x = it)) }
                }
                if (rule.pattern.usesN) {
                    IntField("N 位", rule.n, Modifier.weight(1f)) { onRule(rule.copy(n = it)) }
                }
            }
        }

        OutlinedTextField(
            value = rule.replaceWith,
            onValueChange = { onRule(rule.copy(replaceWith = it)) },
            label = { Text("替换成") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (removable) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = AppColors.RedFg,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("删除这条规则", style = MaterialTheme.typography.labelMedium, color = AppColors.RedFg)
                }
            }
        }
    }
}

// ---------------- 插入内容 ----------------

@Composable
private fun InsertForm(config: RenameConfig, rowCount: Int, onChange: (RenameConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledChips("内容", InsertKind.entries.map { it.label }, InsertKind.entries.indexOf(config.insertKind)) {
            onChange(config.copy(insertKind = InsertKind.entries[it]))
        }
        LabeledChips("位置", InsertPosition.entries.map { it.label }, InsertPosition.entries.indexOf(config.insertPos)) {
            onChange(config.copy(insertPos = InsertPosition.entries[it]))
        }

        when (config.insertKind) {
            InsertKind.TEXT -> OutlinedTextField(
                value = config.insertText,
                onValueChange = { onChange(config.copy(insertText = it)) },
                label = { Text("要插入的文本") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            InsertKind.FILE_INFO -> Text(
                "插入原文件名（不含扩展名）",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )

            InsertKind.NUMBER -> NumberConfigForm(config.number, rowCount) { onChange(config.copy(number = it)) }
        }

        if (config.insertPos == InsertPosition.AT) {
            IntField("插入位置（第几位之后，从 0 起）", config.insertAt, Modifier.fillMaxWidth()) {
                onChange(config.copy(insertAt = it))
            }
        }
    }
}

// ---------------- 自动编号 / 序号参数（两处共用） ----------------

@Composable
private fun NumberConfigForm(cfg: NumberConfig, rowCount: Int, onChange: (NumberConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField("开始序号", cfg.start, Modifier.weight(1f)) { onChange(cfg.copy(start = it)) }
            IntField("固定位数（0 = 不补零）", cfg.pad, Modifier.weight(1f)) { onChange(cfg.copy(pad = it)) }
        }

        var kindMenu by remember { mutableStateOf(false) }
        Box {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.clickable { kindMenu = true },
            ) {
                Text(
                    "序号类型：${cfg.kind.label}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                NumberKind.entries.forEach { k ->
                    DropdownMenuItem(
                        text = { Text(k.label) },
                        onClick = {
                            onChange(cfg.copy(kind = k))
                            kindMenu = false
                        },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = cfg.prefix,
                onValueChange = { onChange(cfg.copy(prefix = it)) },
                label = { Text("序号前面字符") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = cfg.suffix,
                onValueChange = { onChange(cfg.copy(suffix = it)) },
                label = { Text("序号后面字符") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            "共 $rowCount 个文件，编号按预览列表从上到下递增",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextTertiary,
        )
    }
}

@Composable
private fun LabeledChips(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, text ->
                AppChip(text, selected == i, onClick = { onPick(i) })
            }
        }
    }
}

// ---------------- 预览列表 ----------------

@Composable
private fun PreviewHeader(
    runnable: Int,
    blocked: Int,
    total: Int,
    expandedToDir: Boolean,
    canExpand: Boolean,
    onToggleAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append("共 $total 个，将重命名 $runnable 个")
                    if (blocked > 0) append("，$blocked 个有问题已跳过")
                    val skipped = total - runnable - blocked
                    if (skipped > 0) append("，$skipped 个名字未变")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (blocked > 0) AppColors.RedFg else AppColors.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            // 批量改名最常见的用法就是"整个目录一起改"，强迫用户先回文件页一个个勾选没必要。
            // 做成切换而不是单向：点错了要能退回原来的选择范围。
            if (canExpand) {
                TextButton(onClick = onToggleAll) {
                    Text(
                        if (expandedToDir) "恢复为已选" else "全选本目录",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(
    oldName: String,
    newName: String,
    isDir: Boolean,
    changed: Boolean,
    error: String?,
    manual: (@Composable () -> Unit)?,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(oldName, isDir, Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                oldName,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (manual != null) {
                manual()
            } else {
                Text(
                    newName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        error != null -> AppColors.RedFg
                        changed -> AppColors.AccentDeep
                        else -> AppColors.TextTertiary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.labelSmall, color = AppColors.RedFg)
                }
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "从本次重命名中移除",
                tint = AppColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun ManualField(value: String, onChange: (String) -> Unit, onMultiLine: (List<String>) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            if (text.contains('\n')) {
                // 粘贴进来的是多行（Excel 一列）：按行往下分发，而不是塞进这一格
                val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isNotEmpty()) onMultiLine(lines)
            } else {
                onChange(text)
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}

// ---------------- 底部执行条 ----------------

@Composable
private fun BottomBar(
    totalCount: Int,
    runnableCount: Int,
    speed: RenameSpeed,
    onSpeed: (RenameSpeed) -> Unit,
    queued: RenameTask?,
    queuedPosition: Int?,
    onExecute: () -> Unit,
    onGotoTasks: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(color = AppColors.Card, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (queued != null) {
                // 已提交：任务归队列管，这一屏只是"回执"。关掉面板任务照跑，
                // 所以这里给的是"去任务页"而不是"取消"（要取消得去任务页）。
                val done = queued.finishedAt != null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!done) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when {
                            done && queued.cancelled -> "已取消：成功 ${queued.succeeded}，失败 ${queued.failed}"
                            done -> buildString {
                                append("已完成：成功 ${queued.succeeded}")
                                if (queued.failed > 0) append("，失败 ${queued.failed}")
                            }
                            queuedPosition != null && queuedPosition > 1 -> "已加入队列：排在第 $queuedPosition 位"
                            else -> "已开始重命名…"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (queued.failed > 0) AppColors.RedFg else AppColors.TextPrimary,
                    )
                }
                if (!done) {
                    Text(
                        "${queued.doneCount}/${queued.total} · 成功 ${queued.succeeded} 失败 ${queued.failed}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    LinearProgressIndicator(
                        progress = { if (queued.total > 0) queued.doneCount.toFloat() / queued.total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp),
                    )
                }
                queued.note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.AmberFg,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onGotoTasks) { Text("去任务页") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("速度", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    Spacer(Modifier.width(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RenameSpeed.entries.forEach { s ->
                            AppChip(s.label, speed == s, onClick = { onSpeed(s) })
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (runnableCount > 0) "将重命名 $runnableCount 个（已选 $totalCount 个）"
                        else "已选 $totalCount 个文件",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppColors.TextPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onExecute,
                        enabled = runnableCount > 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.Accent,
                            contentColor = Color.White,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("执行重命名", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * 整数字段。
 *
 * 刻意用一个 String 中转：直接把 Int 绑上去的话，用户清空输入框会被立刻改回 0/1，
 * 光标乱跳、根本没法把 12 改成 3。
 */
@Composable
private fun IntField(label: String, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(6)
            text = digits
            if (digits.isNotEmpty()) onChange(digits.toInt())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
