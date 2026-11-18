package app.melo.music.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.melo.music.*
import app.melo.music.R
import app.melo.music.data.*

@Composable internal fun BrandMark(modifier: Modifier = Modifier) {
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.mauri_mark), "Логотип Mauri", Modifier.requiredSize(50.dp), colorFilter = ColorFilter.tint(Accent))
    }
}
fun resourceArtwork(id: Int) = "android.resource://app.melo.music/$id"
fun playlistFallback(playlist: Playlist): String = resourceArtwork(listOf(R.drawable.collection_moon, R.drawable.collection_road, R.drawable.collection_arch)[(playlist.id.hashCode().toLong().let { if (it < 0) -it else it } % 3).toInt()])

@Composable internal fun HomeScreen(library: Library, playback: PlaybackState, model: MeloViewModel) {
    val downloads by model.downloads.collectAsStateWithLifecycle()
    val first = library.playlists.sortedByDescending { it.pinned }.firstOrNull { it.tracks.isNotEmpty() }
    val resumable = playback.track != null || first != null || library.likes.isNotEmpty() || library.history.isNotEmpty()
    val context = if (playback.track != null) playback.context else first?.title ?: "Избранное"
    val cover = if (playback.track != null) library.playlists.find { it.title == context }?.artwork ?: playback.track.artwork else first?.artwork.orEmpty()
    val favorites = Playlist(id = "likes", title = "Избранное", tracks = library.likes, artwork = resourceArtwork(R.drawable.favorites_flowers))
    val collections = buildList {
        first?.let { add(it) }
        add(favorites)
        addAll(library.playlists.sortedByDescending { it.pinned }.filter { it.id != first?.id }.take(3))
    }.take(4)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
        item { Text("С возвращением", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)) }
        item {
            BoxWithConstraints {
            val imageWidth = minOf(maxWidth * .34f, 120.dp)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Panel, Elevated))).border(1.dp, Accent.copy(alpha = .16f), RoundedCornerShape(24.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val image = cover.ifBlank { resourceArtwork(R.drawable.night_drive) }
                Artwork(image, Modifier.width(imageWidth).aspectRatio(1f))
                Column(Modifier.weight(1f).padding(start = 18.dp)) {
                    Text("ПРОДОЛЖИМ?", color = AccentSoft, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 6.dp))
                    Text(if (resumable) context else "Твоя музыка", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (playback.track != null) "С ${playback.index + 1}-го трека" else if (resumable) "Твоя музыка рядом" else "Перенеси плейлист", color = Muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                    Button(onClick = { model.continueListening() }, contentPadding = PaddingValues(horizontal = 20.dp)) {
                        Icon(if (resumable) Icons.Rounded.PlayArrow else Icons.Rounded.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(if (resumable) "Слушать" else "Импорт", fontSize = 13.sp)
                    }
                }
            }
            }
        }
        item { Text("Под рукой", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 20.dp, bottom = 12.dp)) }
        items(collections.chunked(2)) { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { playlist ->
                    val downloaded = playlist.tracks.isNotEmpty() && playlist.tracks.all { track -> track.isLocal || downloads.any { it.track.id == track.id && it.status == OfflineStatus.READY } }
                    Column(Modifier.weight(1f).clickable { model.openPlaylist(playlist.id) }) {
                        Box {
                            Artwork(playlist.artwork.ifBlank { playlist.tracks.firstOrNull()?.artwork.orEmpty() }.ifBlank { playlistFallback(playlist) }, Modifier.fillMaxWidth().aspectRatio(1.16f))
                            if (downloaded) Icon(Icons.Rounded.CheckCircle, "Доступно без сети", tint = Accent, modifier = Modifier.align(Alignment.TopEnd).padding(9.dp).size(20.dp))
                        }
                        Text(playlist.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Text(trackCountLabel(playlist.tracks.size), color = Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { ForYouRow(model) }
        if (library.playlists.isEmpty()) item { TextButton(onClick = { model.tab(Tab.IMPORT) }, modifier = Modifier.fillMaxWidth()) { Text("Перенести плейлист из Spotify") } }
        val mix = library.likes.ifEmpty { library.playlists.flatMap { it.tracks }.distinctBy { it.id } }
        if (mix.isNotEmpty()) item {
            Surface(onClick = { model.playShuffled(mix, true, "Твой свежий микс") }, color = Elevated, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Accent, modifier = Modifier.size(30.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text("Твой свежий микс", style = MaterialTheme.typography.titleMedium)
                        Text("Любимое с меньшим числом повторов", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Icon(Icons.Rounded.PlayArrow, "Запустить свежий микс", tint = Accent)
                }
            }
        }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { AssistChip(onClick = { model.tab(Tab.DOWNLOADS) }, label = { Text("Без сети") }, leadingIcon = { Icon(Icons.Rounded.DownloadDone, null, Modifier.size(18.dp)) }) }
            item { AssistChip(onClick = { model.tab(Tab.STATS) }, label = { Text("Твой ритм") }, leadingIcon = { Icon(Icons.Rounded.BarChart, null, Modifier.size(18.dp)) }) }
        } }
        if (library.history.isNotEmpty()) item { ActionRow(Icons.Rounded.History, "Недавно звучало", trackCountLabel(library.history.size)) { model.openPlaylist("history") } }
        if (library.history.isNotEmpty()) item { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(library.history.take(12), key = { it.id }) { track -> Column(Modifier.width(112.dp).clickable { model.play(library.history, library.history.indexOf(track), context = "Недавно звучало") }) {
                Artwork(track.artwork, Modifier.size(112.dp))
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, style = MaterialTheme.typography.bodySmall)
            } }
        } }
    }
}
