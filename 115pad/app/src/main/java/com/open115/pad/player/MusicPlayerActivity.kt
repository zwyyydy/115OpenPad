package com.open115.pad.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.open115.pad.appContainer
import com.open115.pad.data.parseFileDownloadUrl
import com.open115.pad.ui.theme.Open115Theme
import com.open115.pad.util.APP_USER_AGENT
import com.open115.pad.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 音乐播放器队列里的一项：云盘文件（pick_code）或本机文件（uri），二者取其一 */
@Serializable
data class MusicTrack(
    val name: String,
    val pickCode: String? = null,
    val uri: String? = null,
)

private val musicJson = Json { ignoreUnknownKeys = true }

/**
 * 简易音乐播放器：文件页点音频 / 传输中心点已下载的音频进来。
 *
 * 有意做小：
 *  - 只播音频队列（当前目录里的音频，按列表顺序）；
 *  - 上一首 / 下一首 / 拖动进度 / 播放暂停；
 *  - 云端走 `open/ufile/downurl` 直链（按需解析，不预取整队）；
 *  - **不做后台播放/通知栏控制**（那需要前台服务 + MediaSession）；
 *    退到后台就暂停 —— 避免"音乐还在响但没有地方控制"。
 */
class MusicPlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var tracks: List<MusicTrack> = emptyList()
    private var initialIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        tracks = runCatching {
            musicJson.decodeFromString(
                ListSerializer(MusicTrack.serializer()),
                intent.getStringExtra(EXTRA_TRACKS).orEmpty(),
            )
        }.getOrDefault(emptyList())
        if (tracks.isEmpty()) {
            finish()
            return
        }
        initialIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, tracks.lastIndex)

        val exo = ExoPlayer.Builder(this)
            // 直链播放：不挂播放缓存（音频文件通常不大，缓存换来的复杂度不值）
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(
                        this,
                        DefaultHttpDataSource.Factory()
                            .setUserAgent(APP_USER_AGENT)
                            .setAllowCrossProtocolRedirects(true),
                    ),
                ),
            )
            .build()
        // 处理音频焦点：来电/其它应用放声音时会自动让出
        exo.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player = exo

        setContent {
            Open115Theme {
                MusicPlayerScreen(
                    player = exo,
                    tracks = tracks,
                    initialIndex = initialIndex,
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 简易播放器不做后台播放：没有通知栏控制时，音乐继续响反而是负担
        if (!isChangingConfigurations) player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TRACKS = "tracks"
        private const val EXTRA_INDEX = "index"

        /** 云盘音频队列（文件页点音频时用） */
        fun intent(context: Context, tracks: List<MusicTrack>, index: Int = 0): Intent =
            Intent(context, MusicPlayerActivity::class.java)
                .putExtra(EXTRA_TRACKS, musicJson.encodeToString(ListSerializer(MusicTrack.serializer()), tracks))
                .putExtra(EXTRA_INDEX, index)

        /** 本机音频（传输中心点已下载的文件时用） */
        fun localIntent(context: Context, uri: String, name: String): Intent =
            intent(context, listOf(MusicTrack(name = name, uri = uri)), 0)
    }
}

@Composable
private fun MusicPlayerScreen(
    player: ExoPlayer,
    tracks: List<MusicTrack>,
    initialIndex: Int,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = context.appContainer
    val scope = rememberCoroutineScope()

    var index by remember { mutableIntStateOf(initialIndex) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    // 监听器只挂一次，闭包里的 index 要用 rememberUpdatedState，否则永远停在首次组合的值
    val latestIndex by rememberUpdatedState(index)

    suspend fun playAt(i: Int) {
        val t = tracks.getOrNull(i) ?: return
        loading = true
        error = null
        try {
            val url = t.uri ?: t.pickCode?.let { pc ->
                withContext(Dispatchers.IO) {
                    runCatching { parseFileDownloadUrl(container.openApi.downUrl(pc)) }.getOrNull()
                }
            }
            if (url.isNullOrBlank()) {
                error = "无法获取播放地址"
                return
            }
            index = i
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.play()
        } catch (e: Exception) {
            error = "播放失败：${e.message ?: e.javaClass.simpleName}"
        } finally {
            loading = false
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) durMs = player.duration.coerceAtLeast(0L)
                if (state == Player.STATE_ENDED) {
                    // 播完自动下一首；最后一首就停在末尾
                    if (latestIndex < tracks.lastIndex) scope.launch { playAt(latestIndex + 1) }
                    else player.pause()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "播放失败：${e.errorCodeName}"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 首次进来自动开播
    LaunchedEffect(player) { playAt(initialIndex) }

    // 进度轮询；拖动时不用播放器的实时值，否则滑块会被拽回去
    LaunchedEffect(player) {
        while (isActive) {
            if (!dragging) {
                posMs = player.currentPosition.coerceAtLeast(0L)
                durMs = player.duration.coerceAtLeast(0L)
            }
            delay(400)
        }
    }

    val shownMs = if (dragging) dragMs else posMs
    val current = tracks.getOrNull(index)
    val title = current?.name?.substringBeforeLast('.')?.ifBlank { current.name } ?: "音乐"

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF1A1F29), Color(0xFF07080B))),
            )
            .systemBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                }
                Text(
                    "音乐",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${index + 1} / ${tracks.size}",
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(Modifier.weight(1f))

            Box(
                Modifier
                    .size(250.dp)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF2B3342), Color(0xFF11141B))),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.82f),
                    modifier = Modifier.size(96.dp),
                )
                if (loading || buffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        strokeWidth = 2.dp,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                error ?: if (current?.uri != null) "本机音频" else "云盘音频",
                color = if (error != null) Color(0xFFFF7B7B) else Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.weight(1f))

            Slider(
                value = if (durMs > 0L) (shownMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f,
                onValueChange = {
                    dragging = true
                    dragMs = (it * durMs).toLong()
                },
                onValueChangeFinished = {
                    if (durMs > 0L) {
                        player.seekTo(dragMs)
                        posMs = dragMs
                    }
                    dragging = false
                },
                enabled = durMs > 0L,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Format.playTime(shownMs), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                Text(Format.playTime(durMs), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        // 播过 3 秒先回本首开头，否则上一首（与常见音乐播放器一致）
                        if (player.currentPosition > 3_000L) player.seekTo(0L)
                        else if (index > 0) scope.launch { playAt(index - 1) }
                        else player.seekTo(0L)
                    },
                ) {
                    Icon(Icons.Filled.SkipPrevious, "上一首", tint = Color.White, modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.width(34.dp))
                FilledIconButton(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    shape = CircleShape,
                    modifier = Modifier.size(76.dp),
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        modifier = Modifier.size(44.dp),
                    )
                }
                Spacer(Modifier.width(34.dp))
                IconButton(
                    onClick = { if (index < tracks.lastIndex) scope.launch { playAt(index + 1) } },
                    enabled = index < tracks.lastIndex,
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        "下一首",
                        tint = if (index < tracks.lastIndex) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
        }
    }
}
