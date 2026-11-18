package app.melo.music.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.melo.music.*
import app.melo.music.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import androidx.compose.material.icons.automirrored.rounded.Undo

internal fun activeLyricIndex(lines: List<LyricLine>, position: Long, offset: Long): Int = activeLyricAt(lines, position + offset)

/** Index of the last line that has started by lyric time [at], or -1. */
internal fun activeLyricAt(lines: List<LyricLine>, at: Long): Int {
    var low=0; var high=lines.lastIndex; var answer=-1
    while(low<=high) { val mid=(low+high).ushr(1); if(lines[mid].timeMs<=at) { answer=mid;low=mid+1 } else high=mid-1 }
    return answer
}

/** Lines light up a moment before they are sung, as in Spotify and Apple Music; the eye needs time to move. */
private const val LYRIC_LEAD_MS = 180L

@Composable internal fun LyricsStage(playback: PlaybackState, lyrics: LyricsState, library: Library, settings: ListeningSettings, model: MeloViewModel, palette: CoverPalette, close: ()->Unit) {
    val track = playback.track ?: return
    var adjust by remember { mutableStateOf(false) }
    var aligning by remember(track.id) { mutableStateOf(false) }
    val canAlign = lyrics.lyrics.lines.isNotEmpty() || lyrics.lyrics.alignable.isNotEmpty()
    // Only the text is on screen. One tap on an empty spot shows a few controls for a moment.
    var chrome by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(view, settings.lyricKeepAwake) {
        val before=view.keepScreenOn
        if(settings.lyricKeepAwake) view.keepScreenOn=true
        onDispose { if(settings.lyricKeepAwake) view.keepScreenOn=before }
    }
    // True full screen: status and navigation bars are hidden while the text is open (a swipe from the edge shows them).
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(chrome, adjust) { if(chrome && !adjust && isRealDevice) { delay(3_500); chrome=false } }
    val motion = motionAllowed(settings.lyricMotion)
    val translationState by model.translation.collectAsState()
    LaunchedEffect(settings.lyricTranslate, lyrics.trackId, lyrics.lyrics) { if (settings.lyricTranslate) model.loadTranslation(lyrics) }
    LaunchedEffect(translationState.message) { translationState.message?.let { model.message(it) } }
    val position = rememberSmoothPosition(playback.position, playback.playing, settings.speed, track.id)
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { chrome = !chrome }) }) {
        when(settings.lyricBackdrop) {
            LyricBackdrop.COVER -> CoverBackdrop(track, palette, motion)
            LyricBackdrop.DARK -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Panel, Ink))))
            LyricBackdrop.RED -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF7A2A36), Color(0xFF3A1219), Ink))))
        }
        Column(Modifier.fillMaxSize().displayCutoutPadding()) {
            // Small header: song icon, title and, a little lower and smaller, the artist. Everything below is the text.
            Row(Modifier.fillMaxWidth().padding(start=28.dp,end=12.dp,top=22.dp,bottom=10.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                Artwork(track.artwork,Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f).padding(start=14.dp,end=8.dp)) {
                    Text(track.title,color=SoftWhite,fontSize=18.sp,lineHeight=22.sp,fontWeight=FontWeight.ExtraBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(track.artist,color=SoftWhite.copy(alpha=.68f),fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=2.dp))
                }
                AnimatedVisibility(chrome, enter=fadeIn(tween(200)), exit=fadeOut(tween(300))) {
                    Row {
                        IconButton(onClick={model.lyricTranslate(!settings.lyricTranslate)}){Icon(Icons.Rounded.Translate,if(settings.lyricTranslate) "Скрыть перевод" else "Перевод на русский",tint=if(settings.lyricTranslate) palette.accent else SoftWhite)}
                        if(canAlign) IconButton(onClick={aligning=true;chrome=false}){Icon(Icons.Rounded.Sync,"Подогнать текст",tint=if(aligning) palette.accent else SoftWhite)}
                        IconButton(onClick={adjust=true}){Icon(Icons.Rounded.Tune,"Настройки текста")}
                        IconButton(onClick=close){ Icon(Icons.Rounded.KeyboardArrowDown,"Вернуться к обложке",Modifier.size(30.dp)) }
                    }
                }
            }
            val context=androidx.compose.ui.platform.LocalContext.current
            val shareScope=rememberCoroutineScope()
            LyricsPane(lyrics,position,settings,model,motion,Modifier.weight(1f).fillMaxWidth().padding(horizontal=28.dp),aligning,{aligning=it}) { text ->
                shareScope.launch { runCatching { shareLyricCard(context,track,text,palette) }.onFailure { model.message("Не удалось подготовить картинку") } }
            }
        }
        AnimatedVisibility(chrome || playback.error!=null, Modifier.align(Alignment.BottomCenter), enter=fadeIn(tween(200)), exit=fadeOut(tween(300))) {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha=.5f)))).systemBarsPadding().padding(horizontal=20.dp).padding(top=24.dp, bottom=12.dp),
                horizontalAlignment=Alignment.CenterHorizontally) {
                playback.error?.let { error -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(error,color=Color(0xFFFFDAD5),style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
                    TextButton(onClick=model::retry){Text("Ещё раз",color=SoftWhite)}
                } }
                if(chrome) Row(horizontalArrangement=Arrangement.spacedBy(28.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=model::previous,modifier=Modifier.size(52.dp)){Icon(Icons.Rounded.SkipPrevious,"Предыдущий трек",Modifier.size(32.dp))}
                    FilledIconButton(onClick=model::togglePlay,modifier=Modifier.size(60.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=SoftWhite,contentColor=Ink)) {
                        if(playback.buffering) CircularProgressIndicator(Modifier.size(24.dp),color=Ink,strokeWidth=2.5.dp)
                        else Icon(if(playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,if(playback.playing) "Пауза" else "Воспроизвести",Modifier.size(34.dp))
                    }
                    IconButton(onClick=model::next,modifier=Modifier.size(52.dp)){Icon(Icons.Rounded.SkipNext,"Следующий трек",Modifier.size(32.dp))}
                }
            }
        }
    }
    if(adjust) LyricSettingsSheet(model,lyrics.trackId.takeIf{it.isNotBlank()}) {adjust=false}
}

@Composable private fun LyricText(text: String, color: Color, settings: ListeningSettings, centered: Boolean, modifier: Modifier = Modifier, onLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {}) {
    Text(text,color=color,fontSize=settings.lyricSize.sp,lineHeight=(settings.lyricSize*1.2f).sp,fontWeight=FontWeight.ExtraBold,
        textAlign=if(centered) TextAlign.Center else TextAlign.Start,modifier=modifier.fillMaxWidth(),onTextLayout=onLayout)
}

@Composable internal fun LyricsPane(state: LyricsState, position: State<Long>, settings: ListeningSettings, model: MeloViewModel, motion: Boolean, modifier: Modifier,
                                    aligning: Boolean = false, setAligning: (Boolean) -> Unit = {}, onShare: (String) -> Unit = {}) {
    val translation by model.translation.collectAsState()
    val translations = if (settings.lyricTranslate && translation.trackId == state.trackId) translation.lines else emptyMap()
    // Lines aligned by hand (for a recording of another length) behave like normal timed lyrics.
    val anchors=settings.lyricAnchors[state.trackId].orEmpty()
    val aligned=state.lyrics.lines.isEmpty() && state.lyrics.alignable.isNotEmpty() && (settings.lyricOffsets.containsKey(state.trackId) || anchors.isNotEmpty())
    val lines=if(aligned) state.lyrics.alignable else state.lyrics.lines
    val offset=settings.lyricOffsets[state.trackId] ?: 0L
    val timing=remember(offset,anchors){LyricTiming(offset,anchors)}
    // Recompose only when the current line changes, not on every frame of the running clock.
    val index by remember(lines,timing) { derivedStateOf { activeLyricAt(lines,timing.toLyric(position.value+LYRIC_LEAD_MS)) } }
    val activeTime=lines.getOrNull(index)?.timeMs
    val anchor=if(activeTime==null) -1 else lines.indexOfFirst{it.timeMs==activeTime}
    val scroll=key(state.trackId){rememberLazyListState()}
    var follow by remember(state.trackId,settings.lyricFollow){mutableStateOf(settings.lyricFollow)}
    LaunchedEffect(scroll,state.trackId){scroll.interactionSource.interactions.collectLatest{if(it is DragInteraction.Start) follow=false}}
    // After reading ahead the text returns to the current line by itself, like the big players do.
    if(settings.lyricFollow && isRealDevice) LaunchedEffect(follow,scroll.isScrollInProgress) {
        if(!follow && !scroll.isScrollInProgress) { delay(4_500); follow=true }
    }
    BoxWithConstraints(modifier) {
        val viewport=maxHeight
        val density=LocalDensity.current
        val focusLine=with(density){(viewport*.24f).toPx()}
        LaunchedEffect(anchor,follow,state.trackId,settings.lyricSize,settings.lyricGap,viewport) {
            if(!follow || anchor<0) return@LaunchedEffect
            fun distance(): Float? = scroll.layoutInfo.visibleItemsInfo.firstOrNull{it.index==anchor}?.let { (it.offset-scroll.layoutInfo.viewportStartOffset)-focusLine }
            val visible=distance()
            if(visible==null) { scroll.scrollToItem(anchor); distance()?.let{scroll.scrollBy(it)} }
            else if(motion) scroll.animateScrollBy(visible,tween(620,easing=FastOutSlowInEasing))
            else scroll.scrollBy(visible)
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator(color=SoftWhite)}
            aligning && state.error==null && (state.lyrics.lines.isNotEmpty() || state.lyrics.alignable.isNotEmpty()) ->
                LyricAligner(state,state.lyrics.lines.ifEmpty{state.lyrics.alignable},position,settings,model) { setAligning(false); follow=true }
            state.error!=null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.Center){
                EmptyState(Icons.Rounded.Lyrics,"Текст пока недоступен",state.error,"Повторить"){model.playback.value.track?.let(model::loadLyrics)}
            }
            lines.isNotEmpty() -> {
                val blurLines=motion && follow && Build.VERSION.SDK_INT>=Build.VERSION_CODES.S
                LazyColumn(Modifier.fillMaxSize().graphicsLayer{compositingStrategy=CompositingStrategy.Offscreen}.drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent,.035f to Color.Black,.88f to Color.Black,1f to Color.Transparent),blendMode=BlendMode.DstIn)
                },state=scroll,contentPadding=PaddingValues(top=12.dp,bottom=viewport*.76f),verticalArrangement=Arrangement.spacedBy(settings.lyricGap.dp*.55f)) {
                    itemsIndexed(lines,key={i,_->"${state.trackId}-$i"}){i,line->
                        val active=activeTime!=null && line.timeMs==activeTime
                        val distance=if(anchor<0) 3 else abs(i-anchor)
                        val past=anchor>=0 && i<anchor
                        val dim=settings.lyricDim
                        val target=when { active -> SoftWhite; past -> SoftWhite.copy(alpha=dim*.85f); else -> SoftWhite.copy(alpha=dim) }
                        val duration=if(motion) 420 else 0
                        val color by animateColorAsState(target,tween(duration),label="lyricColor")
                        val scale by animateFloatAsState(if(active) 1f else .94f,tween(duration,easing=FastOutSlowInEasing),label="lyricFocus")
                        val blur by animateDpAsState(if(blurLines && !active) (distance.coerceAtMost(4)*.9f).dp else 0.dp,tween(duration),label="lyricBlur")
                        val centered=settings.lyricCentered
                        // The sung part of the current line fills with light, like in Apple Music.
                        val ends=lines.drop(i+1).firstOrNull{it.timeMs>line.timeMs}?.timeMs ?: (line.timeMs+5_000)
                        val span=(ends-line.timeMs).coerceIn(600L,10_000L)
                        val filling=active && motion && line.text.isNotBlank()
                        Box(Modifier.fillMaxWidth().heightIn(min=48.dp)
                                .graphicsLayer{scaleX=scale;scaleY=scale;transformOrigin=TransformOrigin(if(centered) .5f else 0f,.5f)}
                                .then(if(blur>0.dp) Modifier.blur(blur) else Modifier)
                                .semantics(mergeDescendants=true){selected=active}
                                .clip(RoundedCornerShape(14.dp))
                                .combinedClickable(onLongClickLabel="Поделиться строкой",onLongClick={ if(line.text.isNotBlank()) onShare(line.text) }) { model.seek(timing.toPlay(line.timeMs));follow=true }
                                .padding(vertical=6.dp,horizontal=4.dp)) {
                            Column {
                            Box {
                            LyricText(line.text.ifBlank{"• • •"},if(filling) SoftWhite.copy(alpha=maxOf(dim,.38f)) else color,settings,centered)
                            if(filling) {
                                var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                                LyricText(line.text,SoftWhite,settings,centered,Modifier.clearAndSetSemantics{}.drawWithContent {
                                    val text=layout ?: return@drawWithContent
                                    val progress=((timing.toLyric(position.value+LYRIC_LEAD_MS)-line.timeMs).toFloat()/span).coerceIn(0f,1f)
                                    val total=text.layoutInput.text.length.coerceAtLeast(1)
                                    val lit=progress*total
                                    for(row in 0 until text.lineCount) {
                                        val first=text.getLineStart(row); val last=text.getLineEnd(row,visibleEnd=true)
                                        val share=((lit-first)/(last-first).coerceAtLeast(1)).coerceIn(0f,1f)
                                        if(share<=0f) break
                                        val left=text.getLineLeft(row); val right=text.getLineRight(row)
                                        clipRect(left,text.getLineTop(row),left+(right-left)*share,text.getLineBottom(row)) { this@drawWithContent.drawContent() }
                                    }
                                }) { layout=it }
                            }
                            }
                            translations[line.text.trim()]?.takeIf { it.isNotBlank() && !it.equals(line.text.trim(), true) }?.let { tr ->
                                Text(tr,color=SoftWhite.copy(alpha=if(active) .82f else dim*.9f),fontSize=(settings.lyricSize*.52f).sp,lineHeight=(settings.lyricSize*.66f).sp,fontWeight=FontWeight.SemiBold,
                                    textAlign=if(centered) TextAlign.Center else TextAlign.Start,modifier=Modifier.fillMaxWidth().padding(top=4.dp))
                            }
                            }
                        }
                    }
                    item{Text("Текст · ${state.lyrics.source}",color=SoftWhite.copy(alpha=.6f),style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=24.dp,start=4.dp))}
                }
                if(!follow) FilledTonalButton(onClick={follow=true},modifier=Modifier.align(Alignment.BottomCenter).padding(bottom=8.dp),
                    colors=ButtonDefaults.filledTonalButtonColors(containerColor=Color.Black.copy(alpha=.45f),contentColor=SoftWhite)){
                    Icon(Icons.Rounded.MyLocation,null,Modifier.size(16.dp));Spacer(Modifier.width(8.dp));Text("К текущей строке")
                }
            }
            state.lyrics.plain.isNotBlank() -> Column(Modifier.fillMaxSize().graphicsLayer{compositingStrategy=CompositingStrategy.Offscreen}.drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent,.05f to Color.Black,.92f to Color.Black,1f to Color.Transparent),blendMode=BlendMode.DstIn)
                }.verticalScroll(rememberScrollState()).padding(top=12.dp,bottom=48.dp)) {
                Text("Без синхронизации · ${state.lyrics.source}",color=SoftWhite.copy(alpha=.7f),style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(bottom=if(state.lyrics.alignable.isNotEmpty()) 10.dp else 20.dp))
                if(state.lyrics.alignable.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(bottom=22.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha=.28f)).padding(16.dp)) {
                    val expected=state.lyrics.expectedDurationMs; val played=state.lyrics.playedDurationMs
                    Text("Текст с синхронизацией есть, но он для записи длиной ${durationLabel(expected)}, а играет ${durationLabel(played)}. Обычно это клип или другая версия.",
                        color=SoftWhite,fontSize=14.sp,lineHeight=19.sp)
                    Row(Modifier.padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick={ model.playback.value.track?.let { model.useMatchingVersion(it,expected) } },colors=ButtonDefaults.filledTonalButtonColors(containerColor=SoftWhite,contentColor=Ink)){Text("Включить версию ${durationLabel(expected)}",fontSize=13.sp)}
                        OutlinedButton(onClick={setAligning(true)}){Text("Подогнать",color=SoftWhite,fontSize=13.sp)}
                    }
                }
                if(translations.isEmpty()) Text(state.lyrics.plain,color=SoftWhite,fontSize=settings.lyricSize.sp,lineHeight=(settings.lyricSize*1.4f).sp,fontWeight=FontWeight.Bold,textAlign=if(settings.lyricCentered) TextAlign.Center else TextAlign.Start,modifier=Modifier.fillMaxWidth())
                else state.lyrics.plain.lines().forEach { row ->
                    val align=if(settings.lyricCentered) TextAlign.Center else TextAlign.Start
                    Text(row,color=SoftWhite,fontSize=settings.lyricSize.sp,lineHeight=(settings.lyricSize*1.25f).sp,fontWeight=FontWeight.Bold,textAlign=align,modifier=Modifier.fillMaxWidth().padding(top=if(row.isBlank()) 8.dp else 10.dp))
                    translations[row.trim()]?.takeIf { it.isNotBlank() && !it.equals(row.trim(), true) }?.let { Text(it,color=SoftWhite.copy(alpha=.72f),fontSize=(settings.lyricSize*.52f).sp,lineHeight=(settings.lyricSize*.66f).sp,fontWeight=FontWeight.SemiBold,textAlign=align,modifier=Modifier.fillMaxWidth()) }
                }
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.Center) {EmptyState(Icons.Rounded.Lyrics,if(state.lyrics.instrumental) "Без слов" else "Текст ещё не найден","Искали в LRCLIB и YouTube Music.")}
        }
    }
}

/**
 * Hand alignment. While the song plays the listener taps the line that is being sung; every tap becomes a sync
 * point, and the highlight immediately shows the result. One tap fixes a late start; a few more (after a long
 * instrumental, a skit in a music video, a cut verse) keep the whole song in time.
 */
@Composable private fun LyricAligner(state: LyricsState, source: List<LyricLine>, position: State<Long>, settings: ListeningSettings, model: MeloViewModel, close: () -> Unit) {
    val lines = remember(source) { source.filter { it.text.isNotBlank() } }
    val startOffset = settings.lyricOffsets[state.trackId] ?: 0L
    var anchors by remember(state.trackId) { mutableStateOf(settings.lyricAnchors[state.trackId].orEmpty()) }
    var history by remember(state.trackId) { mutableStateOf(listOf<List<LyricAnchor>>()) }
    val timing = remember(anchors, startOffset) { LyricTiming(if (anchors.isEmpty()) startOffset else 0L, anchors) }
    val marked = remember(anchors) { anchors.map { it.lyricMs }.toSet() }
    // Before the first tap a text for another recording has no reliable time here, so nothing is highlighted yet.
    val estimate = anchors.isNotEmpty() || state.lyrics.lines.isNotEmpty() || settings.lyricOffsets.containsKey(state.trackId)
    val current by remember(lines, timing, estimate) { derivedStateOf { if (estimate) activeLyricAt(lines, timing.toLyric(position.value)) else -1 } }
    val playback by model.playback.collectAsState()
    val list = rememberLazyListState()
    // The list keeps the line being sung near the top, so the next lines are always in reach.
    LaunchedEffect(current) { if (current >= 0 && !list.isScrollInProgress) list.animateScrollToItem((current - 1).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = .32f)).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text("Подгонка текста", color = SoftWhite, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Text(if (anchors.isEmpty()) "Нажми на строку в тот момент, когда её начинают петь. Начни с первой."
                else "Отмечено строк: ${anchors.size}. Если текст снова разошёлся (после проигрыша или вставки в клипе), нажми на строку, которая звучит сейчас.",
                color = SoftWhite.copy(alpha = .8f), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.seekBy(-5_000) }) { Icon(Icons.Rounded.Replay5, "Назад на 5 секунд", tint = SoftWhite) }
                IconButton(onClick = model::togglePlay) { Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "Пауза" else "Воспроизвести", tint = SoftWhite) }
                IconButton(onClick = { anchors = history.last(); history = history.dropLast(1) }, enabled = history.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, "Отменить последнюю отметку", tint = if (history.isNotEmpty()) SoftWhite else SoftWhite.copy(alpha = .35f))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = close) { Text("Отмена", color = SoftWhite.copy(alpha = .75f)) }
                FilledTonalButton(onClick = {
                    if (anchors.isEmpty()) { if (settings.lyricAnchors.containsKey(state.trackId)) model.clearLyricSync(state.trackId) } else model.saveLyricSync(state.trackId, anchors)
                    close()
                }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = SoftWhite, contentColor = Ink)) { Text("Готово") }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 10.dp, bottom = 240.dp)) {
            itemsIndexed(lines, key = { i, _ -> "align-$i" }) { i, line ->
                val now = i == current
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (now) Color.White.copy(alpha = .14f) else Color.Transparent)
                    .clickable(onClickLabel = "Сейчас поётся эта строка") {
                        history = (history + listOf(anchors)).takeLast(30)
                        anchors = LyricTiming.add(anchors, LyricAnchor(line.timeMs, (position.value - LyricTiming.TAP_REACTION_MS).coerceAtLeast(0)))
                    }.padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(line.text, color = if (now) SoftWhite else SoftWhite.copy(alpha = .6f), fontSize = (settings.lyricSize * .74f).sp, lineHeight = (settings.lyricSize * .94f).sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (line.timeMs in marked) Icon(Icons.Rounded.CheckCircle, "Отмечена", tint = SoftWhite, modifier = Modifier.padding(start = 8.dp).size(20.dp))
                }
            }
        }
    }
}
