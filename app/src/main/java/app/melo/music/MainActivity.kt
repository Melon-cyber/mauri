package app.melo.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import app.melo.music.ui.MeloApp
import app.melo.music.ui.MeloTheme

class MainActivity : ComponentActivity() {
    private val model: MeloViewModel by viewModels { ViewModelProvider.AndroidViewModelFactory(application) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent { MeloTheme { MeloApp(model) } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT)?.let { model.receiveShare(it) }
        // "Play … in Mauri" from the voice assistant: search and start the best result.
        if (intent.action == android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) model.playFromSearch(intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty())
    }
}
