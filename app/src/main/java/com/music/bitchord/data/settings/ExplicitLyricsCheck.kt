package com.music.bitchord.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.playback.ExplicitLyricsChecker
import com.music.bitchord.playback.ExplicitLyricsStore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's side of "Check lyrics for explicit content": the shared
 * [ExplicitLyricsStore], kept in a preference file of its own (it only grows,
 * like [ExplicitAllowList]), and a checker that reads lyrics the way the
 * player does — the sources switched on in Settings, in their order. A source
 * that has been switched off is not contacted for this either.
 */
object ExplicitLyricsCheck {

    private lateinit var prefs: SharedPreferences

    val store = ExplicitLyricsStore { encoded ->
        if (this::prefs.isInitialized) prefs.edit().putString(KEY_VERDICTS, encoded).apply()
    }

    val checker = ExplicitLyricsChecker(store) { song ->
        val sources = if (AppSettings.syncedLyrics.value) AppSettings.lyricsSources.value else emptySet()
        // Nothing to read with: no verdict, rather than "clean".
        if (sources.isEmpty()) error("no lyrics sources enabled")
        withTimeoutOrNull(LOOKUP_MS) {
            LyricsRepository.lyrics(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                durationMs = song.durationMillis(),
                album = song.albumName,
                sources = sources,
                order = AppSettings.lyricsSourceOrder.value,
            )?.lines?.map { it.text } ?: emptyList()
        } ?: error("lyrics lookup timed out")
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        store.load(prefs.getString(KEY_VERDICTS, null))
    }

    /** How long a lookup may hold up a tap before the song plays unread. */
    const val LOOKUP_MS = 6_000L

    private const val FILE = "explicit_lyrics"
    private const val KEY_VERDICTS = "verdicts"
}
