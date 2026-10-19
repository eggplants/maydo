package io.github.eggplants.maydo.core

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.eggplants.maydo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The highest resolution yt-dlp picks, as mpv's `ytdl-format`. */
enum class VideoQuality(@StringRes val label: Int, val height: Int?) {
    BEST(R.string.quality_best, null),
    P2160(R.string.quality_2160, 2160),
    P1440(R.string.quality_1440, 1440),
    P1080(R.string.quality_1080, 1080),
    P720(R.string.quality_720, 720),
    P480(R.string.quality_480, 480),
    P360(R.string.quality_360, 360),
    AUDIO(R.string.quality_audio, null);

    /** yt-dlp's `--format`: separate video and audio, which mpv plays together, else one file. */
    val ytdlFormat: String
        get() =
            when {
                this == AUDIO -> "bestaudio/best"
                height == null -> "bestvideo*+bestaudio/best"
                else -> "bestvideo*[height<=?$height]+bestaudio/best[height<=?$height]/best"
            }
}

/** Something played, newest first on the home screen. */
@Serializable data class HistoryEntry(val uri: String, val title: String?, val time: Long)

data class AppSettings(
    val quality: VideoQuality = VideoQuality.P1080,
    /** mediacodec, falling back to software decoding. */
    val hardwareDecoding: Boolean = true,
    /**
     * Web pages go to yt-dlp before mpv tries them; otherwise only what mpv cannot open itself does
     * (and YouTube and Twitch, which ytdl_hook always sends first).
     */
    val ytdlFirst: Boolean = true,
    /**
     * A link with a playlist in it (`watch?v=...&list=...`) plays the whole playlist from that
     * video, as yt-dlp's `--yes-playlist`; otherwise only the video.
     */
    val wholePlaylists: Boolean = true,
    /** Whether to look for new versions of the app and yt-dlp on start. */
    val checkUpdates: Boolean = true,
    val keepHistory: Boolean = true,
    val history: List<HistoryEntry> = emptyList(),
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val state: StateFlow<AppSettings> =
        context.dataStore.data.map(::read).stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** The settings as stored, without waiting for [state] to have read them. */
    suspend fun current(): AppSettings = read(context.dataStore.data.first())

    suspend fun setQuality(value: VideoQuality) = set(QUALITY, value.name)

    suspend fun setHardwareDecoding(value: Boolean) = set(HWDEC, value)

    suspend fun setYtdlFirst(value: Boolean) = set(YTDL_FIRST, value)

    suspend fun setWholePlaylists(value: Boolean) = set(WHOLE_PLAYLISTS, value)

    suspend fun setCheckUpdates(value: Boolean) = set(CHECK_UPDATES, value)

    suspend fun setKeepHistory(value: Boolean) {
        context.dataStore.edit {
            it[KEEP_HISTORY] = value
            if (!value) it.remove(HISTORY)
        }
    }

    /** Puts [uri] first in the history, or moves it there with [title] when it has none yet. */
    suspend fun addHistory(uri: String, title: String?) {
        context.dataStore.edit { prefs ->
            if (prefs[KEEP_HISTORY] == false) return@edit
            val old = decodeHistory(prefs[HISTORY])
            val keptTitle = title ?: old.firstOrNull { it.uri == uri }?.title
            val entry = HistoryEntry(uri, keptTitle, System.currentTimeMillis())
            val history = (listOf(entry) + old.filter { it.uri != uri }).take(HISTORY_SIZE)
            prefs[HISTORY] = json.encodeToString(HISTORY_SERIALIZER, history)
        }
    }

    suspend fun removeHistory(uri: String) {
        context.dataStore.edit { prefs ->
            val history = decodeHistory(prefs[HISTORY]).filter { it.uri != uri }
            prefs[HISTORY] = json.encodeToString(HISTORY_SERIALIZER, history)
        }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { it.remove(HISTORY) }
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    private fun read(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            quality =
                prefs[QUALITY]?.let { name -> VideoQuality.entries.firstOrNull { it.name == name } }
                    ?: defaults.quality,
            hardwareDecoding = prefs[HWDEC] ?: defaults.hardwareDecoding,
            ytdlFirst = prefs[YTDL_FIRST] ?: defaults.ytdlFirst,
            wholePlaylists = prefs[WHOLE_PLAYLISTS] ?: defaults.wholePlaylists,
            checkUpdates = prefs[CHECK_UPDATES] ?: defaults.checkUpdates,
            keepHistory = prefs[KEEP_HISTORY] ?: defaults.keepHistory,
            history = decodeHistory(prefs[HISTORY]),
        )
    }

    private fun decodeHistory(text: String?): List<HistoryEntry> =
        text?.let { runCatching { json.decodeFromString(HISTORY_SERIALIZER, it) }.getOrNull() }
            ?: emptyList()

    private companion object {
        val QUALITY = stringPreferencesKey("quality")
        val HWDEC = booleanPreferencesKey("hardware_decoding")
        val YTDL_FIRST = booleanPreferencesKey("ytdl_first")
        val WHOLE_PLAYLISTS = booleanPreferencesKey("whole_playlists")
        val CHECK_UPDATES = booleanPreferencesKey("check_updates")
        val KEEP_HISTORY = booleanPreferencesKey("keep_history")
        val HISTORY = stringPreferencesKey("history")
        const val HISTORY_SIZE = 100
        val HISTORY_SERIALIZER = ListSerializer(HistoryEntry.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}
