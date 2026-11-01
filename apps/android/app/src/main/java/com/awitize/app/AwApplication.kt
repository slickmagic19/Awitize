package com.awitize.app

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.awitize.app.data.Downloads
import com.awitize.app.data.InnerTube
import com.awitize.app.data.LibraryStore
import com.awitize.app.data.LyricsClient
import com.awitize.app.data.Net
import com.awitize.app.data.StreamResolver
import com.awitize.app.data.SyncClient
import com.awitize.app.ui.AwThemePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class AwApplication : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }
}

// Single composition root — no DI framework, everything constructed once and
// shared. Lives for the whole process; nothing here holds an Activity.
class Container(context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    val net = Net(context)
    val store = LibraryStore(File(context.filesDir, "library.json"), appScope).also { it.load() }
    val innerTube = InnerTube(http)
    val resolver = StreamResolver(http).also { StreamResolver.init(http) }
    val lyrics = LyricsClient(http)
    val downloads = Downloads(context, resolver, http) { net.online.value }
    val syncClient = SyncClient(http)

    // Appearance: theme preset survives restarts via prefs; the Compose state
    // lets any screen read/switch the look live.
    private val uiPrefs = context.getSharedPreferences("awitize_ui", Context.MODE_PRIVATE)
    val themePreset: MutableState<AwThemePreset> =
        mutableStateOf(AwThemePreset.fromId(uiPrefs.getString("ui_theme", null)))

    fun setThemePreset(preset: AwThemePreset) {
        uiPrefs.edit().putString("ui_theme", preset.id).apply()
        themePreset.value = preset
    }

    val deviceName: String =
        Build.MODEL?.trim()?.takeIf { it.isNotEmpty() && it != Build.UNKNOWN } ?: "Android phone"
}
