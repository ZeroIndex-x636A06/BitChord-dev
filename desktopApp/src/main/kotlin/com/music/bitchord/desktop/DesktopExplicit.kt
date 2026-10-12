package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.ExplicitAllowListStore
import com.music.bitchord.playback.CleanVersions
import com.music.bitchord.playback.ExplicitFilter
import com.music.bitchord.playback.ExplicitLyrics
import com.music.bitchord.playback.ExplicitLyricsChecker
import com.music.bitchord.playback.ExplicitLyricsStore
import com.music.bitchord.playback.ExplicitFilterMode
import com.music.bitchord.playback.ExplicitPolicy
import com.music.bitchord.playback.StandardExplicitLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
     * Reads YouTube Music's own lyrics and the lyrics sources ticked in Settings — see
     * [ExplicitLyricsChecker] for which words count. A source left unticked is never contacted.
     */
    // The lyrics sources ticked in Settings — none with lyrics switched off, which the setting's
    // row says.
    val lyricsChecker = ExplicitLyricsChecker(
        lyricsStore,
        StandardExplicitLyrics {
            val settings = DesktopPersistence()
            if (!settings.boolean(DesktopLyricsClient.KEY_SYNCED_LYRICS, true)) {
                emptySet()
            } else {
                DesktopLyricsClient.enabledSources(settings.lyricsSourceOrder(), settings.lyricsEnabledSources())
                    .mapNotNull(::lyricsSourceNamed)
                    .toSet()
            }
        },
        log = { _, message -> DesktopTrackLog.log(message) },
    )

    /** Checks outlive whatever started them: a click stops waiting, the check doesn't. */
    private val lyricsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The verdict for a song about to play, read in full if it hasn't been — the click waits for
     * it, at most [ExplicitLyricsChecker.BUDGET_MS]. Run where leaving the page can't cut it short;
     * null when it couldn't be read.
     */
    suspend fun checkLyricsBeforePlaying(song: Song): Boolean? = lyricsScope.async { lyricsChecker.check(song) }.await()

    /** Prechecks running together, so a scrolled list doesn't fire off a burst. */
    private val prechecks = Semaphore(PRECHECKS_AT_ONCE)

    /** [ExplicitLyricsChecker.precheck] for a song on screen, a couple at a time. */
    /** Prechecks for a list about to be shown, run together — see [prepareLyrics]. */
    private val preparing = Semaphore(PREPARES_AT_ONCE)

    /**
     * Reads the lyrics of the first [PREPARE_ROWS] songs a list is about to
     * show before it is shown, waiting at most [budgetMs], so their explicit
     * mark is there from the start; the next [PREPARE_AHEAD] are read behind
     * it. The phone's `ExplicitLyricsCheck.prepare`.
     */
    suspend fun prepareLyrics(songs: List<Song>, budgetMs: Long) {
        val pending = songs.filter(::needsLyricsCheck).distinctBy { it.videoId }
        if (pending.isEmpty()) return
        val first = pending.take(PREPARE_ROWS).map { song ->
            lyricsScope.async { preparing.withPermit { lyricsChecker.precheck(song) } }
        }
        pending.drop(PREPARE_ROWS).take(PREPARE_AHEAD).forEach(::precheckLyrics)
        if (budgetMs > 0) withTimeoutOrNull(budgetMs) { first.awaitAll() }
    }

    fun precheckLyrics(song: Song) {
        if (!needsLyricsCheck(song)) return
        lyricsScope.launch { prechecks.withPermit { lyricsChecker.precheck(song) } }
    }

    /** Whether [song]'s lyrics still have to be read before the filter can judge it. */
    fun needsLyricsCheck(song: Song): Boolean =
        _mode.value != ExplicitFilterMode.OFF && _lyricsCheck.value && lyricsChecker.needsCheck(song)

    fun setLyricsCheck(value: Boolean) {
        _lyricsCheck.value = value
        persistence.saveBoolean(KEY_LYRICS_CHECK, value)
    }

    /** Play a blocked track's clean release instead — see [CleanVersions]. */
    private val _swapClean = MutableStateFlow(persistence.boolean(KEY_SWAP_CLEAN, false))
    val swapClean: StateFlow<Boolean> = _swapClean.asStateFlow()

    init {
        // What clean-version lookups found, kept so each song is looked up once.
        CleanVersions.attachStorage(persistence.string(KEY_CLEAN_VERSIONS).ifBlank { null }) { encoded ->
            persistence.saveString(KEY_CLEAN_VERSIONS, encoded)
        }
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
        swapToClean = _swapClean.value,
    )

    fun setSwapClean(value: Boolean) {
        _swapClean.value = value
        persistence.saveBoolean(KEY_SWAP_CLEAN, value)
    }

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
    private const val KEY_SWAP_CLEAN = "explicit_swap_clean"
    private const val KEY_CLEAN_VERSIONS = "explicit_clean_versions"
    private const val PRECHECKS_AT_ONCE = 2
    private const val PREPARES_AT_ONCE = 6
    private const val PREPARE_ROWS = 30
    private const val PREPARE_AHEAD = 60
}

/** [ExplicitFilter.permits] against the desktop app's current settings. */
internal fun Song.desktopExplicitBlocked(
    suggestion: Boolean = false,
    sourceId: String? = playbackSourceId,
): Boolean = !ExplicitFilter.permits(this, DesktopExplicit.policy(), suggestion, sourceId)

/** Blocked, but will play as its clean release — see [ExplicitFilter.swapsToClean]. */
internal fun Song.desktopExplicitSwapsToClean(
    suggestion: Boolean = false,
    sourceId: String? = playbackSourceId,
): Boolean = ExplicitFilter.swapsToClean(
    this,
    DesktopExplicit.policy(),
    suggestion,
    sourceId,
    CleanVersions.missing.value,
)

/** Blocked with nothing to swap to: what a queue leaves out. */
internal fun Song.desktopExplicitSkipped(
    suggestion: Boolean = false,
    sourceId: String? = playbackSourceId,
): Boolean = desktopExplicitBlocked(suggestion, sourceId) && !desktopExplicitSwapsToClean(suggestion, sourceId)

/** Where Play over a whole list starts: the first track the filter lets through (or swaps), else 0. */
internal fun List<Song>.desktopFirstPlayable(sourceId: String? = null): Int =
    indexOfFirst { !it.desktopExplicitSkipped(sourceId = sourceId ?: it.playbackSourceId) }.coerceAtLeast(0)

/** The desktop filter's settings as Compose state, so rows re-dim the moment they change. */
@Composable
internal fun rememberDesktopExplicitPolicy(): ExplicitPolicy {
    val mode by DesktopExplicit.mode.collectAsState()
    val allowed by DesktopExplicit.allowList.keys.collectAsState()
    val allowOwn by DesktopExplicit.allowOwnPlaylists.collectAsState()
    val ownIds by DesktopExplicit.ownPlaylistIds.collectAsState()
    val lyricsCheck by DesktopExplicit.lyricsCheck.collectAsState()
    val lyricsFlagged by DesktopExplicit.lyricsStore.flagged.collectAsState()
    val swapClean by DesktopExplicit.swapClean.collectAsState()
    return remember(mode, allowed, allowOwn, ownIds, lyricsCheck, lyricsFlagged, swapClean) {
        ExplicitPolicy(mode, allowed, allowOwn, ownIds, if (lyricsCheck) lyricsFlagged else emptySet(), swapClean)
    }
}

/** The shared rows' "E from its lyrics" predicate. */
@Composable
internal fun rememberDesktopExplicitByLyrics(policy: ExplicitPolicy): (Song) -> Boolean =
    remember(policy.lyricsFlagged) { { song -> song.videoId in policy.lyricsFlagged } }

/**
 * The shared rows' greying predicate for rows played from [sourceId], or from
 * where each row says. A row that will be swapped for its clean release isn't
 * greyed — see [rememberDesktopRowSwapped].
 */
@Composable
internal fun rememberDesktopRowBlocked(policy: ExplicitPolicy, sourceId: String? = null): (Song) -> Boolean {
    val swapped = rememberDesktopRowSwapped(policy, sourceId)
    return remember(policy, sourceId, swapped) {
        { song ->
            !ExplicitFilter.permits(song, policy, suggestion = false, sourceId = sourceId ?: song.playbackSourceId) &&
                !swapped(song)
        }
    }
}

/**
 * The shared rows' "plays as its clean version" predicate, for the faded "C": only once the clean
 * release has actually been found.
 */
@Composable
internal fun rememberDesktopRowSwapped(policy: ExplicitPolicy, sourceId: String? = null): (Song) -> Boolean {
    val known by CleanVersions.known.collectAsState()
    return remember(policy, sourceId, known) {
        { song ->
            song.videoId in known &&
                ExplicitFilter.swapsToClean(song, policy, suggestion = false, sourceId = sourceId ?: song.playbackSourceId)
        }
    }
}

/** The shared rows' background lookup of clean releases for explicit rows on screen. */
@Composable
internal fun rememberDesktopCleanVersionLookup(policy: ExplicitPolicy): (Song) -> Unit =
    remember(policy) {
        { song ->
            if (ExplicitFilter.swapsToClean(song, policy, suggestion = false, sourceId = song.playbackSourceId)) {
                CleanVersions.request(song)
            }
        }
    }
