package app.melo.music.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.melo.music.data.SpotifyImportResult
import app.melo.music.data.trackCountLabel

@Composable internal fun PartialSpotifyImportDialog(result: SpotifyImportResult, dismiss: () -> Unit, save: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Получена только часть плейлиста") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("${result.summary}\n\nМожно сохранить найденные песни отдельным плейлистом. Текущая коллекция не изменится. Для полного списка повтори импорт позже или выбери CSV.")
        } },
        confirmButton = { TextButton(onClick = save) { Text("Сохранить ${trackCountLabel(result.playlist.tracks.size)}") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Не сохранять") } },
    )
}
