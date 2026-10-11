package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.ExplicitAllowListStore
import com.music.bitchord.playback.ExplicitFilter
import com.music.bitchord.playback.ExplicitLyrics
import com.music.bitchord.playback.ExplicitLyricsChecker
import com.music.bitchord.playback.ExplicitLyricsStore
import kotlinx.coroutines.withTimeoutOrNull
import com.music.bitchord.playback.ExplicitFilterMode
import com.music.bitchord.playback.ExplicitPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The desktop app's explicit-filter settings (issue #720), kept in its own
 * preference store. The rules themselves are the shared [ExplicitFilter], so the
 * desktop app and the phone block exactly the same songs given the same settings.
 */
internal object DesktopExplicit {

    private val persistence = DesktopPersistence()

    private val _mode = MutableStateFlow(
        runCatching { ExplicitFilterMode.valueOf(persistence.string(KEY_MODE, ExplicitFilterMode.OFF.name)) }
            .getOrDefault(ExplicitFilterMode.OFF),
    )
    val mode: StateFlow<ExplicitFilterMode> = _mode.asStateFlow()

    private val _allowOwnPlaylists = MutableStateFlow(persistence.boolean(KEY_ALLOW_OWN_PLAYLISTS, false))
    val allowOwnPlaylists: StateFlow<Boolean> = _allowOwnPlaylists.asStateFlow()

    /**
     * Playlists known to be the account's, learned as they are opened. Kept so a
     * queue restored at launch can be judged before any page has been opened.
     */
    private val _ownPlaylistIds = MutableStateFlow(
        persistence.string(KEY_OWN_PLAYLISTS).split('\n').filter(String::isNotBlank).toSet(),
    )
    val ownPlaylistIds: StateFlow<Set<String>> = _ownPlaylistIds.asStateFlow()

    /** Also treat songs as explicit when their lyrics are — see [ExplicitLyrics]. Off by default. */
    private val _lyricsCheck = MutableStateFlow(persistence.boolean(KEY_LYRICS_CHECK, false))
    val lyricsCheck: StateFlow<Boolean> = _lyricsCheck.asStateFlow()

    /** What lyric checks found, kept so a song is read once. */
    val lyricsStore = ExplicitLyricsStore { encoded -> persistence.saveString(KEY_LYRICS_VERDICTS, encoded) }
        .also { it.load(persistence.string(KEY_LYRICS_VERDICTS).ifBlank { null }) }

    /**
     * Reads lyrics the way the lyrics panel does: the sources ticked in Settings, in their order.
     * "Lyrics unavailable" is an answer (no words to count); any other failure is no verdict.
     */
    val lyricsChecker = ExplicitLyricsChecker(lyricsStore) { song ->
        val found = withTimeoutOrNull(LYRICS_LOOKUP_MS) { DesktopLyricsClient.lookup(song) }
            ?: error("lyrics lookup timed out")
        found.fold(
            onSuccess = { lyrics -> lyrics.lines.map { it.text } },
            onFailure = { if (it.message == "Lyrics unavailable") emptyList() else throw it },
        )
    }

    /** Whether [song]'s lyrics still have to be read before the filter can judge it. */
    fun needsLyricsCheck(song: Song): Boolean =
        _mode.value != ExplicitFilterMode.OFF && _lyricsCheck.value && lyricsChecker.needsCheck(song)

    fun setLyricsCheck(value: Boolean) {
        _lyricsCheck.value = value
        persistence.saveBoolean(KEY_LYRICS_CHECK, value)
    }

    /** Songs let past the filter one at a time. Chunked storage: the list only grows. */
    val allowList = ExplicitAllowListStore { json -> persistence.saveString(KEY_ALLOWED, json.orEmpty()) }
        .also { it.load(persistence.string(KEY_ALLOWED).ifBlank { null }) }

    fun policy(): ExplicitPolicy = ExplicitPolicy(
        mode = _mode.value,
        allowed = allowList.keys.value,
        allowOwnPlaylists = _allowOwnPlaylists.value,
        ownPlaylistIds = _ownPlaylistIds.value,
        lyricsFlagged = if (_lyricsCheck.value) lyricsStore.flagged.value else emptySet(),
    )

    fun setMode(value: ExplicitFilterMode) {
        _mode.value = value
        persistence.saveString(KEY_MODE, value.name)
    }

    fun setAllowOwnPlaylists(value: Boolean) {
        _allowOwnPlaylists.value = value
        persistence.saveBoolean(KEY_ALLOW_OWN_PLAYLISTS, value)
    }

    /** Lets [song] through from now on, or stops letting it through. */
    fun setAllowed(song: Song, allowed: Boolean) {
        if (allowed) allowList.allow(song) else allowList.remove(song)
    }

    /** Records what an opened playlist says about who made it. */
    fun noteOwnership(browseId: String, owned: Boolean) {
        val current = _ownPlaylistIds.value
        val next = if (owned) current + browseId else current - browseId
        if (next == current) return
        _ownPlaylistIds.value = next
        persistence.saveString(KEY_OWN_PLAYLISTS, next.joinToString("\n"))
    }

    private const val KEY_MODE = "explicit_filter"
    private const val KEY_ALLOW_OWN_PLAYLISTS = "explicit_allow_own_playlists"
    private const val KEY_OWN_PLAYLISTS = "explicit_own_playlists"
    private const val KEY_ALLOWED = "explicit_allowed_songs"
    private const val KEY_LYRICS_CHECK = "explicit_lyrics_check"
    private const val KEY_LYRICS_VERDICTS = "explicit_lyrics_verdicts"
    private const val LYRICS_LOOKUP_MS = 6_000L
}

/** [ExplicitFilter.permits] against the desktop app's current settings. */
internal fun Song.desktopExplicitBlocked(
    suggestion: Boolean = false,
    sourceId: String? = playbackSourceId,
): Boolean = !ExplicitFilter.permits(this, DesktopExplicit.policy(), suggestion, sourceId)

/** Where Play over a whole list starts: the first track the filter lets through, else 0. */
internal fun List<Song>.desktopFirstPlayable(sourceId: String? = null): Int =
    indexOfFirst { !it.desktopExplicitBlocked(sourceId = sourceId ?: it.playbackSourceId) }.coerceAtLeast(0)

/** The desktop filter's settings as Compose state, so rows re-dim the moment they change. */
@Composable
internal fun rememberDesktopExplicitPolicy(): ExplicitPolicy {
    val mode by DesktopExplicit.mode.collectAsState()
    val allowed by DesktopExplicit.allowList.keys.collectAsState()
    val allowOwn by DesktopExplicit.allowOwnPlaylists.collectAsState()
    val ownIds by DesktopExplicit.ownPlaylistIds.collectAsState()
    val lyricsCheck by DesktopExplicit.lyricsCheck.collectAsState()
    val lyricsFlagged by DesktopExplicit.lyricsStore.flagged.collectAsState()
    return remember(mode, allowed, allowOwn, ownIds, lyricsCheck, lyricsFlagged) {
        ExplicitPolicy(mode, allowed, allowOwn, ownIds, if (lyricsCheck) lyricsFlagged else emptySet())
    }
}

/** The shared rows' "E from its lyrics" predicate. */
@Composable
internal fun rememberDesktopExplicitByLyrics(policy: ExplicitPolicy): (Song) -> Boolean =
    remember(policy.lyricsFlagged) { { song -> song.videoId in policy.lyricsFlagged } }

/** The shared rows' greying predicate for rows played from [sourceId], or from where each row says. */
@Composable
internal fun rememberDesktopRowBlocked(policy: ExplicitPolicy, sourceId: String? = null): (Song) -> Boolean =
    remember(policy, sourceId) {
        { song -> !ExplicitFilter.permits(song, policy, suggestion = false, sourceId = sourceId ?: song.playbackSourceId) }
    }
