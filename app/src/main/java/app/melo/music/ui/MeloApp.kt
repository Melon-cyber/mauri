@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.melo.music.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.melo.music.*
import app.melo.music.R
import app.melo.music.data.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun MeloApp(model: MeloViewModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val playback by model.playback.collectAsStateWithLifecycle()
    val lyrics by model.lyrics.collectAsStateWithLifecycle()
    val downloads by model.downloads.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(false) }
    var addTrack by remember { mutableStateOf<Track?>(null) }
    var creating by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(false) }
    suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
            val buffer = CharArray(8192); val text = StringBuilder()
            while (true) { val count = it.read(buffer); if (count < 0) break; require(text.length + count <= 10_000_000) { "Файл слишком большой" }; text.append(buffer, 0, count) }
            text.toString()
        } ?: error("Не удалось прочитать файл")
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        runCatching { read(uri) }.onSuccess { model.importCsv(it, "Импорт из CSV") }.onFailure { model.message(it.message) }
    } }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        runCatching { read(uri) }.onSuccess(model::restoreLibrary).onFailure { model.message(it.message) }
    } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) scope.launch {
        runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(model.exportLibrary()) } ?: error("Не удалось сохранить файл") } }
            .onSuccess { model.message("Библиотека сохранена") }.onFailure { model.message(it.message) }
    } }
    val exportStats = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) scope.launch {
        runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(model.exportStatistics()) } ?: error("Не удалось сохранить файл") } }
            .onSuccess { model.message("Копия статистики сохранена") }.onFailure { model.message(it.message) }
    } }
    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) scope.launch {
        runCatching { withContext(Dispatchers.IO) { val text = model.exportEverything(); context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } ?: error("Не удалось сохранить файл") } }
            .onSuccess { model.message("Файл переноса сохранён") }.onFailure { model.message(it.message) }
    } }
    val restoreAll = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        runCatching { read(uri) }.onSuccess(model::restoreEverything).onFailure { model.message(it.message) }
    } }
    val restoreStats = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        runCatching { read(uri) }.onSuccess(model::restoreStatistics).onFailure { model.message(it.message) }
    } }
    val local = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (uris.isNotEmpty()) model.importLocal(uris)
    }
    LaunchedEffect(screen.message) { screen.message?.let { model.message(null); snackbar.showSnackbar(it) } }
    screen.pendingImport?.let { result ->
        PartialSpotifyImportDialog(result, model::dismissPartialImport, model::savePartialImport)
    }
    BackHandler(screen.selectedPlaylist != null) { model.openPlaylist(null) }
    BackHandler(screen.selectedPlaylist == null && screen.tab in listOf(Tab.IMPORT, Tab.DOWNLOADS, Tab.STATS)) { model.tab(Tab.LIBRARY) }
    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        Column(Modifier.background(Ink).navigationBarsPadding()) {
            playback.track?.let { current -> MiniPlayer(playback, model::togglePlay, { model.fullPlayer(true) }, model::next, library.likes.any { it.id == current.id }) { model.like(current) } }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                val icons = listOf(Icons.Rounded.Home, Icons.Rounded.Search, Icons.Rounded.LibraryMusic)
                val labels = listOf("Главная", "Поиск", "Коллекция")
                listOf(Tab.HOME, Tab.SEARCH, Tab.LIBRARY).forEachIndexed { i, tab ->
                    val selected = screen.tab == tab || (tab == Tab.LIBRARY && screen.tab in listOf(Tab.IMPORT, Tab.DOWNLOADS, Tab.STATS))
                    Column(Modifier.weight(1f).clickable { model.tab(tab) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icons[i], if (tab == Tab.SEARCH) "Найти музыку" else null, tint = if (selected) Accent else Muted, modifier = Modifier.size(23.dp))
                        Text(labels[i], fontSize = 12.sp, lineHeight = 16.sp, color = if (selected) Accent else Muted, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().then(if (screen.tab == Tab.HOME) Modifier.drawWithCache {
            val glow = Brush.radialGradient(listOf(Color(0x44362343), Color.Transparent), center = Offset(size.width * .88f, 0f), radius = size.width * 1.1f)
            onDrawBehind { drawRect(glow) }
        } else Modifier)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 10.dp).height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                if (screen.selectedPlaylist != null || screen.tab in listOf(Tab.IMPORT, Tab.DOWNLOADS, Tab.STATS)) IconButton(onClick = { model.openPlaylist(null) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                BrandMark(Modifier.width(40.dp).height(30.dp))
                Text("mauri", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
                if (screen.radioLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Settings, "Настройки", tint = Muted) }
            }
            val playlist = when (screen.selectedPlaylist) {
                "likes" -> Playlist(id = "likes", title = "Избранное", tracks = library.likes)
                "history" -> Playlist(id = "history", title = "Недавно звучало", tracks = library.history)
                "offline" -> Playlist(id = "offline", title = "Скачано", tracks = downloads.filter { it.status == OfflineStatus.READY }.map { it.track })
                else -> library.playlists.find { it.id == screen.selectedPlaylist }
            }
            if (playlist != null) PlaylistScreen(playlist, playback, library, model) { addTrack = it }
            else when (screen.tab) {
                Tab.HOME -> HomeScreen(library, playback, model)
                Tab.SEARCH -> SearchScreen(screen, library, playback, model) { addTrack = it }
                Tab.LIBRARY -> LibraryScreen(library, model, { addTrack = it }) { creating = true }
                Tab.IMPORT -> ImportScreen(screen, model, { csv.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }, { local.launch(arrayOf("audio/*")) })
                Tab.DOWNLOADS -> DownloadsScreen(model)
                Tab.STATS -> StatisticsScreen(model) { addTrack = it }
            }
        }
    }
    if (screen.fullPlayer) FullPlayer(playback, lyrics, library, model) { addTrack = it }
    val recap by model.recap.collectAsStateWithLifecycle()
    recap?.let { YearRecapDialog(it, model::closeRecap) }
    val crash by model.crashReport.collectAsStateWithLifecycle()
    crash?.let { report ->
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(onDismissRequest = model::dismissCrash, title = { Text("Mauri закрылось с ошибкой") },
            text = { Text("Можно отправить отчёт разработчику: в нём версия приложения и Android, модель телефона и техническое описание ошибки. Твоя музыка, поиски и статистика туда не попадают.") },
            confirmButton = { TextButton(onClick = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "Отчёт об ошибке Mauri").putExtra(android.content.Intent.EXTRA_TEXT, report)
                runCatching { context.startActivity(android.content.Intent.createChooser(send, "Отправить отчёт")) }
                model.dismissCrash()
            }) { Text("Поделиться отчётом") } },
            dismissButton = { TextButton(onClick = model::dismissCrash) { Text("Не нужно") } })
    }
    if (queue) QueueSheet(playback, model) { queue = false }
    if (creating) NameDialog("Новый плейлист", { creating = false }) { model.createPlaylist(it); creating = false }
    addTrack?.let { track -> PlaylistPicker(track, library.playlists, { addTrack = null }, { model.addToPlaylist(it, track); addTrack = null }, { title ->
        model.createPlaylistWithTrack(title, track); addTrack = null
    }) }
    if (settings) SettingsSheet(model, { settings = false }, { export.launch("Mauri-library.json") }, { restore.launch(arrayOf("application/json", "text/*")) }, { local.launch(arrayOf("audio/*")) }, { exportStats.launch("Mauri-statistics.json") }, { restoreStats.launch(arrayOf("application/json", "text/*")) }, { exportAll.launch("Mauri-перенос.json") }, { restoreAll.launch(arrayOf("application/json", "text/*")) })
}

@Composable internal fun SectionTitle(title: String, action: String? = null, click: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); if (action != null) TextButton(onClick = click) { Text(action, fontSize = 12.sp) }
    }
}
@Composable private fun QuickTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, click: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Panel).clickable(onClick = click).padding(16.dp)) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(22.dp)); Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp)); Text(subtitle, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}
@Composable private fun SearchScreen(screen: ScreenState, library: Library, playback: PlaybackState, model: MeloViewModel, add: (Track) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val settings by model.settings.collectAsStateWithLifecycle()
    Column {
        Text("Найди своё звучание", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        OutlinedTextField(screen.query, model::query, Modifier.fillMaxWidth().padding(horizontal = 22.dp), placeholder = { Text("Песня, исполнитель или альбом", fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (screen.query.isNotEmpty()) IconButton(onClick = { model.query("") }) { Icon(Icons.Rounded.Close, "Очистить запрос") } }, singleLine = true, shape = RoundedCornerShape(20.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); model.search() }))
        if (screen.query.isNotBlank()) TextButton(onClick = { keyboard?.hide(); model.search() }, modifier = Modifier.align(Alignment.End).padding(end = 22.dp)) { Text("Найти") }
        LazyColumn(contentPadding = PaddingValues(vertical = 14.dp)) {
            if (screen.searchedQuery.isBlank()) {
                if (settings.recentSearches.isNotEmpty()) {
                    item { SectionTitle("Недавние запросы", "Очистить", model::clearRecentSearches) }
                    items(settings.recentSearches) { query -> ActionRow(Icons.Rounded.History, query, "Повторить поиск", Modifier.padding(horizontal = 24.dp)) { model.search(query) } }
                } else item { EmptyState(Icons.Rounded.GraphicEq, "Что включим?", "Найди любимую песню или открой для себя что-то новое.") }
            }
            screen.searchError?.let { error -> item { EmptyState(Icons.Rounded.CloudOff, "Поиск недоступен", error, "Повторить") { model.search() } } }
            itemsIndexed(screen.results, key = { _, t -> t.id }) { i, track -> SongRow(track, playback.track?.id == track.id, library, model, { model.play(screen.results, i, context = "Поиск · ${screen.searchedQuery}") }, add) }
            if (screen.searching) item { Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else if (screen.hasMore) item { TextButton(onClick = model::loadMore, modifier = Modifier.fillMaxWidth()) { Text("Показать ещё") } }
            else if (screen.searchedQuery.isNotBlank() && screen.results.isEmpty() && screen.searchError == null) item { EmptyState(Icons.Rounded.SearchOff, "Ничего не нашлось", "Попробуй другое название или имя исполнителя.") }
        }
    }
}

@Composable private fun LibraryScreen(library: Library, model: MeloViewModel, add: (Track) -> Unit, create: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(CollectionFilter.ALL) }
    var order by remember { mutableStateOf(CollectionOrder.RECENT) }
    var sorting by remember { mutableStateOf(false) }
    val downloads by model.downloads.collectAsStateWithLifecycle()
    val ready = remember(downloads) { downloads.filter { it.status == OfflineStatus.READY }.map { it.track.id }.toSet() }
    val lists = remember(library, query, filter, order, ready) { library.collectionPlaylists(query, filter, order, ready) }
    val songs = remember(library, query, filter, ready) { library.collectionTracks(query, filter, ready, downloads.filter { it.status == OfflineStatus.READY }.map { it.track }) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Твоя коллекция", "Создать", create) }
        item { FlowRow(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { model.tab(Tab.DOWNLOADS) }, label = { Text("Загрузки") }, leadingIcon = { Icon(Icons.Rounded.DownloadDone, null, Modifier.size(18.dp)) })
            AssistChip(onClick = { model.tab(Tab.STATS) }, label = { Text("Твой ритм") }, leadingIcon = { Icon(Icons.Rounded.BarChart, null, Modifier.size(18.dp)) })
            AssistChip(onClick = { model.tab(Tab.IMPORT) }, label = { Text("Импорт") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) })
        } }
        item { Row(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickTile("Избранное", trackCountLabel(library.likes.size), Icons.Rounded.Favorite, Modifier.weight(1f)) { model.openPlaylist("likes") }
            QuickTile("История", trackCountLabel(library.history.size), Icons.Rounded.History, Modifier.weight(1f)) { model.openPlaylist("history") }
        } }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 22.dp), singleLine = true, placeholder = { Text("Песня, исполнитель, плейлист") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Очистить поиск в коллекции") } }, shape = RoundedCornerShape(18.dp)) }
        item { androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CollectionFilter.entries) { option -> FilterChip(filter == option, { filter = option }, label = { Text(option.label) }) }
        } }
        item { Box(Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = { sorting = true }) { Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(order.label) }
            DropdownMenu(sorting, { sorting = false }) { CollectionOrder.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { order = option; sorting = false }) } }
        } }
        items(lists, key = { it.id }) { PlaylistRow(it) { model.openPlaylist(it.id) } }
        if (query.isNotBlank() || filter == CollectionFilter.DOWNLOADED) {
            if (songs.isNotEmpty()) item { Text("Песни · ${songs.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp)) }
            itemsIndexed(songs, key = { _, t -> "song-${t.id}" }) { i, track -> SongRow(track, false, library, model, { model.play(songs, i, context = "Твоя коллекция") }, add) }
        }
        if (lists.isEmpty() && songs.isEmpty() && (query.isNotBlank() || filter != CollectionFilter.ALL)) item { EmptyState(Icons.Rounded.SearchOff, "Ничего не найдено в коллекции", "Попробуй другой запрос или фильтр. Скачанные песни также доступны в разделе «Загрузки».") }
        else if (lists.isEmpty() && query.isBlank() && filter == CollectionFilter.ALL) item { EmptyState(Icons.Rounded.LibraryMusic, "Место для твоей музыки", "Создай плейлист или перенеси его из Spotify.", "Импортировать") { model.tab(Tab.IMPORT) } }
    }
}
@Composable private fun PlaylistRow(playlist: Playlist, click: () -> Unit) {
    Row(Modifier.padding(horizontal = 22.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = click).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(playlist.artwork.ifBlank { playlist.tracks.firstOrNull()?.artwork.orEmpty() }, Modifier.size(62.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(playlist.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(trackCountLabel(playlist.tracks.size), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        if (playlist.pinned) Icon(Icons.Rounded.PushPin, "Закреплено", tint = Accent, modifier = Modifier.size(18.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
    }
}

@Composable private fun PlaylistScreen(playlist: Playlist, playback: PlaybackState, library: Library, model: MeloViewModel, add: (Track) -> Unit) {
    var query by remember(playlist.id) { mutableStateOf("") }
    var sort by remember(playlist.id) { mutableStateOf(TrackSort.ORIGINAL) }
    var menu by remember { mutableStateOf(false) }; var sorting by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }; var deleting by remember { mutableStateOf(false) }
    // One shuffle state for the whole app, as in Spotify: «Слушать» starts the playlist in it.
    val listening by model.settings.collectAsStateWithLifecycle()
    val mode = if (playback.track != null) playback.mode else listening.queueMode
    val editable = playlist.id !in listOf("likes", "history", "offline")
    val tracks = remember(playlist.tracks, query, sort) { playlist.tracks.filterAndSort(query, sort) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF262035), Ink))).padding(horizontal = 24.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(playlist.artwork.ifBlank { playlist.tracks.firstOrNull()?.artwork.orEmpty() }, Modifier.size(118.dp), if (playlist.id == "likes") Icons.Rounded.Favorite else Icons.Rounded.Album)
                Column(Modifier.weight(1f).padding(start = 20.dp)) { Text(if (playlist.pinned) "ЗАКРЕПЛЕНО" else "ТВОЯ КОЛЛЕКЦИЯ", color = Accent, style = MaterialTheme.typography.labelSmall); Text(playlist.title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)); Text(trackCountLabel(playlist.tracks.size), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.playInMode(tracks, playlist.title, mode) }, enabled = tracks.isNotEmpty(), contentPadding = PaddingValues(horizontal = 16.dp)) { Icon(Icons.Rounded.PlayArrow, null); Text("Слушать", fontSize = 12.sp) }
                ShuffleModeButton(mode) { model.chooseMode(it) }
                if (editable) Box { IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "Меню плейлиста") }; DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; rename = true })
                    DropdownMenuItem(text = { Text(if (playlist.pinned) "Открепить" else "Закрепить") }, onClick = { menu = false; model.pinPlaylist(playlist.id) })
                    DropdownMenuItem(text = { Text("Создать копию") }, onClick = { menu = false; model.duplicatePlaylist(playlist) })
                    DropdownMenuItem(text = { Text("Рекомендации по плейлисту") }, enabled=playlist.tracks.any { !it.isLocal }, onClick = {menu=false;model.playlistRadio(playlist)})
                    if (playlist.sourceUrl.startsWith("https://open.spotify.com/playlist/")) DropdownMenuItem(text = { Text("Обновить из Spotify") }, onClick = { menu = false; model.receiveShare(playlist.sourceUrl) })
                    DropdownMenuItem(text = { Text("Удалить плейлист") }, onClick = { menu = false; deleting = true })
                } }
            }
            if (playlist.importNote.isNotBlank()) Text(playlist.importNote, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
            if (playlist.tracks.any { !it.isLocal }) PlaylistDownloadAction(playlist, model)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 18.dp), placeholder = { Text("Найти в плейлисте", fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box { TextButton(onClick = { sorting = true }) { Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(sort.label, fontSize = 12.sp) }; DropdownMenu(sorting, { sorting = false }) { TrackSort.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { sort = option; sorting = false }) } } }
                Spacer(Modifier.weight(1f)); Text(mode.label, color = if (mode == QueueMode.ORDERED) Muted else Accent, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            }
        } }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, track -> SongRow(track, playback.track?.id == track.id, library, model, { model.play(tracks, i, context = playlist.title) }, add,
            remove = if (editable) ({ model.removeFromPlaylist(playlist.id, track.id) }) else null,
            up = if (editable && query.isBlank() && sort == TrackSort.ORIGINAL && i > 0) ({ model.movePlaylistTrack(playlist.id, track.id, -1) }) else null,
            down = if (editable && query.isBlank() && sort == TrackSort.ORIGINAL && i < tracks.lastIndex) ({ model.movePlaylistTrack(playlist.id, track.id, 1) }) else null) }
        if (tracks.isEmpty()) item { EmptyState(Icons.Rounded.MusicNote, "Здесь пока тихо", if (query.isBlank()) "Добавь песни из поиска через меню трека." else "Попробуй другой запрос.") }
    }
    if (rename) NameDialog("Название плейлиста", { rename = false }, playlist.title) { model.renamePlaylist(playlist.id, it); rename = false }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Удалить плейлист?") }, text = { Text(playlist.title) }, confirmButton = { TextButton(onClick = { model.deletePlaylist(playlist.id); deleting = false }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } })
}

@Composable private fun ImportScreen(screen: ScreenState, model: MeloViewModel, csv: () -> Unit, local: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Музыка\nпереезжает с тобой", style = MaterialTheme.typography.headlineLarge)
        Text("Из Spotify — в Mauri", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
        Text("Вставь ссылку на публичный плейлист Spotify, Яндекс Музыки или Apple Music. Mauri перенесёт список песен и найдёт записи на YouTube при воспроизведении.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(screen.importUrl, model::importUrl, Modifier.fillMaxWidth().padding(top = 22.dp), label = { Text("Ссылка на плейлист") }, singleLine = true, enabled = !screen.importing, shape = RoundedCornerShape(18.dp))
        screen.importError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
        if (screen.importing && screen.importProgress.isNotBlank()) Text(screen.importProgress, color = Muted, modifier = Modifier.padding(top = 12.dp))
        Button(onClick = { keyboard?.hide(); model.importSpotify() }, enabled = screen.importUrl.isNotBlank() && !screen.importing, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp)) { if (screen.importing) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }; Text(if (screen.importing) "Получаем песни…" else "Перенести в Mauri") }
        Text("Пароль не нужен. Mauri загружает все части списка и проверяет их количество. Если часть недоступна, ты увидишь предупреждение перед сохранением. Закрытые плейлисты недоступны.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 20.dp))
        ActionRow(Icons.Rounded.TableChart, "Импортировать CSV", "Список песен из Exportify и похожих сервисов", click = csv)
        ActionRow(Icons.Rounded.AudioFile, "Добавить с телефона", "Твои аудиофайлы, доступные без интернета", click = local)
        Text("В Spotify также можно выбрать «Поделиться» → Mauri.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp))
    }
}

@Composable internal fun Artwork(url: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.MusicNote) {
    val app = LocalContext.current.applicationContext as MeloApplication
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Elevated), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.night_drive), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .45f)
        Icon(icon, null, tint = SoftWhite.copy(alpha = .65f), modifier = Modifier.fillMaxSize(.28f))
        val resource = url.takeIf { it.startsWith("android.resource://app.melo.music/") }?.substringAfterLast('/')?.toIntOrNull()
        if (resource != null) Image(painterResource(resource), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (url.isNotBlank()) AsyncImage(app.offline.cover(url), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
@Composable internal fun SongRow(track: Track, active: Boolean, library: Library, model: MeloViewModel, play: () -> Unit, add: (Track) -> Unit, remove: (() -> Unit)? = null, up: (() -> Unit)? = null, down: (() -> Unit)? = null) {
    val liked = library.likes.any { it.id == track.id }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(if (active) Accent.copy(alpha = .07f) else Color.Transparent).clickable(onClick = play).padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.artwork, Modifier.size(52.dp)); Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, color = if (active) Accent else SoftWhite, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        if (liked) Icon(Icons.Rounded.Favorite, "В избранном", tint = Accent, modifier = Modifier.size(14.dp))
        Box { IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "Меню: ${track.title}", tint = Muted) }; DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(if (liked) "Убрать из избранного" else "В избранное") }, onClick = { model.like(track); menu = false })
            DropdownMenuItem(text = { Text("Слушать следующим") }, onClick = { model.playNext(track); menu = false })
            DropdownMenuItem(text = { Text("В конец очереди") }, onClick = { model.queue(track); menu = false })
            if (!track.isLocal) DropdownMenuItem(text = { Text("Радио по песне") }, onClick = { model.radio(track); menu = false })
            if (!track.isLocal) DropdownMenuItem(text = { Text("Скачать без сети") }, onClick = { model.download(track); menu = false })
            DropdownMenuItem(text = { Text("Добавить в плейлист") }, onClick = { add(track); menu = false })
            if (up != null) DropdownMenuItem(text = { Text("Выше в плейлисте") }, onClick = { up(); menu = false })
            if (down != null) DropdownMenuItem(text = { Text("Ниже в плейлисте") }, onClick = { down(); menu = false })
            if (!track.isLocal) DropdownMenuItem(text = { Text("Поделиться") }, onClick = {
                val link = when {
                    track.id.startsWith("yandex:") -> "https://music.yandex.ru/track/${track.id.substringAfter(':')}"
                    track.id.startsWith("apple:") -> "https://music.apple.com/song/${track.id.substringAfter(':')}"
                    track.id.startsWith("csv:") -> track.url.ifBlank { "" }
                    track.source == "spotify" -> "https://open.spotify.com/track/${track.id.substringAfterLast(':')}"
                    else -> track.url
                }
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${track.artist} — ${track.title}\n$link"), "Поделиться песней")); menu = false
            })
            if (remove != null) DropdownMenuItem(text = { Text("Убрать из плейлиста") }, onClick = { remove(); menu = false })
        } }
    }
}
@Composable private fun MiniPlayer(state: PlaybackState, toggle: () -> Unit, open: () -> Unit, next: () -> Unit, liked: Boolean, like: () -> Unit) {
    val track = state.track ?: return
    Column(Modifier.fillMaxWidth().background(Panel).clickable(onClick = open)) {
        Row(Modifier.padding(start = 9.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.artwork, Modifier.size(44.dp)); Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (state.error != null) "Не удалось воспроизвести" else track.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = like) { Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Убрать текущую песню из избранного" else "Текущая песня в избранное", tint = if (liked) Accent else Muted, modifier = Modifier.size(21.dp)) }
            IconButton(onClick = toggle) { if (state.buffering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Пауза" else "Воспроизвести") }
            IconButton(onClick = next) { Icon(Icons.Rounded.SkipNext, "Следующий трек") }
        }
        LinearProgressIndicator(progress = { if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Accent, trackColor = Color.Transparent)
    }
}
@Composable internal fun EmptyState(icon: ImageVector, title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(34.dp)); Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp)); Text(description, color = Muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
@Composable internal fun ActionRow(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier, click: () -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = click).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Elevated), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Accent, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text(description, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }; Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
    }
}
@Composable internal fun NameDialog(title: String, dismiss: () -> Unit, initial: String = "", save: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(name, { name = it.take(120) }, singleLine = true, label = { Text("Название") }) }, confirmButton = { TextButton(onClick = { save(name.trim()) }, enabled = name.isNotBlank()) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
@Composable private fun PlaylistPicker(track: Track, playlists: List<Playlist>, dismiss: () -> Unit, select: (String) -> Unit, create: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) NameDialog("Новый плейлист", { creating = false }, save = create)
    else ModalBottomSheet(onDismissRequest = dismiss, containerColor = Panel) {
        Text("Добавить в плейлист", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text(track.title, color = Muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.heightIn(max = 400.dp).padding(horizontal = 24.dp)) {
            item { ActionRow(Icons.Rounded.Add, "Новый плейлист", "Создать и добавить песню") { creating = true } }
            items(playlists, key = { it.id }) { playlist -> ActionRow(Icons.Rounded.LibraryMusic, playlist.title, trackCountLabel(playlist.tracks.size)) { select(playlist.id) } }
        }
        Spacer(Modifier.height(24.dp))
    }
}
/** Public source repository (GPL: the app links to its own source). */
const val SOURCE_URL = "https://github.com/Melon-cyber/mauri"
@Composable private fun SettingsSheet(model: MeloViewModel, dismiss: () -> Unit, export: () -> Unit, restore: () -> Unit, local: () -> Unit, exportStats: () -> Unit, restoreStats: () -> Unit, exportAll: () -> Unit = {}, restoreAll: () -> Unit = {}) {
    val settings by model.settings.collectAsStateWithLifecycle()
    var lyricSettings by remember { mutableStateOf(false) }
    if (lyricSettings) LyricSettingsSheet(model, dismiss = { lyricSettings = false })
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Panel, sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { BrandMark(Modifier.width(40.dp).height(30.dp)); Text("mauri", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 12.dp)); Spacer(Modifier.weight(1f)); Text(BuildConfig.VERSION_NAME, color = Muted) }
            Text("Настройки", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top=18.dp,bottom=10.dp))
            Text("При запуске",style=MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { StartScreen.entries.forEach { value ->
                FilterChip(settings.startScreen == value,{model.startScreen(value)},label={Text(value.label)})
            } }
            ActionRow(Icons.Rounded.Lyrics, "Оформление текста", "Фон, размер, движение и автопрокрутка") { lyricSettings = true }
            HorizontalDivider(Modifier.padding(vertical=12.dp))
            Text("Загрузки и воспроизведение",style=MaterialTheme.typography.titleLarge)
            PreferenceSwitch("Загружать по Wi-Fi", "Применяется к новым загрузкам", settings.wifiDownloads, model::wifiDownloads)
            ActionRow(Icons.Rounded.BarChart, "Твой ритм", "Минуты, песни и исполнители") { dismiss(); model.tab(Tab.STATS) }
            ActionRow(Icons.Rounded.DownloadDone, "Музыка без сети", "Загрузки и занятое место") { dismiss(); model.tab(Tab.DOWNLOADS) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Собирать статистику", style = MaterialTheme.typography.titleMedium); Text("Только на этом телефоне", color = Muted, fontSize = 12.sp) }; Switch(settings.statisticsEnabled, model::statisticsEnabled) }
            AutoplaySetting(settings.autoplay, model::autoplay)
            SpeedSetting(settings.speed, model::speed)
            HorizontalDivider(Modifier.padding(vertical=12.dp))
            Text("Звук",style=MaterialTheme.typography.titleLarge)
            Text("Плавный переход",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=10.dp))
            Text("Конец песни затихает, следующая плавно нарастает",color=Muted,fontSize=12.sp)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(0,2,4,6,8).forEach { value ->
                FilterChip(settings.crossfadeSeconds==value,{model.crossfade(value)},label={Text(if(value==0) "Выкл" else "$value с")})
            } }
            PreferenceSwitch("Выравнивать громкость", "Тихие и громкие песни звучат ровнее", settings.normalizeVolume, model::normalizeVolume)
            Text("Эквалайзер",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=10.dp))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { EqPreset.entries.forEach { value ->
                FilterChip(settings.equalizer==value,{model.equalizer(value)},label={Text(value.label)})
            } }
            HorizontalDivider(Modifier.padding(vertical=12.dp))
            Text("Данные и резервные копии",style=MaterialTheme.typography.titleLarge)
            ActionRow(Icons.Rounded.PhoneAndroid, "Перенос на новый телефон", "Библиотека, статистика и настройки одним файлом", click = exportAll)
            ActionRow(Icons.Rounded.SystemUpdate, "Восстановить из файла переноса", "Добавит всё из файла к тому, что уже есть", click = restoreAll)
            ActionRow(Icons.Rounded.SaveAlt, "Сохранить библиотеку", "Плейлисты и избранное в одном файле", click = export)
            ActionRow(Icons.Rounded.Restore, "Восстановить библиотеку", "Объединить с копией Mauri или Melo", click = restore)
            ActionRow(Icons.Rounded.BarChart, "Сохранить статистику", "Минуты и прослушивания по дням в отдельном файле", click = exportStats)
            ActionRow(Icons.Rounded.Restore, "Восстановить статистику", "Повторный импорт копии не удваивает минуты", click = restoreStats)
            ActionRow(Icons.Rounded.AudioFile, "Добавить с телефона", "Аудиофайлы для прослушивания без сети", click = local)
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text("Твоя приватность", style = MaterialTheme.typography.titleLarge)
            Text("Библиотека, история, загрузки и статистика хранятся на телефоне. Регистрации, рекламных SDK и отправки статистики разработчику нет.\n\nПоиск, аудио и радио обращаются к YouTube/Google; импорт — к Spotify; тексты — к LRCLIB и YouTube Music. Эти сервисы получают IP и соответствующие запросы. Обложки загружаются с серверов источников.\n\nПоиск и новые загрузки зависят от источника и сети. Готовые скачанные записи открываются без сети. Текст найден не для каждой песни.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 14.dp))
            val context = LocalContext.current
            ActionRow(Icons.Rounded.Code, "Исходный код", "Открытый проект на GitHub: ошибки, идеи, новые версии") {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SOURCE_URL))) }
            }
            Text("Открытый проект · GPL-3.0-or-later\nNewPipe Extractor · Media3 · LRCLIB · Manrope\nНе связан с YouTube, Spotify или LRCLIB.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
