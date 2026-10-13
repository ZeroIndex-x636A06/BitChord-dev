package com.music.bitchord.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone's explicit-filter allow list: the shared [ExplicitAllowListStore],
 * kept in a preference file of its own rather than [AppSettings]'s.
 *
 * SharedPreferences loads a whole file at startup and rewrites the whole file
 * on every change, so a list that only grows would otherwise make every
 * unrelated settings toggle a little heavier for as long as the app is
 * installed. Here it costs nothing until a song is allowed or removed.
 */
object ExplicitAllowList {

    private lateinit var prefs: SharedPreferences

    private val store = ExplicitAllowListStore { json ->
        if (this::prefs.isInitialized) {
            val editor = prefs.edit()
            if (json == null) editor.remove(KEY_SONGS) else editor.putString(KEY_SONGS, json)
            editor.apply()
        }
    }

    val songs: StateFlow<List<AllowedExplicitSong>> get() = store.songs
    val keys: StateFlow<Set<String>> get() = store.keys

    fun init(context: Context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        store.load(prefs.getString(KEY_SONGS, null))
    }

    fun allow(song: Song) = store.allow(song)
    fun remove(song: Song) = store.remove(song)
    fun remove(entry: AllowedExplicitSong) = store.remove(entry)
    fun clear() = store.clear()
    fun replaceAll(entries: List<AllowedExplicitSong>) = store.replaceAll(entries)

    private const val FILE = "explicit_allow_list"
    private const val KEY_SONGS = "songs"
}
