package app.melo.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.melo.music.MeloViewModel
import app.melo.music.data.*
import java.time.format.TextStyle
import java.util.Locale

private fun mixColors(kind: MixKind?): List<Color> = when (kind) {
    MixKind.DAILY -> listOf(Color(0xFFF04444), Color(0xFF6B1F3A))
    MixKind.FREQUENT -> listOf(Color(0xFF8E5CF0), Color(0xFF2B1B55))
    MixKind.FORGOTTEN -> listOf(Color(0xFF2FA88B), Color(0xFF123A35))
    MixKind.NEW -> listOf(Color(0xFFF0A23C), Color(0xFF4A2A10))
    null -> listOf(Color(0xFFE0457B), Color(0xFF2B2140))
}
private fun mixIcon(kind: MixKind?): ImageVector = when (kind) {
    MixKind.DAILY -> Icons.Rounded.WbSunny
    MixKind.FREQUENT -> Icons.Rounded.Favorite
    MixKind.FORGOTTEN -> Icons.Rounded.History
    MixKind.NEW -> Icons.Rounded.NewReleases
    null -> Icons.Rounded.AutoAwesome
}

/** "Для тебя": playlists Mauri updates by itself, plus the year in music. */
@Composable internal fun ForYouRow(model: MeloViewModel) {
    val mixes by model.smartMixes.collectAsStateWithLifecycle()
    Column {
        Text("Для тебя", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(mixes, key = { it.kind.name }) { mix ->
                MixCard(mix.title, "${trackCountLabel(mix.tracks.size)} · ${mix.subtitle}", mix.kind, mix.tracks.take(3).map { it.artwork }) { model.playMix(mix) }
            }
            item(key = "recap") { MixCard("Итоги года", "Твой ${java.time.LocalDate.now().year} в музыке", null, emptyList()) { model.loadRecap() } }
        }
    }
}

@Composable private fun MixCard(title: String, subtitle: String, kind: MixKind?, covers: List<String>, open: () -> Unit) {
    Column(Modifier.width(156.dp).clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = title, onClick = open)) {
        Box(Modifier.size(156.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(mixColors(kind)))) {
            covers.filter { it.isNotBlank() }.take(3).forEachIndexed { i, url ->
                Artwork(url, Modifier.align(Alignment.BottomEnd).padding(end = (12 + i * 22).dp, bottom = (12 + i * 10).dp).size(64.dp).clip(RoundedCornerShape(12.dp)))
            }
            Icon(mixIcon(kind), null, tint = Color.White, modifier = Modifier.padding(14.dp).size(26.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, lineHeight = 20.sp, modifier = Modifier.align(Alignment.BottomStart).padding(14.dp).fillMaxWidth(.62f))
        }
        Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
    }
}

/** Full-screen story of the year in music: swipe through a few bright cards. */
@Composable internal fun YearRecapDialog(recap: YearRecap, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val months = recap.months
        val bestMonth = months.maxByOrNull { it.value }
        val slides = buildList<@Composable () -> Unit> {
            add { RecapSlide(listOf(Color(0xFFF04444), Color(0xFF3A0F1C)), "Твой ${recap.year}", listeningLabel(recap.milliseconds), "музыки за год · ${recap.plays} прослушиваний · ${recap.activeDays} дней с музыкой") }
            recap.artists.firstOrNull()?.let { top -> add { RecapSlide(listOf(Color(0xFF8E5CF0), Color(0xFF1C1238)), "Исполнитель года", top.artist, "${listeningLabel(top.milliseconds)} вместе", recap.artists.drop(1).mapIndexed { i, a -> "${i + 2}. ${a.artist}" }) } }
            recap.tracks.firstOrNull()?.let { top -> add { RecapSlide(listOf(Color(0xFF2FA88B), Color(0xFF0E2B27)), "Песня года", top.track.title, "${top.track.artist} · ${top.plays} раз", recap.tracks.drop(1).mapIndexed { i, t -> "${i + 2}. ${t.track.title} — ${t.track.artist}" }, top.track.artwork) } }
            bestMonth?.let { m -> add { RecapSlide(listOf(Color(0xFFF0A23C), Color(0xFF3A210B)), "Самый музыкальный месяц", java.time.Month.of(m.key).getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru")).replaceFirstChar { it.uppercase() }, listeningLabel(m.value) + (recap.bestDay?.let { " · рекорд дня: ${it.day.dayOfMonth}.${"%02d".format(it.day.monthValue)}, ${listeningLabel(it.milliseconds)}" } ?: "")) } }
            add { RecapSlide(listOf(Color(0xFFE0457B), Color(0xFF2B2140)), "Итог", "${recap.uniqueTracks} песен", "от ${recap.uniqueArtists} исполнителей. Спасибо, что слушаешь с Mauri.") }
        }
        val pager = rememberPagerState { slides.size }
        Box(Modifier.fillMaxSize().background(Ink)) {
            if (recap.milliseconds == 0L) RecapSlide(listOf(Color(0xFF2B2140), Ink), "Итоги ${recap.year}", "Пока тихо", "Послушай музыку — здесь появится твой год в цифрах.")
            else HorizontalPager(pager, Modifier.fillMaxSize()) { page -> slides[page]() }
            Row(Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (recap.milliseconds > 0) repeat(slides.size) { i -> Box(Modifier.width(28.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == pager.currentPage) .95f else .3f))) }
            }
            IconButton(onClick = close, modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(6.dp)) { Icon(Icons.Rounded.Close, "Закрыть итоги", tint = Color.White) }
        }
    }
}

@Composable private fun RecapSlide(colors: List<Color>, label: String, big: String, caption: String, list: List<String> = emptyList(), artwork: String = "") {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(colors)).systemBarsPadding().padding(horizontal = 28.dp, vertical = 64.dp), verticalArrangement = Arrangement.Center) {
        if (artwork.isNotBlank()) Artwork(artwork, Modifier.size(150.dp).clip(RoundedCornerShape(20.dp)).padding(bottom = 4.dp))
        Text(label.uppercase(), color = Color.White.copy(alpha = .8f), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(top = 18.dp))
        Text(big, color = Color.White, fontSize = 46.sp, lineHeight = 50.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(caption, color = Color.White.copy(alpha = .85f), fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 12.dp))
        if (list.isNotEmpty()) Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { Text(it, color = Color.White.copy(alpha = .9f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
