@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.melo.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.melo.music.*
import app.melo.music.data.*
import java.time.format.DateTimeFormatter
import java.util.Locale

fun bytesLabel(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) "%.1f ГБ".format(bytes / (1024.0 * 1024 * 1024)) else "%.1f МБ".format(bytes / (1024.0 * 1024))
fun listeningLabel(ms: Long): String { val minutes = ms / 60000; return if (minutes >= 60) "${minutes / 60} ч ${minutes % 60} мин" else "$minutes мин" }

@Composable internal fun PlaylistDownloadAction(playlist: Playlist, model: MeloViewModel) {
    val downloads by model.downloads.collectAsStateWithLifecycle()
    val online = playlist.tracks.filterNot { it.isLocal }
    val ready = online.count { track -> downloads.any { it.track.id == track.id && it.status == OfflineStatus.READY } }
    TextButton(onClick = { if (ready == online.size) model.tab(Tab.DOWNLOADS) else model.downloadAll(online) }, modifier = Modifier.fillMaxWidth()) {
        Icon(if (ready == online.size) Icons.Rounded.DownloadDone else Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(if (ready == online.size) "Доступно без сети" else if (ready > 0) "Скачать оставшиеся · $ready из ${online.size} готово" else "Скачать плейлист", fontSize = 12.sp)
    }
}

@Composable internal fun DownloadsScreen(model: MeloViewModel) {
    val downloads by model.downloads.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val ready = downloads.filter { it.status == OfflineStatus.READY }
    var clear by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text("Музыка без сети", style = MaterialTheme.typography.headlineMedium)
            Text("${trackCountLabel(ready.size)} · ${bytesLabel(ready.sumOf { it.bytes })}", color = Muted, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Загружать по Wi-Fi", style = MaterialTheme.typography.titleMedium); Text("Для новых загрузок", color = Muted, fontSize = 12.sp) }; Switch(settings.wifiDownloads, model::wifiDownloads)
            }
            if (ready.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = { model.openPlaylist("offline") }) { Icon(Icons.Rounded.PlayArrow, null); Text("Слушать") }
                TextButton(onClick = { clear = true }) { Text("Освободить место") }
            }
        } }
        if (downloads.isEmpty()) item { EmptyState(Icons.Rounded.Download, "Возьми музыку с собой", "Открой меню песни или плейлиста и выбери «Скачать». Готовые записи будут доступны без интернета.", "Найти музыку") { model.tab(Tab.SEARCH) } }
        items(downloads, key = { it.track.id }) { entry ->
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(entry.track.artwork, Modifier.size(50.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(entry.track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when (entry.status) {
                        OfflineStatus.READY -> "Скачано · ${bytesLabel(entry.bytes)}"
                        OfflineStatus.RESOLVING -> "Подготавливаем запись…"
                        OfflineStatus.WAITING -> "В очереди · ожидаем сеть"
                        OfflineStatus.DOWNLOADING -> if (entry.total > 0) "${entry.bytes * 100 / entry.total}% · ${bytesLabel(entry.bytes)}" else bytesLabel(entry.bytes)
                        OfflineStatus.FAILED -> entry.error
                    }, color = if (entry.status == OfflineStatus.FAILED) MaterialTheme.colorScheme.error else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    if (entry.status == OfflineStatus.DOWNLOADING) LinearProgressIndicator(progress = { if (entry.total > 0) (entry.bytes.toFloat() / entry.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp))
                    if (entry.status == OfflineStatus.FAILED) TextButton(onClick = { model.download(entry.track) }) { Text("Повторить") }
                }
                IconButton(onClick = { model.removeDownload(entry.track.id) }) { Icon(if (entry.status == OfflineStatus.READY) Icons.Rounded.DeleteOutline else Icons.Rounded.Close, if (entry.status == OfflineStatus.READY) "Удалить скачанную копию: ${entry.track.title}" else "Отменить загрузку: ${entry.track.title}", tint = Muted) }
            }
        }
        if (downloads.isNotEmpty()) item { Text("Удаление скачанной копии оставляет песню в коллекции. После подготовки файлы скачиваются средствами Android, даже когда Mauri закрыт.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(24.dp)) }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Удалить скачанные копии?") }, text = { Text("Освободится ${bytesLabel(ready.sumOf { it.bytes })}. Песни и плейлисты останутся в коллекции.") }, confirmButton = { TextButton(onClick = { ready.forEach { model.removeDownload(it.track.id) }; clear = false }) { Text("Удалить копии") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Отмена") } })
}

@Composable internal fun StatisticsScreen(model: MeloViewModel, add: (Track) -> Unit) {
    val stats by model.statistics.collectAsStateWithLifecycle()
    val period by model.statsPeriod.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    var clear by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text("Твой ритм", style = MaterialTheme.typography.headlineLarge)
            Text("Музыка, которая была рядом", color = Muted, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            ActionRow(Icons.Rounded.AutoAwesome, "Итоги года", "Твой год в музыке: минуты, песня и исполнитель года") { model.loadRecap() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatsPeriod.entries.forEach { option -> FilterChip(period == option, { model.statsPeriod(option) }, label = { Text(option.label) }) } }
            if (!settings.statisticsEnabled) Text("Учёт выключен в настройках. Уже собранные данные сохранены.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        } }
        if (stats.milliseconds == 0L) item { EmptyState(Icons.Rounded.GraphicEq, "У каждой песни есть история", "Начни слушать — здесь появятся минуты, самые прослушиваемые песни и исполнители. Учёт начинается с этой версии, данные остаются на телефоне.", "Включить музыку", model::continueListening) }
        else {
            item { Column(Modifier.padding(horizontal = 24.dp)) {
                Text(listeningLabel(stats.milliseconds), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Accent)
                Text("${stats.plays} прослушиваний · ${trackCountLabel(stats.uniqueTracks)}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
                val max = stats.days.maxOfOrNull { it.milliseconds }?.coerceAtLeast(1) ?: 1
                if (period == StatsPeriod.ALL) Text("Активность за последние 7 дней", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
                Row(Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) { stats.days.forEach { day ->
                    Column(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "${day.day}: ${listeningLabel(day.milliseconds)}" }, verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height((72 * day.milliseconds.toFloat() / max).coerceAtLeast(2f).dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(if (day.milliseconds > 0) Accent else Elevated))
                        if (stats.days.size <= 7) Text(day.day.format(DateTimeFormatter.ofPattern("EE", Locale("ru"))), color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                } }
            } }
            item { SectionTitle("На повторе") }
            items(stats.tracks, key = { it.track.id }) { item ->
                SongRow(item.track, false, library, model, { model.play(stats.tracks.map { it.track }, stats.tracks.indexOf(item), context = "Твой ритм") }, add)
                Text("${listeningLabel(item.milliseconds)} · ${item.plays} прослушиваний", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 86.dp, bottom = 10.dp))
            }
            item { SectionTitle("Твои исполнители") }
            items(stats.artists, key = { it.artist }) { artist -> Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) { Text(artist.artist, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Text(listeningLabel(artist.milliseconds), color = Muted, fontSize = 12.sp) } }
            item { Text("Считается реальное время звучания. Прослушивание засчитывается после 30 секунд или половины короткой песни. Паузы и перемотки не добавляют минуты.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(24.dp)) }
            item { TextButton(onClick = { clear = true }, modifier = Modifier.fillMaxWidth()) { Text("Очистить статистику") } }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Очистить статистику?") }, text = { Text("Минуты и история прослушиваний в этом разделе будут удалены. Музыка и плейлисты сохранятся.") }, confirmButton = { TextButton(onClick = { model.clearStatistics(); clear = false }) { Text("Очистить") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Отмена") } })
}
