package app.melo.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.melo.music.MeloViewModel
import app.melo.music.data.Track
import app.melo.music.data.durationLabel

/** Lets the listener replace an automatically found recording (cover, live, remix…) with the one they want. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VersionsSheet(track: Track, model: MeloViewModel, close: () -> Unit) {
    val state by model.versions.collectAsStateWithLifecycle()
    LaunchedEffect(track.id) { model.loadVersions(track) }
    ModalBottomSheet(onDismissRequest = close, containerColor = Panel) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Другая версия записи", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Если играет кавер, live или ремикс, выберите нужную запись. Выбор запомнится.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
            when {
                state.trackId != track.id || state.loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
                state.error != null -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error.orEmpty(), color = Muted, fontSize = 14.sp)
                    TextButton(onClick = { model.loadVersions(track) }) { Text("Повторить") }
                }
                state.options.isEmpty() -> Text("Подходящих записей не нашлось.", color = Muted, modifier = Modifier.padding(vertical = 24.dp))
                else -> LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.options, key = { it.id }) { option ->
                        val selected = option.id == state.currentId
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) Accent.copy(alpha = .10f) else Color.Transparent)
                            .clickable(enabled = !selected, onClickLabel = "Выбрать эту запись") { model.chooseVersion(track, option); close() }
                            .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(option.artwork, Modifier.size(52.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(option.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(listOf(option.artist, option.durationMs.takeIf { it > 0 }?.let(::durationLabel)).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                                    color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                            }
                            if (selected) Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircle, null, tint = Accent, modifier = Modifier.size(18.dp))
                                Text(" Сейчас", color = Accent, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
