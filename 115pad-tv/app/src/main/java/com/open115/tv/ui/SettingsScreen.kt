package com.open115.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open115.tv.receiver.TvProtocol
import com.open115.tv.util.TvPrefs

/**
 * 电视端设置：解码方式、播放器与迷你进度条。
 *
 * 解码/播放器也可以由平板在遥控面板里改（同一份偏好，见 TvPrefs）——电视遥控器够得着的时候
 * 就地改，够不着时用平板改，两边改的是同一个东西。
 */
@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val decode by TvPrefs.decode.collectAsState()
    val player by TvPrefs.player.collectAsState()
    val miniProgress by TvPrefs.miniProgress.collectAsState()
    val autoNext by TvPrefs.autoNext.collectAsState()

    BackHandler { onBack() }

    // 遥控器进来要能直接左右切换：把焦点先放到第一组选项上
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF152540), Color(0xFF07080B))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .widthIn(max = 760.dp),
        ) {
            Text(
                "设置",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(30.dp))
            SettingRow(
                title = "解码方式",
                hint = "软解走 CPU：硬解花屏/黑屏/起播失败时切它绕过（兼容性更好，但高码率 4K 可能跟不动）。",
                options = listOf(
                    TvProtocol.DECODE_HW to "硬解",
                    TvProtocol.DECODE_SW to "软解",
                ),
                selected = decode,
                onSelect = { TvPrefs.setDecode(context, it) },
                firstOptionFocus = firstFocus,
            )
            Spacer(Modifier.height(26.dp))
            SettingRow(
                title = "播放器",
                hint = "内置播放器可以用电视遥控器操作（进度 / 倍速 / 音量），平板上的投屏面板也能遥控；" +
                    "电视自带播放器解码兼容性通常更好，但交出去之后平板控制不了它，只能当内置播放器放不了时的兜底。",
                options = listOf(
                    TvProtocol.PLAYER_BUILTIN to "内置播放器",
                    TvProtocol.PLAYER_SYSTEM to "电视自带",
                ),
                selected = player,
                onSelect = { TvPrefs.setPlayer(context, it) },
            )
            Spacer(Modifier.height(26.dp))
            SettingRow(
                title = "自动连播",
                hint = "一集播完自动接下一集（投屏时平板会把后面几集一起带过来，平板退出也不影响）；" +
                    "队列播完就停在片尾。片尾 5 秒倒计时内按任意键可取消。",
                options = listOf(
                    "on" to "开",
                    "off" to "关",
                ),
                selected = if (autoNext) "on" else "off",
                onSelect = { TvPrefs.setAutoNext(context, it == "on") },
            )
            Spacer(Modifier.height(26.dp))
            SettingRow(
                title = "迷你进度条",
                hint = "控制条收起时，屏幕最下方常驻一条细进度线；不想要可以关掉。",
                options = listOf(
                    "on" to "显示",
                    "off" to "隐藏",
                ),
                selected = if (miniProgress) "on" else "off",
                onSelect = { TvPrefs.setMiniProgress(context, it == "on") },
            )
            Spacer(Modifier.height(36.dp))
            Text(
                "按返回键回到待机",
                color = Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    hint: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    firstOptionFocus: FocusRequester? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            options.forEachIndexed { index, (value, label) ->
                TvOptionChip(
                    label = label,
                    selected = selected == value,
                    focusRequester = if (index == 0) firstOptionFocus else null,
                    onClick = { onSelect(value) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            hint,
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** 选项胶囊：遥控器左右移动焦点、OK 选中；焦点态用亮边框表达（电视上必须一眼看出当前选到哪） */
@Composable
private fun TvOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        color = if (selected) Color(0xFF9FC3FF) else Color.White.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(50))
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(50),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF0F2E52) else Color.White,
            fontSize = 18.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}
