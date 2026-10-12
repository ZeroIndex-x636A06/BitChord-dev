package com.music.bitchord.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.ExplicitLyricsChecker
import com.music.bitchord.playback.ExplicitLyricsStore
import com.music.bitchord.playback.StandardExplicitLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The phone's side of "Check lyrics for explicit content": the shared
 * [ExplicitLyricsStore], kept in a preference file of its own (it only grows,
 * like [ExplicitAllowList]), and a checker that reads YouTube Music's own
 * lyrics plus the lyrics sources switched on in Settings. A source that has
 * been switched off is not contacted for this either.
 */
object ExplicitLyricsCheck {

    private lateinit var prefs: SharedPreferences

    /** Checks outlive whatever started them: a tap stops waiting, the check doesn't. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store = ExplicitLyricsStore { encoded ->
        if (this::prefs.isInitialized) prefs.edit().putString(KEY_VERDICTS, encoded).apply()
    }

    val checker = ExplicitLyricsChecker(
        store,
        StandardExplicitLyrics {
            if (AppSettings.syncedLyrics.value) AppSettings.lyricsSources.value else emptySet()
        },
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        store.load(prefs.getString(KEY_VERDICTS, null))
    }

    /** Prechecks running together, so a scrolled list doesn't fire off a burst. */
    private val prechecks = Semaphore(PRECHECKS_AT_ONCE)

    /**
     * The verdict for a song about to play, read in full if it hasn't been —
     * the tap waits for it, at most [ExplicitLyricsChecker.BUDGET_MS]. Run
     * where leaving the screen can't cut it short; null when it couldn't be
     * read.
     */
    suspend fun checkBeforePlaying(song: Song): Boolean? = scope.async { checker.check(song) }.await()

    /** [ExplicitLyricsChecker.precheck] for a song on screen, a couple at a time. */
    fun precheck(song: Song) {
        if (!AppSettings.explicitNeedsLyricsCheck(song)) return
        scope.launch { prechecks.withPermit { checker.precheck(song) } }
    }

    private const val PRECHECKS_AT_ONCE = 2

    private const val FILE = "explicit_lyrics"
    private const val KEY_VERDICTS = "verdicts"
}
