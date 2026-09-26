package com.open115.pad.ui.filter

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open115.pad.AppContainer
import com.open115.pad.data.FilterPrefs
import com.open115.pad.data.FilterRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.compose.ui.draw.clip

/**
 * 过滤方案管理 VM：方案的增删改查直写 DataStore（整存整取 JSON 列表）。
 */
class FilterRulesViewModel(private val prefs: FilterPrefs) : ViewModel() {

    private val _schemes = MutableStateFlow(emptyList<FilterRules.FilterScheme>())
    val schemes = _schemes.asStateFlow()

    init {
        viewModelScope.launch { prefs.schemes.collect { _schemes.value = it } }
    }

    private fun persist(next: List<FilterRules.FilterScheme>) {
        _schemes.value = next
        viewModelScope.launch { prefs.setSchemes(next) }
    }

    fun upsert(scheme: FilterRules.FilterScheme) = persist(
        _schemes.value.filterNot { it.id == scheme.id } + scheme,
    )

    fun remove(id: String) = persist(_schemes.value.filterNot { it.id == id })

    fun setEnabled(id: String, enabled: Boolean) = persist(
        _schemes.value.map { if (it.id == id) it.copy(enabled = enabled) else it },
    )
}

/**
 * 入口 A：规则配置中心。
 * 方案列表（启用开关 / 编辑 / 删除）→ 点新建或编辑进入条件树编辑器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterRulesScreen(container: AppContainer) {
    val vm: FilterRulesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        initializer = { FilterRulesViewModel(container.filterPrefs) },
    )
    val schemes by vm.schemes.collectAsState()
    var editing by remember { mutableStateOf<FilterRules.FilterScheme?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FilterRules.FilterScheme?>(null) }

    // 编辑器是全屏覆盖：返回键先退回方案列表，而不是退出应用
    androidx.activity.compose.BackHandler(enabled = editing != null || isNew) {
        editing = null
        isNew = false
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("过滤规则") })
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { isNew = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("新建方案") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "方案按列表顺序解析；同一目录命中多个方案时取靠前的。子目录未单独绑定时就近继承父级方案。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            if (schemes.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有方案\n点右下角新建一条，再绑定生效目录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                // 宽屏自适应双列卡片网格（minSize 380dp：手机 1 列、平板 2 列），内容居中防拉伸
                com.open115.pad.ui.theme.AdaptiveCardGrid(
                    items = schemes,
                    key = { it.id },
                    modifier = Modifier.weight(1f),
                ) { scheme ->
                    SchemeCard(
                        scheme = scheme,
                        onToggle = { vm.setEnabled(scheme.id, !scheme.enabled) },
                        onEdit = {
                            isNew = false
                            editing = scheme
                        },
                        onDelete = { deleteTarget = scheme },
                    )
                }
            }
        }
    }

    // 编辑器 = 全屏覆盖层，渲染在外层 Scaffold 之外（否则其顶栏会被外层顶栏盖住，保存按钮点不到）。
    // editing 非空 = 编辑已有方案；isNew = 新建。两态互斥，保存/返回时一并清零。
    if (editing != null || isNew) {
        SchemeEditor(
            initial = editing ?: FilterRules.FilterScheme(
                id = UUID.randomUUID().toString(),
                name = "",
            ),
            isNew = isNew,
            api = container.openApi,
            onSave = { scheme ->
                vm.upsert(scheme)
                editing = null
                isNew = false
            },
            onBack = {
                editing = null
                isNew = false
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除方案") },
            text = { Text("确定删除「${target.name.ifBlank { "未命名方案" }}」？") },
            confirmButton = {
                Button(onClick = {
                    vm.remove(target.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SchemeCard(
    scheme: FilterRules.FilterScheme,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val C = com.open115.pad.ui.theme.AppColors
    com.open115.pad.ui.theme.AppCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 顶部：规则彩色微图标 + 名称加粗 + 圆润开关
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(C.AccentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = C.AccentDeep,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                scheme.name.ifBlank { "未命名方案" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = scheme.enabled, onCheckedChange = { onToggle() })
        }

        // 中部：配置状态彩色徽章行
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (scheme.scope == FilterRules.Scope.GLOBAL) {
                com.open115.pad.ui.theme.StatusBadge("全局生效", C.BlueBg, C.BlueFg)
            } else {
                com.open115.pad.ui.theme.StatusBadge("绑定 ${scheme.dirs.size} 个目录", C.PurpleBg, C.PurpleFg)
            }
            com.open115.pad.ui.theme.StatusBadge(
                if (scheme.group.mode == FilterRules.Combinator.ALL) "AND 逻辑" else "OR 逻辑",
                C.SlateBg, C.SlateFg,
            )
            com.open115.pad.ui.theme.StatusBadge(
                "${countConditions(scheme.group)} 个条件",
                C.GreenBg, C.GreenFg,
            )
            com.open115.pad.ui.theme.StatusBadge(
                when (scheme.group.action) {
                    FilterRules.FilterAction.KEEP_MATCHED -> "保留命中"
                    FilterRules.FilterAction.EXCLUDE_MATCHED -> "排除命中"
                },
                C.AmberBg, C.AmberFg,
            )
        }

        // 底部：右对齐的 [编辑]（浅灰底）与 [删除]（淡红底）
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(
                onClick = onEdit,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = C.GraySoft,
                    contentColor = C.TextPrimary,
                ),
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null, Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("编辑", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onDelete,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = C.RedBg,
                    contentColor = C.RedFg,
                ),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("删除", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 递归统计条件树中的叶子条件数 */
private fun countConditions(group: FilterRules.RuleGroup): Int =
    group.conditions.size + group.groups.sumOf { countConditions(it) }

// ---------------- 方案编辑器（条件树 + 生效范围） ----------------

private const val MAX_TREE_DEPTH = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchemeEditor(
    initial: FilterRules.FilterScheme,
    isNew: Boolean,
    api: com.open115.pad.data.OpenApi,
    onSave: (FilterRules.FilterScheme) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(initial.name) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var group by remember { mutableStateOf(initial.group) }
    var scope by remember { mutableStateOf(initial.scope) }
    var dirs by remember { mutableStateOf(initial.dirs) }
    var pickerOpen by remember { mutableStateOf(false) }

    Scaffold(
        // 全屏覆盖在方案列表之上：容器必须实底（默认 background 在壁纸激活时是半透明的，
        // 会让底下的列表和壁纸全透出来，文字叠印）
        containerColor = com.open115.pad.ui.theme.LightAppPalette.bg,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建方案" else "编辑方案") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onSave(initial.copy(name = name.trim(), enabled = enabled, group = group, scope = scope, dirs = dirs)) },
                        enabled = name.isNotBlank(),
                    ) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("方案名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = { enabled = it })
                Spacer(Modifier.width(8.dp))
                Text("启用该方案", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()

            // 条件树
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("条件树", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    "可嵌套 ${MAX_TREE_DEPTH} 层",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            GroupEditor(
                group = group,
                depth = 1,
                onChange = { group = it },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()

            // 生效范围
            Text(
                "生效范围",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = scope == FilterRules.Scope.GLOBAL,
                    onClick = { scope = FilterRules.Scope.GLOBAL },
                )
                Text("全局默认（未单独绑定的目录都走它）")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = scope == FilterRules.Scope.DIRS,
                    onClick = { scope = FilterRules.Scope.DIRS },
                )
                Text("绑定到指定目录（子目录就近继承）")
            }

            if (scope == FilterRules.Scope.DIRS) {
                Spacer(Modifier.height(8.dp))
                // 已绑定目录：芯片 + ✕
                if (dirs.isEmpty()) {
                    Text(
                        "还没绑定目录，点下面按钮可视化选择",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(dirs) { _, dir ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Row(
                                Modifier.padding(start = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    Text(
                                        dir.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Text(
                                        dir.fullPath,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                IconButton(onClick = { dirs = dirs.filterNot { it.cid == dir.cid } }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "移除 ${dir.name}",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { pickerOpen = true }) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加目录")
                }
            }
        }
    }

    if (pickerOpen) {
        DirMultiPickerDialog(
            api = api,
            onDismiss = { pickerOpen = false },
            onConfirm = { picked ->
                // 合并去重（同一 cid 只保留一条，保留新选择的 fullPath）
                dirs = (dirs + picked).distinctBy { it.cid }
                pickerOpen = false
            },
        )
    }
}

/**
 * 递归条件组编辑器：模式(AND/OR) + 动作(保留/排除) + 条件行 + 子组。
 * depth 限制嵌套层数，避免 UI 无限套娃。
 */
@Composable
private fun GroupEditor(
    group: FilterRules.RuleGroup,
    depth: Int,
    onChange: (FilterRules.RuleGroup) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (depth % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                com.open115.pad.ui.theme.AppChip(
                    label = "且 (AND)",
                    selected = group.mode == FilterRules.Combinator.ALL,
                    onClick = { onChange(group.copy(mode = FilterRules.Combinator.ALL)) },
                )
                com.open115.pad.ui.theme.AppChip(
                    label = "或 (OR)",
                    selected = group.mode == FilterRules.Combinator.ANY,
                    onClick = { onChange(group.copy(mode = FilterRules.Combinator.ANY)) },
                )
                Spacer(Modifier.width(4.dp))
                com.open115.pad.ui.theme.AppChip(
                    label = "保留",
                    selected = group.action == FilterRules.FilterAction.KEEP_MATCHED,
                    onClick = { onChange(group.copy(action = FilterRules.FilterAction.KEEP_MATCHED)) },
                )
                com.open115.pad.ui.theme.AppChip(
                    label = "排除",
                    selected = group.action == FilterRules.FilterAction.EXCLUDE_MATCHED,
                    onClick = { onChange(group.copy(action = FilterRules.FilterAction.EXCLUDE_MATCHED)) },
                )
            }

            Spacer(Modifier.height(6.dp))
            group.conditions.forEachIndexed { i, cond ->
                ConditionRow(
                    condition = cond,
                    onChange = { next ->
                        onChange(group.copy(conditions = group.conditions.toMutableList().apply { set(i, next) }))
                    },
                    onRemove = { onChange(group.copy(conditions = group.conditions.filterIndexed { idx, _ -> idx != i })) },
                )
            }

            // 子组（递归）
            group.groups.forEachIndexed { gi, sub ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "子组 ${gi + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onChange(group.copy(groups = group.groups.filterIndexed { idx, _ -> idx != gi })) }) {
                        Icon(Icons.Outlined.Close, contentDescription = "删除子组", Modifier.size(16.dp))
                    }
                }
                GroupEditor(
                    group = sub,
                    depth = depth + 1,
                    onChange = { next ->
                        onChange(group.copy(groups = group.groups.toMutableList().apply { set(gi, next) }))
                    },
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = {
                        onChange(
                            group.copy(
                                conditions = group.conditions + FilterRules.RuleCondition(
                                    kind = FilterRules.ConditionKind.NAME_CONTAINS,
                                ),
                            ),
                        )
                    },
                    label = { Text("添加条件") },
                )
                if (depth < MAX_TREE_DEPTH) {
                    AssistChip(
                        onClick = { onChange(group.copy(groups = group.groups + FilterRules.RuleGroup())) },
                        label = { Text("添加子组") },
                    )
                }
            }
        }
    }
}

/** 单个条件行：种类下拉 + 参数输入（按种类切换） */
@Composable
private fun ConditionRow(
    condition: FilterRules.RuleCondition,
    onChange: (FilterRules.RuleCondition) -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val kind = condition.kind

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.clickable { menuOpen = true },
                ) {
                    Text(
                        kind.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    FilterRules.ConditionKind.entries.forEach { k ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(k.label) },
                            onClick = {
                                onChange(condition.copy(kind = k))
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除条件", Modifier.size(18.dp))
            }
        }

        when {
            kind.needsText -> OutlinedTextField(
                value = condition.text,
                onValueChange = { onChange(condition.copy(text = it)) },
                label = { Text(if (kind == FilterRules.ConditionKind.NAME_REGEX) "正则表达式" else "关键词") },
                singleLine = true,
                isError = kind == FilterRules.ConditionKind.NAME_REGEX && condition.text.isNotBlank() &&
                    runCatching { Regex(condition.text) }.isFailure,
                modifier = Modifier.fillMaxWidth(),
            )

            kind.needsSize -> {
                val mbText = remember(condition.bytes) {
                    if (condition.bytes <= 0) "" else (condition.bytes / 1024.0 / 1024.0).let {
                        if (it % 1.0 == 0.0) "${it.toInt()}" else "%.1f".format(it)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = mbText,
                        onValueChange = { raw ->
                            val mb = raw.toDoubleOrNull()
                            onChange(condition.copy(bytes = if (mb == null) 0L else (mb * 1024 * 1024).toLong()))
                        },
                        label = { Text("大小 (MB)") },
                        singleLine = true,
                        modifier = Modifier.width(160.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "文件体积超过/低于该值时命中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            kind.needsType -> LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(FilterRules.TYPE_LABELS) { _, (value, label) ->
                    com.open115.pad.ui.theme.AppChip(
                        label = label,
                        selected = condition.bytes.toInt() == value,
                        onClick = { onChange(condition.copy(bytes = value.toLong())) },
                    )
                }
            }
        }

        if (kind.needsText && kind != FilterRules.ConditionKind.NAME_REGEX) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(
                    checked = condition.ignoreCase,
                    onCheckedChange = { onChange(condition.copy(ignoreCase = it)) },
                )
                Text("忽略大小写", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
