package yos.music.player.ui.pages.discovery

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.objects.RankObject
import yos.music.player.data.repositories.KugouRankSong
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.ui.lazyItemKeys
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.albums.NormalButton
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.Title

/**
 * 榜单详情页。
 *
 * 结构已完全对齐「每日推荐」页（EverydayRecommendDetail）：
 * 统一用 Title(...) 提供列表容器 + 顶栏（模糊/几何都由它负责），
 * 页面内只有 状态行 / 播放按钮行 / 分割线 / 歌曲行。
 *
 * 这样去掉了原实现里本页独有的重开销：自建 LazyColumn 的 layerBackdrop
 * （整页每帧离屏录制）、自定义 overscroll/fling、大封面 + 共享元素转场。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RankDetail(
    navController: NavController,
    // 保留参数以兼容调用点（本页已不使用共享元素转场）
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null
) {
    val rank = RankObject.getSelected()

    val tracks = remember("RankDetail_tracks") {
        mutableStateOf<List<KugouRankSong>>(emptyList())
    }
    val trackItems = tracks.value
    val songKeys = remember(trackItems) { lazyItemKeys(trackItems) { it.hash } }
    val queue = remember("RankDetail_queue") {
        mutableStateOf<List<YosMediaItem>>(emptyList())
    }
    // "loading" | "ok" | "empty" | "error:<msg>"
    val status = remember("RankDetail_status") { mutableStateOf("loading") }
    val scope = rememberCoroutineScope()

    fun loadSongs() {
        val rk = rank ?: return
        status.value = "loading"
        scope.launch {
            KugouRepository.getRankSongs(rk.rankId)
                .onSuccess { list ->
                    tracks.value = list
                    queue.value = list.map { KugouRepository.toQueueMediaItem(it) }
                    status.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e ->
                    tracks.value = emptyList()
                    queue.value = emptyList()
                    status.value = "error:${e.message}"
                }
        }
    }

    fun playAt(index: Int) {
        val list = queue.value
        if (index !in list.indices) return
        scope.launch(Dispatchers.IO) {
            MediaController.prepare(list[index], list)
        }
    }

    LaunchedEffect(Unit) {
        if (rank != null) loadSongs()
    }

    val songCountDisplay =
        if (tracks.value.isNotEmpty()) tracks.value.size else (rank?.songCount ?: 0)

    Title(
        title = rank?.name ?: stringResource(id = R.string.discovery_rank_title),
        subTitle = "$songCountDisplay " +
            stringResource(id = R.string.online_playlists_song_unit),
        onBack = { navController.popBackStack() }
    ) {
        if (rank == null) {
            item("NoRank") {
                Text(
                    text = stringResource(id = R.string.rank_detail_missing),
                    fontSize = 18.sp,
                    modifier = Modifier
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                        .alpha(0.6f)
                )
            }
            return@Title
        }

        if (status.value != "ok") {
            item("Status") {
                val text = when {
                    status.value.startsWith("error") -> status.value.removePrefix("error:")
                    status.value == "empty" -> stringResource(id = R.string.rank_songs_empty)
                    else -> stringResource(id = R.string.online_playlist_loading_songs)
                }
                Text(
                    text = text,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                        .alpha(0.6f)
                )
            }
            if (status.value.startsWith("error")) {
                item("Retry") {
                    Text(
                        text = stringResource(id = R.string.load_more_failed),
                        fontSize = 16.sp,
                        modifier = Modifier
                            .padding(horizontal = 22.dp, vertical = 4.dp)
                            .clickable { loadSongs() }
                    )
                }
            }
            return@Title
        }

        item("Actions") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 15.dp)
                    .padding(horizontal = 22.dp)
            ) {
                NormalButton(
                    icon = painterResource(id = R.drawable.button_icon_play),
                    label = stringResource(id = R.string.normal_button_play),
                    modifier = Modifier.weight(1f)
                ) {
                    // 「播放」= 顺序播放：显式关闭随机（随机为进程级粘性，必须关）
                    MediaController.mediaControl?.shuffleModeEnabled = false
                    playAt(0)
                }
                Spacer(modifier = Modifier.width(15.dp))
                NormalButton(
                    icon = painterResource(id = R.drawable.button_icon_shuffle),
                    label = stringResource(id = R.string.normal_button_shuffle),
                    modifier = Modifier.weight(1f)
                ) {
                    val list = queue.value
                    if (list.isNotEmpty()) {
                        MediaController.mediaControl?.shuffleModeEnabled = true
                        playAt(list.indices.random())
                    }
                }
            }
        }

        item("DividerTop") {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 16.dp)
                    .height(0.5.dp)
                    .background((Color.Black withNight Color.White).copy(alpha = 0.15f))
            )
        }

        itemsIndexed(
            trackItems,
            key = { index, _ -> songKeys.getOrElse(index) { "oob_$index" } },
            contentType = { _, _ -> "song" }
        ) { index, song ->
            val media = remember(song) { KugouRepository.toDisplayMediaItem(song) }
            MusicList(media) { playAt(index) }

            if (index < trackItems.size - 1) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 88.dp, end = 16.dp)
                        .height(0.5.dp)
                        .background((Color.Black withNight Color.White).copy(alpha = 0.15f))
                )
            }
        }

        item("navbar") {
            Spacer(Modifier.navigationBarsPadding().height(134.dp))
        }
    }
}
