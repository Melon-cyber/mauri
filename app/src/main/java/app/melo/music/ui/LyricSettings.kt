@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.melo.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.melo.music.MeloViewModel
import app.melo.music.data.*

@Composable internal fun LyricSettingsSheet(model: MeloViewModel, trackId: String? = null, dismiss: () -> Unit) {
    val settings by model.settings.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Panel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text("Настройки текста", style = MaterialTheme.typography.headlineMedium)
            Text("Так, как удобно тебе", color = Muted, modifier = Modifier.padding(top=6.dp,bottom=18.dp))
            Text("Размер букв · ${settings.lyricSize.toInt()}", style = MaterialTheme.typography.titleMedium)
            Slider(settings.lyricSize, model::lyricSize, valueRange=22f..42f, steps=9)
            Text("Расстояние между строками", style = MaterialTheme.typography.titleMedium)
            Slider(settings.lyricGap, model::lyricGap, valueRange=12f..36f, steps=5)
            Text("Яркость остальных строк", style = MaterialTheme.typography.titleMedium)
            Slider(settings.lyricDim, model::lyricDim, valueRange=.25f.. .8f)
            Text("Фон", style = MaterialTheme.typography.titleMedium, modifier=Modifier.padding(top=12.dp))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { LyricBackdrop.entries.forEach { value ->
                FilterChip(settings.lyricBackdrop == value, { model.lyricBackdrop(value) }, label={ Text(value.label) })
            } }
            PreferenceSwitch("По центру", "Выравнивание текста", settings.lyricCentered, model::lyricCentered)
            PreferenceSwitch("Автопрокрутка", "Следовать за текущей строкой", settings.lyricFollow, model::lyricFollow)
            PreferenceSwitch("Плавная анимация", "Подсветка и движение строк", settings.lyricMotion, model::lyricMotion)
            PreferenceSwitch("Не гасить экран", "Только пока открыт текст песни", settings.lyricKeepAwake, model::lyricKeepAwake)
            if (trackId != null) {
                val offset = settings.lyricOffsets[trackId] ?: 0L
                HorizontalDivider(Modifier.padding(vertical=14.dp))
                Text("Сдвиг: ${"%+.1f".format(offset / 1000f)} с", style=MaterialTheme.typography.titleMedium)
                Text("Если слова отстают, увеличь значение. Только для этой песни.", color=Muted, style=MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={model.lyricOffset(trackId,offset-500)}){Text("−0,5 с")}
                    TextButton(onClick={model.lyricOffset(trackId,0)}){Text("Сбросить")}
                    TextButton(onClick={model.lyricOffset(trackId,offset+500)}){Text("+0,5 с")}
                }
                val points = settings.lyricAnchors[trackId].orEmpty()
                if (points.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Подогнано по строкам: ${points.size}", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { model.clearLyricSync(trackId) }) { Text("Сбросить подгонку") }
                }
            }
            TextButton(onClick=model::resetLyrics,modifier=Modifier.fillMaxWidth()){Text("Сбросить оформление")}
        }
    }
}

@Composable internal fun PreferenceSwitch(title: String, description: String, checked: Boolean, change: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end=12.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(description,color=Muted,style=MaterialTheme.typography.bodySmall)
        }
        Switch(checked,change,modifier=Modifier.semantics{contentDescription=title})
    }
}
