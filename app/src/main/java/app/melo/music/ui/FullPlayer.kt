@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.melo.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.melo.music.*
import app.melo.music.data.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable internal fun FullPlayer(state: PlaybackState, lyrics: LyricsState, library: Library, model: MeloViewModel, onAdd: (Track) -> Unit) {
    val track = state.track ?: return
    val settings by model.settings.collectAsStateWithLifecycle()
    val downloads by model.downloads.collectAsStateWithLifecycle()
    var showLyrics by remember { mutableStateOf(false) }; var queue by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(false) }; var options by remember { mutableStateOf(false) }; var versions by remember { mutableStateOf(false) }
    val liked = library.likes.any { it.id == track.id }
    val palette = rememberCoverPalette(track)
    val tint = palette.base
    Dialog(onDismissRequest = { model.fullPlayer(false) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { if (showLyrics) showLyrics = false else model.fullPlayer(false) }
        CompositionLocalProvider(LocalContentColor provides SoftWhite) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, Ink, Ink)))) {
            AnimatedVisibility(showLyrics, enter = fadeIn(tween(320)) + slideInVertically(tween(380)) { it / 6 }, exit = fadeOut(tween(220)) + slideOutVertically(tween(260)) { it / 8 }) {
                LyricsStage(state, lyrics, library, settings, model, palette) { showLyrics = false }
            }
            if (!showLyrics) Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.fullPlayer(false) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть плеер") }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("СЕЙЧАС ЗВУЧИТ", color = Accent, style = MaterialTheme.typography.labelSmall)
                        Text(state.context, color = SoftWhite.copy(alpha = .65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                    }
                    IconButton(onClick = { options = true }) { Icon(Icons.Rounded.MoreHoriz, "Параметры песни") }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Artwork(track.artwork, Modifier.size(minOf(maxWidth, maxHeight, 440.dp)).clip(RoundedCornerShape(26.dp)))
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(track.title, color = SoftWhite, fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        if (track.isLocal || downloads.any { it.track.id == track.id && it.status == OfflineStatus.READY }) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.DownloadDone, null, tint = AccentSoft, modifier = Modifier.size(14.dp))
                            Text(" Доступно без сети", color = AccentSoft, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = { model.like(track) }) { Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Убрать из избранного" else "В избранное", tint = if (liked) Accent else SoftWhite) }
                }
                state.error?.let { error -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF452C35)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = Color(0xFFFFDAD5), fontSize = 12.sp, modifier = Modifier.weight(1f)); TextButton(onClick = model::retry) { Text("Ещё раз") }
                } }
                SeekBar(state, model::seek, model::seekBy)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    ShuffleModeButton(state.mode) { model.chooseMode(it) }
                    IconButton(onClick = model::previous, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipPrevious, "Предыдущий трек", modifier = Modifier.size(36.dp)) }
                    FilledIconButton(onClick = model::togglePlay, modifier = Modifier.size(72.dp)) { if (state.buffering) CircularProgressIndicator(Modifier.size(28.dp), color = Ink) else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Пауза" else "Воспроизвести", modifier = Modifier.size(38.dp)) }
                    IconButton(onClick = model::next, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipNext, "Следующий трек", modifier = Modifier.size(36.dp)) }
                    IconButton(onClick = model::repeat) { Icon(if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, when (state.repeat) { Player.REPEAT_MODE_ONE -> "Повтор одного трека"; Player.REPEAT_MODE_ALL -> "Повтор всей очереди"; else -> "Повтор выключен" }, tint = if (state.repeat == Player.REPEAT_MODE_OFF) Muted else Accent) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { queue = true }) { Text(state.mode.label, color = if (state.mode == QueueMode.ORDERED) Muted else Accent, fontSize = 12.sp) }
                    TextButton(onClick = { timer = true }) { Icon(Icons.Rounded.Bedtime, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(timerLabel(state), fontSize = 12.sp) }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(showLyrics, { showLyrics = !showLyrics }, label = { Text("Текст песни", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Rounded.Lyrics, null, Modifier.size(18.dp)) }, modifier = Modifier.weight(1f))
                    FilterChip(queue, { queue = true }, label = { Text("Очередь · ${state.queue.size}", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(18.dp)) }, modifier = Modifier.weight(1f))
                }
            }
        }
        }
        if (queue) QueueSheet(state, model) { queue = false }
        if (timer) TimerSheet(state, model) { timer = false }
        if (versions) VersionsSheet(track, model) { versions = false }
        if (options) ModalBottomSheet(onDismissRequest = { options = false }, containerColor = Panel) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ActionRow(Icons.Rounded.PlaylistAdd, "Добавить в плейлист", "Сохранить в коллекции") { onAdd(track); options = false }
                if (!track.isLocal) ActionRow(Icons.Rounded.SwapHoriz, "Другая версия записи", "Если играет не та версия песни") { versions = true; options = false }
                if (!track.isLocal) ActionRow(Icons.Rounded.Radio, "Радио по песне", "Другие песни этого исполнителя") { model.radio(track); options = false }
                if (!track.isLocal) {
                    val entry = downloads.find { it.track.id == track.id }
                    ActionRow(Icons.Rounded.Download, if (entry?.status == OfflineStatus.READY) "Скачано без сети" else "Скачать песню", if (entry?.status == OfflineStatus.READY) "Управление скачанной копией" else "Сохранить на этом телефоне") {
                        if (entry?.status == OfflineStatus.READY) { model.fullPlayer(false); model.tab(Tab.DOWNLOADS) } else model.download(track)
                        options = false
                    }
                }
                SpeedSetting(settings.speed, model::speed)
            }
        }
    }
}

@Composable internal fun SeekBar(state: PlaybackState, seek: (Long) -> Unit, seekBy: (Long) -> Unit) {
    var dragging by remember(state.track?.id) { mutableStateOf<Float?>(null) }
    val max = state.duration.coerceAtLeast(1).toFloat()
    Slider((dragging ?: state.position.toFloat()).coerceIn(0f, max), { dragging = it }, onValueChangeFinished = { dragging?.let { seek(it.toLong()) }; dragging = null }, valueRange = 0f..max, enabled = state.duration > 0, modifier = Modifier.fillMaxWidth().height(38.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(durationLabel(dragging?.toLong() ?: state.position), color = Muted, fontSize = 11.sp)
        IconButton(onClick = { seekBy(-10000) }, enabled = state.duration > 0) { Icon(Icons.Rounded.Replay10, "Назад на 10 секунд", tint = Muted, modifier = Modifier.size(22.dp)) }
        IconButton(onClick = { seekBy(10000) }, enabled = state.duration > 0) { Icon(Icons.Rounded.Forward10, "Вперёд на 10 секунд", tint = Muted, modifier = Modifier.size(22.dp)) }
        Text(durationLabel(state.duration), color = Muted, fontSize = 11.sp)
    }
}

@Composable internal fun QueueSheet(state: PlaybackState, model: MeloViewModel, dismiss: () -> Unit) {
    val settings by model.settings.collectAsStateWithLifecycle()
    var save by remember { mutableStateOf(false) }
    val ordered = state.order.ifEmpty { state.queue.indices.toList() }
    val at = ordered.indexOf(state.index).coerceAtLeast(0)
    val upcoming = ordered.drop(at + 1)
    // Drag and drop: the list is reordered locally while the finger moves; the player gets one move on release.
    val upcomingUids = upcoming.map { state.entries.getOrNull(it)?.uid ?: "i$it" }
    var local by remember(upcomingUids) { mutableStateOf(upcomingUids) }
    val byUid = remember(upcomingUids, state.queue) { upcoming.associateBy { state.entries.getOrNull(it)?.uid ?: "i$it" } }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragStart by remember { mutableIntStateOf(-1) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun moveDragged(dy: Float) {
        val uid = dragging ?: return
        dragOffset += dy
        val info = list.layoutInfo.visibleItemsInfo
        val self = info.firstOrNull { it.key == uid } ?: return
        val center = self.offset + dragOffset + self.size / 2f
        val target = info.firstOrNull { it.key in local && it.key != uid && center.toInt() in it.offset until it.offset + it.size } ?: run {
            // Near the edges, scroll so the song can travel further than the screen.
            val viewport = list.layoutInfo.viewportEndOffset
            if (center > viewport - 140) scope.launch { list.scrollBy(18f) } else if (center < list.layoutInfo.viewportStartOffset + 220) scope.launch { list.scrollBy(-18f) }
            return
        }
        val from = local.indexOf(uid); val to = local.indexOf(target.key as String)
        if (from < 0 || to < 0) return
        local = local.toMutableList().apply { add(to, removeAt(from)) }
        dragOffset -= (target.offset - self.offset).toFloat()
    }
    fun endDrag() {
        val uid = dragging ?: return
        val end = local.indexOf(uid)
        if (dragStart >= 0 && end >= 0 && end != dragStart) model.moveQueue(uid, end - dragStart)
        dragging = null; dragOffset = 0f; dragStart = -1
    }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Panel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetGesturesEnabled = dragging == null) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.88f), state = list, contentPadding = PaddingValues(bottom = 28.dp)) {
            item { Column(Modifier.padding(horizontal = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Твоя очередь", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f)); IconButton(onClick = { save = true }, enabled = state.queue.isNotEmpty()) { Icon(Icons.Rounded.PlaylistAdd, "Сохранить очередь") } }
                Text("${upcoming.size} следом · ${state.context}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { QueueMode.entries.forEach { mode -> FilterChip(state.mode == mode, { model.chooseMode(mode, false) }, label = { Text(mode.label, fontSize = 11.sp) }) } }
                if (state.mode == QueueMode.FRESH) Text("Недавние песни дальше, исполнители чередуются, когда это возможно.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = model::reshuffle, enabled = upcoming.size > 1) { Icon(Icons.Rounded.Shuffle, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Перемешать ещё", fontSize = 12.sp) }
                    TextButton(onClick = model::clearQueue, enabled = upcoming.isNotEmpty()) { Text("Очистить", fontSize = 12.sp) }
                }
                Text("СЕЙЧАС", color = Accent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            } }
            state.track?.let { track -> item { QueueRow(track, true, {}, null, null, null) } }
            item { Text("ДАЛЬШЕ", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp)) }
            itemsIndexed(local, key = { _, uid -> uid }) { position, uid ->
                val originalIndex = byUid[uid] ?: return@itemsIndexed
                val track = state.queue.getOrNull(originalIndex) ?: return@itemsIndexed
                val isDragged = dragging == uid
                val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    when (value) {
                        SwipeToDismissBoxValue.EndToStart -> { model.removeQueue(originalIndex); true }
                        SwipeToDismissBoxValue.StartToEnd -> { if (position > 0) model.moveQueue(uid, -position); false }
                        else -> false
                    }
                })
                val rowModifier = if (isDragged) Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset; shadowElevation = 18f; scaleX = 1.02f; scaleY = 1.02f }
                    else Modifier.animateItem()
                SwipeToDismissBox(swipe, modifier = rowModifier, enableDismissFromStartToEnd = position > 0 && dragging == null, enableDismissFromEndToStart = dragging == null,
                    backgroundContent = {
                        val toEnd = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(if (toEnd) Color(0xFF2D6B4F) else Color(0xFF6B2D35)).padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End) {
                            Icon(if (toEnd) Icons.Rounded.PlaylistPlay else Icons.Rounded.Delete, null, tint = SoftWhite)
                            Text(if (toEnd) "  Следующей" else "Убрать  ", color = SoftWhite, fontSize = 13.sp)
                        }
                    }) {
                    QueueRow(track, false, { model.queueIndex(originalIndex) },
                        if (position > 0) ({ model.moveQueue(uid, -1) }) else null, if (position < local.lastIndex) ({ model.moveQueue(uid, 1) }) else null, { model.removeQueue(originalIndex) },
                        next = if (position > 0) ({ model.moveQueue(uid, -position) }) else null,
                        last = if (position < local.lastIndex) ({ model.moveQueue(uid, local.lastIndex - position) }) else null,
                        dragged = isDragged,
                        handle = Modifier.pointerInput(uid) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = uid; dragStart = local.indexOf(uid); dragOffset = 0f },
                                onVerticalDrag = { change, dy -> change.consume(); moveDragged(dy) },
                                onDragEnd = { endDrag() }, onDragCancel = { endDrag() })
                        })
                }
            }
            if (upcoming.isEmpty()) item { Text("Пока больше ничего. Добавь песни через их меню.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp)) }
            item { Column(Modifier.padding(horizontal = 24.dp)) { HorizontalDivider(Modifier.padding(vertical = 12.dp)); AutoplaySetting(settings.autoplay, model::autoplay)
                if (state.radioLoading) Text("Подбираем продолжение…", color = Accent, fontSize = 12.sp)
                if (state.radioFailed) Text("Продолжение пока недоступно для этой песни.", color = Muted, fontSize = 12.sp)
            } }
        }
    }
    if (save) NameDialog("Сохранить очередь", { save = false }) { model.saveQueueAsPlaylist(it); save = false }
}
@Composable private fun QueueRow(track: Track, current: Boolean, play: () -> Unit, up: (() -> Unit)?, down: (() -> Unit)?, remove: (() -> Unit)?,
                                 next: (() -> Unit)? = null, last: (() -> Unit)? = null, dragged: Boolean = false, handle: Modifier? = null) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(if (current) Accent.copy(alpha = .07f) else if (dragged) Elevated else Panel).clickable(onClick = play).padding(start = 8.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.artwork, Modifier.size(46.dp)); Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(track.title, color = if (current) Accent else SoftWhite, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (current) Icon(Icons.Rounded.GraphicEq, "Текущий трек", tint = Accent, modifier = Modifier.padding(end = 16.dp).size(20.dp))
        else Box { IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Изменить очередь: ${track.title}", tint = Muted) }; DropdownMenu(menu, { menu = false }) {
            if (next != null) DropdownMenuItem(text = { Text("Играть следующей") }, onClick = { next(); menu = false })
            if (up != null) DropdownMenuItem(text = { Text("Поднять выше") }, onClick = { up(); menu = false })
            if (down != null) DropdownMenuItem(text = { Text("Опустить ниже") }, onClick = { down(); menu = false })
            if (last != null) DropdownMenuItem(text = { Text("В конец очереди") }, onClick = { last(); menu = false })
            if (remove != null) DropdownMenuItem(text = { Text("Убрать из очереди") }, onClick = { remove(); menu = false })
        } }
        if (!current && handle != null) Box(handle.size(44.dp).semantics { contentDescription = "Перетащить: ${track.title}" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DragHandle, null, tint = if (dragged) Accent else Muted)
        }
    }
}
private fun timerLabel(state: PlaybackState): String = when { state.sleepAfterTrack -> "После трека"; state.sleepUntil > 0 -> "${((state.sleepUntil - state.now).coerceAtLeast(0) / 60000) + 1} мин"; else -> "Таймер" }
@Composable private fun TimerSheet(state: PlaybackState, model: MeloViewModel, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Panel) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Засыпай под музыку", style = MaterialTheme.typography.headlineMedium)
            Text("Плеер остановится, даже если экран выключен.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf(15, 30, 45, 60).forEach { minutes -> FilledTonalButton(onClick = { model.timer(minutes); dismiss() }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("$minutes м") } } }
            TextButton(onClick = { model.timer(afterTrack = true); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("После текущего трека") }
            if (state.sleepAfterTrack || state.sleepUntil > 0) TextButton(onClick = { model.timer(); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Выключить таймер") }
        }
    }
}
@Composable internal fun AutoplaySetting(value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text("Музыка не заканчивается", style = MaterialTheme.typography.titleMedium); Text("Песни знакомых исполнителей после очереди", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp)) }
        Switch(value, change)
    }
}
@Composable internal fun SpeedSetting(value: Float, change: (Float) -> Unit) {
    Text("Скорость · ${value}×", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed -> FilterChip(value == speed, { change(speed) }, label = { Text("${speed}×", fontSize = 11.sp) }) } }
}

/**
 * Shuffle as in Spotify: one button that steps through the states on every tap
 * (off → «Вразброс» → «Свежий микс» → off). Colour and a small dot show that shuffle is on, a spark marks
 * the fresh mix; a long press lists all states.
 */
@Composable internal fun ShuffleModeButton(mode: QueueMode, size: Dp = 48.dp, choose: (QueueMode) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val on = mode != QueueMode.ORDERED
    Box {
        Column(Modifier.size(size).clip(CircleShape)
            .semantics(mergeDescendants = true) { contentDescription = "Перемешивание"; stateDescription = mode.label }
            .combinedClickable(onClickLabel = "Следующий режим", onLongClickLabel = "Выбрать режим", onLongClick = { menu = true }) { choose(mode.nextShuffle()) },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(26.dp)) {
                Icon(Icons.Rounded.Shuffle, null, tint = if (on) Accent else Muted, modifier = Modifier.align(Alignment.Center).size(24.dp))
                if (mode == QueueMode.FRESH) Icon(Icons.Rounded.AutoAwesome, null, tint = Accent, modifier = Modifier.align(Alignment.TopEnd).size(11.dp))
            }
            Box(Modifier.padding(top = 3.dp).size(4.dp).clip(CircleShape).background(if (on) Accent else Color.Transparent))
        }
        DropdownMenu(menu, { menu = false }) {
            QueueMode.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { menu = false; choose(option) },
                    leadingIcon = { Icon(if (option == QueueMode.FRESH) Icons.Rounded.AutoAwesome else if (option == QueueMode.SHUFFLE) Icons.Rounded.Shuffle else Icons.Rounded.Reorder, null, tint = if (option == mode) Accent else Muted) },
                    trailingIcon = { if (option == mode) Icon(Icons.Rounded.Check, null, tint = Accent) })
            }
        }
    }
}
