package com.open115.pad.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.open115.pad.AppContainer
import com.open115.pad.data.ImageMediaItem
import com.open115.pad.data.media.MediaDao
import com.open115.pad.data.media.MediaLibraryEntity
import com.open115.pad.data.media.MovieCard
import com.open115.pad.ui.theme.AdaptiveBody

/**
 * 海报墙：一个媒体库的海报网格。
 *
 * 海报/背景图只有 pick_code，加载时要经 ImageUrlResolver 走 downurl 换直链
 * （115 不给固定图床地址），解析结果有 LRU 缓存，翻页回来不会重复请求。
 */
@Composable
fun PosterWallScreen(
    library: MediaLibraryEntity,
    dao: MediaDao,
    onBack: () -> Unit,
    onOpenMovie: (MovieCard, List<MovieCard>, Int) -> Unit,
) {
    val all by dao.byLibraryPath(library.rootPath).collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    // 标题搜索是全表查询，结果再按本库的 mediaKey 收敛，避免搜出别的库的影片
    val hits by produceState<List<MovieCard>?>(initialValue = null, query, all) {
        value = if (query.isBlank()) {
            null
        } else {
            val keys = all.mapTo(HashSet()) { it.mediaKey }
            dao.searchByTitle(query, 100).filter { it.mediaKey in keys }
        }
    }
    val list = hits ?: all

    // 不透明底：这两级"页"是浮在媒体库列表页之上的浮层，没有底色会把下层透出来
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    AdaptiveBody(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.weight(1f)) {
                    Text(library.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${library.rootPath} · ${list.size} 部",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("按标题搜索") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (list.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (query.isBlank()) "这个库还没有影片，回列表点扫描" else "没有匹配「$query」的影片",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.mediaKey }) { card ->
                        MoviePosterCard(card = card, onClick = { onOpenMovie(card, list, list.indexOf(card)) })
                    }
                }
            }
        }
    }
    }
}

/** 竖版海报卡片：海报 + 评分角标 + 标题/年份；没有海报时用图标兜底，不留空白 */
@Composable
private fun MoviePosterCard(card: MovieCard, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            if (card.posterPickCode.isNullOrBlank()) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                PickCodeImage(
                    pickCode = card.posterPickCode,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 底部渐变：保证白底海报上的标题也读得清
            Box(
                Modifier.fillMaxWidth().height(56.dp).align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                        ),
                    ),
            )
            if (card.rating != null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(bottomStart = 8.dp),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Text(
                        "★ ${"%.1f".format(card.rating)}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Column(
                Modifier.align(Alignment.BottomStart).padding(8.dp),
            ) {
                Text(
                    card.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                card.year?.let {
                    Text("$it", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * pick_code → 直链 → Coil 加载。
 * 解析失败/无图时留空（卡片底层已有图标或渐变兜底），绝不白屏崩溃。
 */
@Composable
fun PickCodeImage(
    pickCode: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as com.open115.pad.App115).container
    // 磁盘缓存路径优先：resolve 出直链后把字节落到本地（media_img 目录），
    // 下次冷启动同一 pickCode 直接命中文件，downurl 一次都不调。
    val model by produceState<Any?>(initialValue = null, pickCode) {
        if (pickCode.isNullOrBlank()) return@produceState
        val resolver = container.imageUrlResolver
        val url = resolver.resolveOrigin(
            ImageMediaItem(
                fileId = null,
                fileName = "",
                fileSize = 0,
                pickCode = pickCode,
                thumbnailUrl = null,
                originUrl = null,
            ),
        ) ?: return@produceState
        value = runCatching {
            resolver.fetchPosterToCache(pickCode, url, container.cacheDir).absolutePath
        }.getOrDefault(url)
    }
    if (model != null) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
            loading = {
                Box(modifier, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            },
            error = {},
        )
    } else {
        Spacer(modifier)
    }
}
