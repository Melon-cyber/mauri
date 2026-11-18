package app.melo.music

import android.app.Application
import app.melo.music.data.LibraryStore
import app.melo.music.data.LyricsRepository
import app.melo.music.data.MusicRepository
import app.melo.music.data.AppSettings
import app.melo.music.data.OfflineStore
import app.melo.music.data.ListeningStats

class MeloApplication : Application() {
    val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val library by lazy { LibraryStore(this) }
    val music by lazy { MusicRepository(filesDir) }
    val lyrics by lazy { LyricsRepository(this, music) }
    val settings by lazy { AppSettings(this) }
    val offline by lazy { OfflineStore(this) }
    val statistics by lazy { ListeningStats(this) }
    override fun onCreate() {
        super.onCreate()
        app.melo.music.data.CrashLog.install(this)
    }
}
