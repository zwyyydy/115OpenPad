package com.open115.pad.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open115.pad.data.PlaylistEntry
import com.open115.pad.data.media.EpisodeEntity
import com.open115.pad.data.media.MediaDao
import com.open115.pad.data.media.MovieCard
import com.open115.pad.data.media.MovieEntity
import com.open115.pad.player.PlayerActivity
import com.open115.pad.ui.theme.AdaptiveBody
import kotlinx.coroutines.launch

private data class MediaDetailData(
    val movie: MovieEntity,
    val actors: List<String>,
    val tags: List<String>,
    val episodes: List<EpisodeEntity>,
)

/**
 * 影片详情：fanart 铺底 + 海报/标题/评分/简介/演员/标签/剧集 + 直达播放。
 *
 * 播放列表取海报墙传来的整个库的卡片：播完一部自动接下一部（ PlayerActivity 自己管队列），
 * 剧集类资源则把当前影片的分集作为播放列表。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaDetailScreen(
    card: MovieCard,
    playlist: List<MovieCard>,
    index: Int,
    dao: MediaDao,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val data by produceState<MediaDetailData?>(initialValue = null, card.mediaKey) {
        val movie = dao.movie(card.mediaKey)
        if (movie != null && movie.plot.isNullOrBlank() && movie.rating == null) {
            // 扫描期 nfo 拉取失败的影片：进详情页按需重拉一次（自愈），然后再查库
            val container = (context.applicationContext as com.open115.pad.App115).container
            com.open115.pad.data.media.MediaScanner.refetchNfo(
                container.openApi, container.okHttpClient, dao, container.mediaCache, card.mediaKey,
            )
        }
        val updated = dao.movie(card.mediaKey)
        value = if (updated == null) {
            null
        } else {
            MediaDetailData(
                movie = updated,
                actors = dao.actorsOf(card.mediaKey),
                tags = dao.tagsOf(card.mediaKey),
                episodes = dao.episodesOf(card.mediaKey),
            )
        }
    }

    // 库内播放列表（番号式影片经常一个目录多视频，episodes 优先，否则整库串联）
    val movieEntries = remember(playlist) {
        playlist.mapNotNull { c -> c.videoPickCode?.let { pc -> PlaylistEntry(pc, c.title) } }
    }
    val episodeEntries = remember(data) {
        data?.episodes?.mapNotNull { e ->
            e.videoPickCode?.let { pc -> PlaylistEntry(pc, e.videoName ?: e.episodeKey) }
        } ?: emptyList()
    }

    fun play(pc: String, name: String, entries: List<PlaylistEntry>, at: Int) {
        context.startActivity(
            PlayerActivity.intent(context, pc, name, entries, at.coerceIn(0, (entries.size - 1).coerceAtLeast(0))),
        )
    }

    // 系统返回键 = 顶栏返回键：退回海报墙
    androidx.activity.compose.BackHandler { onBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 不透明黑底：详情页是浮在海报墙之上的浮层，fanart 没加载出来时不能透出下层
        containerColor = Color.Black,
    ) { padding ->
        AdaptiveBody(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxSize()) {
                // fanart 铺底 + 渐变压暗，白底海报/文字都压得住
                PickCodeImage(
                    pickCode = data?.movie?.fanartPickCode,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.Black.copy(alpha = 0.82f),
                                MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f),
                            ),
                        ),
                    ),
                )

                val current = data
                if (current == null) {
                    // 还没加载完或已被删除：转圈，删除了就一直转——由用户点返回
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                    return@AdaptiveBody
                }

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = Color.White)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            current.movie.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(Modifier.padding(top = 8.dp)) {
                        // 海报：竖版 2:3，宽度按可用宽度的比例约束（大屏不甩成半屏宽，窄屏不挤成一指宽），
                        // 高度由 2:3 自动推导，无硬编码宽高
                        BoxWithConstraints(Modifier.fillMaxWidth(0.32f).widthIn(min = 110.dp, max = 180.dp)) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                            ) {
                                PickCodeImage(
                                    pickCode = current.movie.posterPickCode,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(
                                current.movie.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                current.movie.year?.let {
                                    Text("$it", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.width(10.dp))
                                }
                                current.movie.rating?.let {
                                    Icon(
                                        Icons.Outlined.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFFFC107),
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text("%.1f".format(it), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            current.movie.genre?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(it, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(14.dp))
                            val mainPc = current.movie.videoPickCode
                            Button(
                                onClick = {
                                    if (mainPc.isNullOrBlank()) {
                                        scope.launch { snackbarHostState.showSnackbar("没有找到可播放的视频文件") }
                                    } else {
                                        play(mainPc, current.movie.videoName ?: current.movie.title, movieEntries, index)
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("播放")
                            }
                        }
                    }

                    current.movie.plot?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(16.dp))
                        Text("简介", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
                    }

                    if (current.actors.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("演员", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Spacer(Modifier.height(6.dp))
                        LabelFlow(current.actors)
                    }

                    if (current.tags.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("标签", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Spacer(Modifier.height(6.dp))
                        LabelFlow(current.tags)
                    }

                    // 分集：番号式/ Emby 剧集目录会有多视频
                    if (episodeEntries.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("分集（${episodeEntries.size}）", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Spacer(Modifier.height(4.dp))
                        current.episodes.forEachIndexed { i, ep ->
                            val pc = ep.videoPickCode
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    ep.episodeKey,
                                    color = Color.White.copy(alpha = 0.9f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = {
                                        if (pc.isNullOrBlank()) {
                                            scope.launch { snackbarHostState.showSnackbar("这一集没有提取码，无法播放") }
                                        } else {
                                            play(pc, ep.videoName ?: ep.episodeKey, episodeEntries, i)
                                        }
                                    },
                                ) {
                                    Icon(Icons.Outlined.PlayArrow, contentDescription = "播放", tint = Color.White)
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Text(
                        current.movie.dirPath ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}

/** 演员/标签小药丸：自动换行 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelFlow(labels: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEach { label ->
            Surface(
                color = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}
