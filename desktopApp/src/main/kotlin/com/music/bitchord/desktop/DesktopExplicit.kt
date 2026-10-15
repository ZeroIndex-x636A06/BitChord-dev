package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.ExplicitPrompt
import com.music.bitchord.playback.ExplicitFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The desktop app's "Block explicit songs" settings, the counterpart of the
 * phone's `AppSettings.blockExplicit`. The rule itself is the shared
 * [ExplicitFilter].
 */
internal object DesktopExplicit {
    private val persistence = DesktopPersistence()

    /** The setting itself; off by default. */
    private val _enabled = MutableStateFlow(persistence.boolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Video ids of explicit songs let through anyway, from the song menu or the prompt. */
    private val _allowed = MutableStateFlow(
        persistence.string(KEY_ALLOWED).split('\n').filter(String::isNotBlank).toSet(),
    )
    val allowed: StateFlow<Set<String>> = _allowed.asStateFlow()

    /** "Play clean versions instead": a blocked song plays its clean version rather than being skipped. */
    private val _cleanVersions = MutableStateFlow(persistence.boolean(KEY_CLEAN_VERSIONS, false))
    val cleanVersions: StateFlow<Boolean> = _cleanVersions.asStateFlow()

    /** The tapped blocked explicit song the app's dialog is asking about; null when none is. */
    val prompt = MutableStateFlow<ExplicitPrompt?>(null)

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        persistence.saveBoolean(KEY_ENABLED, value)
    }

    fun setCleanVersions(value: Boolean) {
        _cleanVersions.value = value
        persistence.saveBoolean(KEY_CLEAN_VERSIONS, value)
    }

    fun setAllowed(videoId: String, allowed: Boolean) {
        _allowed.value = if (allowed) _allowed.value + videoId else _allowed.value - videoId
        persistence.saveString(KEY_ALLOWED, _allowed.value.joinToString("\n"))
    }

    fun blocks(song: Song): Boolean = ExplicitFilter.blocks(song, _enabled.value, _allowed.value)

    fun filter(songs: List<Song>): List<Song> = ExplicitFilter.filter(songs, _enabled.value, _allowed.value)

    private const val KEY_ENABLED = "block_explicit"
    private const val KEY_ALLOWED = "explicit_allowed"
    private const val KEY_CLEAN_VERSIONS = "play_clean_versions"

    /** How many songs ahead a blocked one is swapped for its clean version. */
    const val CLEAN_SWAP_LOOKAHEAD = 3
}
