package com.open115.pad.ui.files

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.data.DupFile
import com.open115.pad.data.DupGroup
import com.open115.pad.data.DuplicateFinder
import com.open115.pad.data.OpLog
import com.open115.pad.data.OpType
import com.open115.pad.data.OpenApi
import com.open115.pad.data.VersionFinder
import com.open115.pad.data.VersionGroup
import com.open115.pad.data.VersionVerdict
import com.open115.pad.data.VideoVersion
import com.open115.pad.data.COMPARE_ATS
import com.open115.pad.data.VideoFrameGrabber
import com.open115.pad.data.compareFrameHashes
import com.open115.pad.data.consistentCluster
import com.open115.pad.data.distanceHint
import com.open115.pad.data.frameHashesOf
import com.open115.pad.data.envMsg
import com.open115.pad.data.envOk
import com.open115.pad.data.orderByQuality
import com.open115.pad.data.siblingKey
import com.open115.pad.data.verdictOf
import com.open115.pad.ui.components.ConfirmDialog
import com.open115.pad.ui.components.Thumb
import com.open115.pad.ui.components.videoExts
import com.open115.pad.ui.theme.AppChip
import com.open115.pad.ui.theme.AppColors
import com.open115.pad.util.Format
import kotlinx.coroutines.launch

/**
 * 打开去重面板的请求：带上"从哪一层开始扫"（就是点按键时文件页所在的那个目录）。
 *
 * 与 `BatchRenameRequest` 同一套写法：文件页只负责"发起 + 告诉 App 根层要显示什么"，
 * 面板本身渲染在内容区之上（盖住内容、不盖左侧导航栏），删除逻辑都在面板里边。
 *
 * [version] 只在"完成弹窗点查看结果"时带上：那一轮跑的是哪个模式，面板就落在哪一屏
 * （两种模式的结果各自留着，见面板里 mode 的说明）。
 */
data class DupScanRequest(val cid: String, val path: String, val version: Boolean = false)

/**
 * 重复文件查找面板（去重一期）：起始层 / 进度 / 结果三态一屏。
 *
 * 判重依据见 [DuplicateFinder]：按 **SHA-1** 分组，所以"内容完全相同"是确定的，不是猜的；
 * 删除一律走 **115 回收站**（可还原），保留项默认取云端最早的那一份。
 */
@Composable
fun DuplicatePanel(
    request: DupScanRequest,
    finder: DuplicateFinder,
    versionFinder: VersionFinder,
    api: OpenApi,
    /** 画面比对（C 段）要用的抽帧器：从 115 最小转码档抓几帧算 dHash */
    frames: VideoFrameGrabber,
    opLog: OpLog?,
    /** 点某个视频试播（去重面板里"先看一眼再决定删谁"）：参数 = 条目 + 所在目录 */
    onPlay: (item: com.open115.pad.data.FileItem, dirCid: String?, dirPath: String?) -> Unit = { _, _, _ -> },
    onDismiss: () -> Unit,
    onMessage: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val progress by finder.progress.collectAsState()
    val versionProgress by versionFinder.progress.collectAsState()
    /** 0 = 内容相同（sha1，可删）/ 1 = 同片多版本（时长预筛，只出候选清单） */
    var mode by remember { mutableStateOf(if (request.version) 1 else 0) }
    val busy = progress.running || versionProgress.running

    /** 扫描范围：默认只看当前这一层（最快，也最不容易误伤） */
    var recursive by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var askingDelete by remember { mutableStateOf(false) }
    /** 版本模式点「只保留最高档」后待确认的 (要留的, 要删的几个) */
    var askingKeepBest by remember { mutableStateOf<Pair<VideoVersion, List<VideoVersion>>?>(null) }
    /** 版本模式点了某条的删除键、待确认的那一条（最后的筛选删除就靠它） */
    var askingDeleteOne by remember { mutableStateOf<VideoVersion?>(null) }
    /** fid → 是否要删。默认"每组除保留项外都勾上"，用户改的是例外 */
    val checked = remember { mutableStateMapOf<String, Boolean>() }

    /** 画面比对结果：key = 组的成员指纹（见 [frameKey]） */
    val frameResults = remember { mutableStateMapOf<String, FrameCompareState>() }

    // 结果一到（或换了一批）就把默认勾选铺好；**已经存在用户改过的键不动**，
    // 所以删掉一组之后剩下那几组的勾选状态不会被打回默认。
    val signature = remember(progress.groups) { progress.groups.joinToString("|") { it.sha1 } }
    LaunchedEffect(signature) {
        progress.groups.forEach { g -> g.removable.forEach { f -> if (f.fid !in checked) checked[f.fid] = true } }
    }

    val picked = progress.groups.flatMap { g ->
        val keep = g.files.first().fid
        g.files.filter { it.fid != keep && checked[it.fid] == true }
    }
    val pickedBytes = picked.sumOf { it.size }

    fun startScan() {
        checked.clear()
        if (mode == 0) {
            finder.clearResult()
            finder.start(request.cid, request.path, recursive)
        } else {
            versionFinder.clearResult()
            versionFinder.start(request.cid, request.path, recursive)
        }
    }

    fun deletePicked() {
        val ids = picked.map { it.fid }
        if (ids.isEmpty() || deleting) return
        val cids = picked.map { it.dirCid }.distinct()
        deleting = true
        scope.launch {
            val msg = runCatching {
                // 跨目录删：不传 parent_id（与媒体库「彻底删除」同一套调用）
                val resp = api.deleteFiles(ids.joinToString(","), parentId = null)
                if (resp.envOk()) {
                    opLog?.log(OpType.DELETE, "重复文件 ${ids.size} 项", "去重清理 · 移入回收站")
                    finder.markDeleted(ids, cids)
                    "已移入回收站 ${ids.size} 个（可在回收站还原）"
                } else {
                    resp.envMsg() ?: "删除失败"
                }
            }.getOrElse { "网络错误：${it.message}" }
            deleting = false
            onMessage(msg)
        }
    }

    /**
     * 版本模式：把 doomed 几个移入回收站。单条删除（[keep] 为 null）与
     * 「只保留最高档」（[keep] 非空）共用这一套底层。
     * **只动结构一致的那个子集**（不是整组）—— 单删不受此限，删哪条由用户定。
     */
    fun deleteVersions(keep: VideoVersion?, doomed: List<VideoVersion>) {
        if (doomed.isEmpty() || deleting) return
        val ids = doomed.map { it.fid }
        val cids = doomed.map { it.dirCid }.distinct()
        deleting = true
        scope.launch {
            val msg = runCatching {
                val resp = api.deleteFiles(ids.joinToString(","), parentId = null)
                if (resp.envOk()) {
                    opLog?.log(OpType.DELETE, "${doomed.size} 个低档版本", "同片多版本清理 · 移入回收站")
                    // 两个 finder 都要忘掉这些文件（查重结果里也不该再留着它们）；
                    // 顺带（markDeleted 会更新 lastDeletedAt/lastDeletedCids）让文件页自动刷新列表
                    finder.markDeleted(ids, cids)
                    versionFinder.markDeleted(ids)
                    if (keep != null) "已移入回收站 ${ids.size} 个，保留了「${keep.name}」"
                    else "已移入回收站 ${ids.size} 个（可在回收站还原）"
                } else {
                    resp.envMsg() ?: "删除失败"
                }
            }.getOrElse { "网络错误：${it.message}" }
            deleting = false
            onMessage(msg)
        }
    }

    BackHandler(enabled = !deleting) { onDismiss() }

    /**
     * 画面比对（C 段）的结果，按"组"存（key = 组内 fid 排序拼串，与组内容一一对应）。
     *
     * B 段只比结构（宽高比 + 时长毫秒级一致），能筛掉"时长凑巧相同的别的片子"，
     * 但结构一致 ≠ 画面相同。这里对组内成员各抽 3 帧（同一组比例位置）算 dHash，
     * 两两比一遍：只要有一对的多数帧对不上，就说明这组里混着不是同一部片的成员。
     */
    fun compareFramesOf(g: VersionGroup) {
        val key = frameKey(g)
        if (frameResults[key]?.comparing == true) return
        val targets = orderByQuality(g.members, versionProgress.meta).filter { !it.item.pc.isNullOrBlank() }
        if (targets.size < 2) {
            onMessage("这组能抽帧的成员不足 2 个")
            return
        }
        frameResults[key] = FrameCompareState(comparing = true)
        scope.launch {
            val hashes = HashMap<String, List<Long>>()
            for (v in targets) {
                val pc = v.item.pc ?: continue
                val h = runCatching { frameHashesOf(frames, pc) }.getOrNull().orEmpty()
                if (h.isNotEmpty()) hashes[v.fid] = h
            }
            if (hashes.size < 2) {
                frameResults[key] = FrameCompareState(
                    error = if (hashes.isEmpty()) "都没抓到帧（多半是云端还没转码）" else "只有 1 个成员抓到帧，比不了",
                    frameCounts = hashes.mapValues { it.value.size },
                )
                return@launch
            }
            // 两两比一遍（哈希都已在手，这一层不再请求），记下最差的一对
            var same = 0
            var total = 0
            var worst = 0
            var worstPair: Pair<String, String>? = null
            var worstDist: List<Int> = emptyList()
            val members = targets.filter { hashes.containsKey(it.fid) }
            for (i in members.indices) {
                for (j in i + 1 until members.size) {
                    val a = members[i]
                    val b = members[j]
                    val cmp = compareFrameHashes(hashes[a.fid]!!, hashes[b.fid]!!) ?: continue
                    total++
                    if (cmp.same) same++
                    val d = cmp.distances.maxOrNull() ?: 0
                    if (total == 1 || d > worst) {
                        worst = d
                        worstPair = a.name to b.name
                        worstDist = cmp.distances
                    }
                }
            }
            frameResults[key] = FrameCompareState(
                frameCounts = hashes.mapValues { it.value.size },
                worstPair = worstPair,
                worstDistances = worstDist,
                pairsSame = same,
                pairsTotal = total,
                error = if (total == 0) "帧数不够，比不了" else null,
            )
        }
    }

    BackHandler(enabled = !deleting) { onDismiss() }

    // 实底：壁纸激活时 scheme 的 surface 是半透明的，会透出底下的文件列表
    Surface(Modifier.fillMaxSize(), color = AppColors.Card) {
        Column(Modifier.fillMaxSize()) {
            PanelTopBar(
                hasResult = when (mode) {
                    0 -> progress.groups.isNotEmpty() && !progress.running
                    else -> versionProgress.groups.isNotEmpty() && !versionProgress.running
                },
                onRescan = { startScan() },
                onDismiss = onDismiss,
            )
            ModeChips(mode, enabled = !busy) { m ->
                mode = m
                // 换模式就把上一模式的结果留着（切回来还在），只是不显示 —— 清理交给「重新扫描」
            }
            when {
                mode == 1 -> VersionBody(
                    progress = versionProgress,
                    frameResults = frameResults,
                    onCompareFrames = { g -> compareFramesOf(g) },
                    recursive = recursive,
                    onRecursive = { recursive = it },
                    onStop = { versionFinder.stop() },
                    onDeepenAll = { versionFinder.deepen(versionProgress.groups.flatMap { g -> g.members }) },
                    onDeepenGroup = { g -> versionFinder.deepen(g.members) },
                    onKeepBest = { keep, doomed -> askingKeepBest = keep to doomed },
                    onPlay = { m -> onPlay(m.item, m.dirCid, m.dirPath) },
                    onDeleteAsk = { askingDeleteOne = it },
                    request = request,
                    onStart = { startScan() },
                )

                progress.running -> RunningBody(progress, recursive, onStop = { finder.stop() })

                progress.groups.isNotEmpty() -> LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    item { ResultHeader(progress) }
                    items(progress.groups, key = { it.sha1 }) { g ->
                        GroupCard(g, checked, onPlay = onPlay) { fid -> checked[fid] = checked[fid] != true }
                    }
                }

                else -> StartBody(
                    request = request,
                    versionMode = false,
                    summary = progress.summary,
                    recursive = recursive,
                    onRecursive = { recursive = it },
                    onStart = { startScan() },
                )
            }
            // 只有「内容相同」这档才给删除：版本候选天生带误报，定案要等深挖 + 人确认
            if (mode == 0 && progress.groups.isNotEmpty()) {
                PanelBottomBar(
                    pickedCount = picked.size,
                    pickedBytes = pickedBytes,
                    deleting = deleting,
                    onDelete = { askingDelete = true },
                )
            }
        }
    }

    if (askingDelete) {
        ConfirmDialog(
            title = "移入回收站",
            text = "将把选中的 ${picked.size} 个文件移入 115 回收站（可在回收站里还原）。\n\n" +
                "每组保留的是云端时间最早的那一份，可在列表里改。",
            confirmText = "删除",
            onConfirm = {
                askingDelete = false
                deletePicked()
            },
            onDismiss = { askingDelete = false },
        )
    }

    askingKeepBest?.let { (keep, doomed) ->
        ConfirmDialog(
            title = "只保留最高档",
            text = "这组里结构一致的另外 ${doomed.size} 个会移入 115 回收站（可还原），保留：\n\n${keep.name}\n\n" +
                "⚠️ 这一步只比对了真实的分辨率 / 码率 / 时长，没有比对画面内容 —— " +
                "请再确认这几个文件确实是同一部片（不是时长凑巧相同的另一部）。",
            confirmText = "删除",
            onConfirm = {
                askingKeepBest = null
                deleteVersions(keep, doomed)
            },
            onDismiss = { askingKeepBest = null },
        )
    }

    askingDeleteOne?.let { target ->
        ConfirmDialog(
            title = "移入回收站",
            text = "「${target.name}」将移入 115 回收站（可在回收站里还原）。\n\n" +
                "⚠️ 候选只是按规格线索排的，删除前建议先点它试播、确认是多余的那一份。",
            confirmText = "删除",
            onConfirm = {
                val doomed = target
                askingDeleteOne = null
                deleteVersions(null, listOf(doomed))
            },
            onDismiss = { askingDeleteOne = null },
        )
    }
}

@Composable
private fun PanelTopBar(hasResult: Boolean, onRescan: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            "查找重复文件",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
        )
        Spacer(Modifier.weight(1f))
        if (hasResult) TextButton(onClick = onRescan) { Text("重新扫描") }
    }
}

/** 起始层：说明判据 + 选扫描范围 + 开始 */
@Composable
private fun StartBody(
    request: DupScanRequest,
    versionMode: Boolean,
    summary: String?,
    recursive: Boolean,
    onRecursive: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (versionMode) "在「${request.path}」里找**疑似同一部片的不同版本**"
            else "在「${request.path}」里找内容完全相同的文件",
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (versionMode) {
                "这一步只用**零成本的线索**：时长几乎一样（±3 秒）且不是同一部剧的各集/各部分。\n\n" +
                    "名字与体积只用来排序、不作门槛 —— 名字完全不一样、体积也接近的两版同样会留在列表里。" +
                    "所以出来的是**候选清单**（可能混入时长巧合同样的片子）。" +
                    "点条目可以先试播，再点「深挖」读真实分辨率/码率核对；" +
                    "确认是同一部片后用每条后面的删除键（或「只保留最高档」）清理，删除移入回收站、可还原。"
            } else {
                "判据是 115 给的文件 SHA-1（列表里自带，不用下载、不用算哈希），" +
                    "所以「内容相同」是确定的。0 字节的文件与云端没给 SHA-1 的不参与判重。\n\n" +
                    "删除会移入 115 回收站，可以还原；每组默认保留云端时间最早的那一份。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextTertiary,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // onClick 必须显式传：AppChip 的尾随 lambda 绑的是 leading（踩过的坑）
            AppChip(label = "仅当前目录", selected = !recursive, onClick = { onRecursive(false) })
            Spacer(Modifier.width(8.dp))
            AppChip(label = "含所有子目录", selected = recursive, onClick = { onRecursive(true) })
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (recursive) "子目录要一层层列，目录多时会慢（有防频控的限速），过程中可以停。"
            else "只列这一层，几秒钟的事。",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onStart, enabled = request.cid.isNotBlank()) {
            Text(if (versionMode) "开始预筛" else "开始扫描")
        }
        if (summary != null) {
            Spacer(Modifier.height(12.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
    }
}

/** 两种模式：内容相同（sha1，可删）/ 同片多版本（时长预筛，只出候选） */
@Composable
private fun ModeChips(mode: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppChip(
            label = "内容相同",
            selected = mode == 0,
            onClick = { if (enabled) onPick(0) },
        )
        Spacer(Modifier.width(8.dp))
        AppChip(
            label = "同片多版本",
            selected = mode == 1,
            onClick = { if (enabled) onPick(1) },
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (mode == 0) "按 SHA-1：内容一模一样" else "按时长：疑似同一部片",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary,
        )
    }
}

/**
 * 版本模式的整屏：进度 / 候选清单。
 *
 * 候选可点试播（[onPlay]）、可单条删除（[onDeleteAsk]）—— 最后的"删谁留谁"由用户
 * 点着看完再定；「只保留最高档」仍在，两把刀并存。
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.VersionBody(
    progress: VersionFinder.Progress,
    /** 画面比对结果（key = 组成员指纹，见 frameKey）与触发回调 */
    frameResults: Map<String, FrameCompareState>,
    onCompareFrames: (VersionGroup) -> Unit,
    recursive: Boolean,
    onRecursive: (Boolean) -> Unit,
    onStop: () -> Unit,
    onDeepenAll: () -> Unit,
    onDeepenGroup: (VersionGroup) -> Unit,
    onKeepBest: (VideoVersion, List<VideoVersion>) -> Unit,
    onPlay: (VideoVersion) -> Unit,
    onDeleteAsk: (VideoVersion) -> Unit,
    request: DupScanRequest,
    onStart: () -> Unit,
) {
    when {
        progress.running -> Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                if (recursive) "正在预筛「${progress.rootPath}」及其子目录" else "正在预筛「${progress.rootPath}」",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "已列 ${progress.scannedDirs} 个目录 · ${progress.seenVideos} 个视频",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onStop) { Text("停止") }
        }

        progress.groups.isNotEmpty() -> LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            progress.summary ?: "候选 ${progress.groups.size} 组",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (progress.probing) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "读规格 ${progress.probeDone}/${progress.probeTotal}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextSecondary,
                            )
                            TextButton(onClick = onStop) { Text("停止") }
                        } else {
                            TextButton(onClick = onDeepenAll) { Text("全部深挖") }
                        }
                    }
                    Text(
                        "A 段候选天生带误报；点「深挖」读真实的分辨率/码率/时长后再判断（每个 1~2 秒）",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary,
                    )
                }
            }
            items(progress.groups, key = { "v:" + it.members.joinToString("|") { m -> m.fid } }) { g ->
                VersionCard(
                    g, progress,
                    onDeepen = onDeepenGroup,
                    onKeepBest = onKeepBest,
                    onPlay = onPlay,
                    onDeleteAsk = onDeleteAsk,
                    frameState = frameResults[frameKey(g)],
                    onCompareFrames = { onCompareFrames(g) },
                )
            }
        }

        else -> StartBody(
            request = request,
            versionMode = true,
            summary = progress.summary,
            recursive = recursive,
            onRecursive = onRecursive,
            onStart = onStart,
        )
    }
}

/** 一组"疑似同一部片的不同版本"：点条目试播、点删除键移入回收站 + 线索标签 */
@Composable
private fun VersionCard(
    g: VersionGroup,
    progress: VersionFinder.Progress,
    onDeepen: (VersionGroup) -> Unit,
    onKeepBest: (VideoVersion, List<VideoVersion>) -> Unit,
    onPlay: (VideoVersion) -> Unit,
    onDeleteAsk: (VideoVersion) -> Unit,
    /** 本组的画面比对结果（null = 还没比过） */
    frameState: FrameCompareState?,
    onCompareFrames: () -> Unit,
) {
    val meta = progress.meta
    // 深挖过的按真实像素量/码率排（"哪一档更高"），没深挖的排后面。
    // 判断与删除都只在**结构一致的最大子集**上做（A 段的桶里可能混着别的片子）
    val cluster = consistentCluster(g.members, meta)
    val ordered = orderByQuality(g.members, meta)
    val verdict = verdictOf(g, meta)
    val keeper = orderByQuality(cluster, meta).firstOrNull()
    val pending = g.members.count { it.fid !in meta }
    // 同"同胞身份"（除集号/分P号外完全一样）的成员：整桶没被排掉时它们仍会留在组里
    // （因为桶里混了一个真候选），标出来让用户一眼知道"这几条是同一部剧的各集，不是版本"
    val siblingCounts = g.members.groupBy { siblingKey(it.name) }.mapValues { it.value.size }
    Surface(
        color = AppColors.GraySoft,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${g.members.size} 个候选 · 时长 ${g.duration}s（极差 ${g.durationSpan}s）",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "体积 ${String.format(java.util.Locale.CHINA, "%.2f", g.sizeRatio)}×",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.AccentDeep,
                )
            }
            // 线索标签：让用户有依据决定"值不值得深挖"
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val clue = when {
                    g.sharedToken != null -> "名字共同番号：${g.sharedToken}"
                    g.commonSubstring >= 4 -> "名字有 ${g.commonSubstring} 字相同片段"
                    else -> "名字无共同点"
                }
                Text(
                    clue,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (g.sharedToken != null) AppColors.AccentDeep else AppColors.TextTertiary,
                    modifier = Modifier
                        .background(AppColors.BlueBg, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (g.sameContent) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "含内容完全相同的副本（可用「内容相同」清理）",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextSecondary,
                    )
                }
            }
            // 深挖后的结构判断
            if (verdict != VersionVerdict.UNKNOWN) {
                Spacer(Modifier.height(4.dp))
                val (bg, fg) = verdictColors(verdict)
                Text(
                    verdict.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg,
                    modifier = Modifier
                        .background(bg, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            ordered.forEach { m ->
                val vm = meta[m.fid]
                val isKeeper = keeper != null && m.fid == keeper.fid
                val inCluster = cluster.any { it.fid == m.fid }
                Row(
                    Modifier
                        .fillMaxWidth()
                        // 点条目本体 = 试播（"先看一眼再决定删谁"）；删除走行尾的删除键
                        .clickable { onPlay(m) }
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumb(m.item, Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)), iconSize = 20)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            m.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (vm != null) {
                            Text(
                                "真实 ${vm.resolution} · ${bitrateText(vm.bitrate)} · ${Format.duration(vm.durationMs / 1000)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.AccentDeep,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            buildString {
                                append("${m.dirPath} · ${Format.size(m.size)} · ${Format.duration(m.duration)}")
                                // 组里混进来的"非版本"成员标一下，省得用户困惑：
                                // 同名 = 同一文件的另一份拷贝；只是集号/分P号不同 = 同一部剧的各集
                                val hint = when {
                                    g.members.count { it.name == m.name } > 1 -> "同名（另一份拷贝）"
                                    (siblingCounts[siblingKey(m.name)] ?: 0) > 1 -> "同剧各集/分P"
                                    else -> null
                                }
                                if (hint != null) append(" · ").append(hint)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isKeeper) {
                        Text(
                            "建议保留",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.GreenFg,
                            modifier = Modifier
                                .background(AppColors.GreenBg, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    } else if (vm != null && !inCluster && verdict == VersionVerdict.LIKELY_SAME) {
                        // 组里结构对不上的成员（时长/画幅跟主流不一致）：留着，但不参与这次清理
                        Text(
                            "结构不同",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.AmberFg,
                            modifier = Modifier
                                .background(AppColors.AmberBg, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    // 单条删除：最后的筛选删除就靠它（建议保留的也一样可删 —— 删谁是用户的最终决定）
                    IconButton(onClick = { onDeleteAsk(m) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "移入回收站",
                            tint = AppColors.RedFg,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            // 动作：先把规格摸清，结构对得上才给"只保留最高档"
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pending > 0) {
                    TextButton(onClick = { onDeepen(g) }, enabled = !progress.probing) {
                        Text("深挖真实规格（还有 $pending 个）")
                    }
                } else if (cluster.size >= 2 && keeper != null) {
                    // 画面比对说"不一致"时**不给**这个按钮：结构一致是必要条件、不是充分条件
                    val blockKeepBest = frameState?.let { !it.comparing && it.error == null && !it.allSame } == true
                    TextButton(
                        onClick = { onKeepBest(keeper, cluster.filter { it.fid != keeper.fid }) },
                        enabled = !progress.probing && !blockKeepBest,
                    ) {
                        Text(
                            if (blockKeepBest) "画面比对不一致，先别删"
                            else "只保留最高档（删这 ${cluster.size - 1} 个结构一致的）",
                        )
                    }
                }
            }
            // 画面比对（C 段）：B 段只比结构，这里各抽 3 帧比 dHash，确认"真的是同一部片"
            FrameCompareRow(frameState = frameState, enabled = !progress.probing, onCompare = onCompareFrames)
        }
    }
}

@Composable
private fun verdictColors(v: VersionVerdict): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> =
    when (v) {
        VersionVerdict.LIKELY_SAME -> AppColors.GreenBg to AppColors.GreenFg
        VersionVerdict.DIFFERENT_SHAPE -> AppColors.AmberBg to AppColors.AmberFg
        VersionVerdict.UNKNOWN -> AppColors.GraySoft to AppColors.TextTertiary
    }

private fun bitrateText(bps: Long): String =
    if (bps <= 0) "码率未知" else String.format(java.util.Locale.CHINA, "%.1f Mbps", bps / 1_000_000.0)

@Composable
private fun RunningBody(
    progress: DuplicateFinder.Progress,
    recursive: Boolean,
    onStop: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            if (recursive) "正在扫描「${progress.rootPath}」及其子目录" else "正在扫描「${progress.rootPath}」",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "已列 ${progress.scannedDirs} 个目录 · ${progress.seenFiles} 个文件 · 已发现 ${progress.groupCount} 组重复",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onStop) { Text("停止") }
        Text(
            "停下来也会把已经扫到的结果给你",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary,
        )
    }
}

@Composable
private fun ResultHeader(progress: DuplicateFinder.Progress) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            progress.summary ?: "发现 ${progress.groups.size} 组重复",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
        )
        if (progress.stopped) {
            Text(
                "（提前停止：结果只覆盖已经扫过的目录）",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
            )
        }
    }
}

/** 一组重复：保留项打头（不可勾），其余可勾；点条目 = 视频（除保留项走勾选）试播 */
@Composable
private fun GroupCard(
    g: DupGroup,
    checked: Map<String, Boolean>,
    onPlay: (com.open115.pad.data.FileItem, String?, String?) -> Unit,
    onToggle: (String) -> Unit,
) {
    Surface(
        color = AppColors.GraySoft,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${g.files.size} 个内容相同",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "每个 ${Format.size(g.size)} · 可省 ${Format.size(g.wasteBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.AccentDeep,
                )
            }
            g.files.forEachIndexed { i, f ->
                CopyRow(
                    f,
                    keep = i == 0,
                    checked = checked[f.fid] == true,
                    onToggle = onToggle,
                    onPlay = onPlay,
                )
            }
        }
    }
}

@Composable
private fun CopyRow(
    f: DupFile,
    keep: Boolean,
    checked: Boolean,
    onToggle: (String) -> Unit,
    onPlay: (com.open115.pad.data.FileItem, String?, String?) -> Unit,
) {
    val isVideo = f.item.isv == 1 || f.name.substringAfterLast('.', "").lowercase() in videoExts
    Row(
        Modifier
            .fillMaxWidth()
            // 点条目本体：视频 = 试播；非视频/保留项维持"点按勾选"（保留项没有可勾的）
            .clickable {
                when {
                    isVideo -> onPlay(f.item, f.dirCid, f.dirPath)
                    !keep -> onToggle(f.fid)
                }
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (keep) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "保留这一份",
                    tint = AppColors.AccentDeep,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle(f.fid) },
                modifier = Modifier.size(24.dp),
            )
        }
        Thumb(f.item, Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)), iconSize = 20)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                f.name,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(f.dirPath)
                    if (f.upt > 0) append(" · ").append(Format.dateTime(f.upt))
                },
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (keep) {
            Text(
                "保留",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.AccentDeep,
                modifier = Modifier.background(AppColors.BlueBg, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun PanelBottomBar(pickedCount: Int, pickedBytes: Long, deleting: Boolean, onDelete: () -> Unit) {
    Surface(color = AppColors.Card, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (pickedCount == 0) "没有勾选要删的副本" else "已选 $pickedCount 个 · 约 ${Format.size(pickedBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            if (deleting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = onDelete, enabled = pickedCount > 0 && !deleting) {
                Text("移入回收站")
            }
        }
    }
}

/**
 * 一个"版本组"的画面比对状态。
 *
 * 结论看 [pairsSame] / [pairsTotal]：帧比的是**同一组比例位置**（[COMPARE_ATS]，
 * 各成员都按片长比例取，所以同一部片的两版能采到同一画面），逐对多帧多数表决
 * （见 [compareFrameHashes]）。只要有一对对不上，就说明这组里混着不是同一部片的成员。
 */
private data class FrameCompareState(
    val comparing: Boolean = false,
    /** fid → 实际抓到的帧数（抓不满 3 帧的成员也照样参与，少一帧少比一帧） */
    val frameCounts: Map<String, Int> = emptyMap(),
    /** 最差的那一对（名字 + 逐帧汉明距离），给用户一个可核对的数 */
    val worstPair: Pair<String, String>? = null,
    val worstDistances: List<Int> = emptyList(),
    val pairsSame: Int = 0,
    val pairsTotal: Int = 0,
    val error: String? = null,
) {
    /** 所有两两之对都判"同一画面" */
    val allSame: Boolean get() = pairsTotal > 0 && pairsSame == pairsTotal
}

/** 组的指纹：组内成员 fid 排序拼串（组的身份就是它的成员集合） */
private fun frameKey(g: VersionGroup): String = g.members.map { it.fid }.sorted().joinToString("|")

/** 「画面比对」这一行的三种状态：未比 / 正在比 / 有结论 */
@Composable
private fun FrameCompareRow(
    frameState: FrameCompareState?,
    enabled: Boolean,
    onCompare: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            frameState == null -> {
                TextButton(onClick = onCompare, enabled = enabled) { Text("画面比对（各抽 3 帧）") }
                Spacer(Modifier.width(6.dp))
                Text(
                    "约 1MB/条，确认是同一部片再删",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                )
            }
            frameState.comparing -> {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在抽帧比对…",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextSecondary,
                )
            }
            frameState.error != null -> {
                Text(
                    "画面比对：${frameState.error}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.AmberFg,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onCompare, enabled = enabled) { Text("重试") }
            }
            else -> {
                val ok = frameState.allSame
                Text(
                    if (ok) {
                        "画面一致：${frameState.pairsSame}/${frameState.pairsTotal} 对帧都对得上" +
                            (frameState.worstDistances.maxOrNull()?.let { "（最差 ${distanceHint(it)}）" } ?: "")
                    } else {
                        "画面不一致：只有 ${frameState.pairsSame}/${frameState.pairsTotal} 对帧对得上" +
                            (frameState.worstPair?.let { "，最差是「${it.first}」↔「${it.second}」" } ?: "") +
                            (frameState.worstDistances.maxOrNull()?.let { "（${distanceHint(it)}）" } ?: "")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ok) AppColors.GreenFg else AppColors.RedFg,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onCompare, enabled = enabled) { Text("重比") }
            }
        }
    }
}
