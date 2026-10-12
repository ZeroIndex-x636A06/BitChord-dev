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
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * Starts reading [song] and waits for the verdict for at most [TAP_WAIT_MS]
     * — what a tap holds the song for. A check still running after that keeps
     * going, and when it finds the song explicit the player drops it, whether
     * it has started or not; null meanwhile.
     */
    suspend fun checkBeforePlaying(song: Song): Boolean? {
        val verdict = scope.async { checker.check(song) }
        return withTimeoutOrNull(TAP_WAIT_MS) { verdict.await() }
    }

    /**
     * How long a tap waits on the lyric check before the song starts anyway.
     * Most songs are settled well inside it, by YouTube Music's own lyrics.
     */
    const val TAP_WAIT_MS = 2_500L

    private const val FILE = "explicit_lyrics"
    private const val KEY_VERDICTS = "verdicts"
}
