package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ExplicitLyricsCheck
import com.music.bitchord.playback.CleanVersions
import com.music.bitchord.playback.ExplicitFilter
import com.music.bitchord.playback.ExplicitPolicy

/** The explicit filter's settings as Compose state, so rows re-dim the moment they change. */
@Composable
fun rememberExplicitPolicy(): ExplicitPolicy {
    val mode by AppSettings.explicitFilter.collectAsStateWithLifecycle()
    val allowed by AppSettings.explicitAllowed.collectAsStateWithLifecycle()
    val allowOwn by AppSettings.explicitAllowOwnPlaylists.collectAsStateWithLifecycle()
    val ownIds by AppSettings.explicitOwnPlaylistIds.collectAsStateWithLifecycle()
    val lyricsCheck by AppSettings.explicitLyricsCheck.collectAsStateWithLifecycle()
    val lyricsFlagged by ExplicitLyricsCheck.store.flagged.collectAsStateWithLifecycle()
    val swapClean by AppSettings.explicitSwapClean.collectAsStateWithLifecycle()
    return remember(mode, allowed, allowOwn, ownIds, lyricsCheck, lyricsFlagged, swapClean) {
        ExplicitPolicy(mode, allowed, allowOwn, ownIds, if (lyricsCheck) lyricsFlagged else emptySet(), swapClean)
    }
}

/** The [LocalExplicitByLyrics] predicate: songs whose lyrics, read, made them explicit. */
@Composable
fun rememberExplicitByLyrics(policy: ExplicitPolicy): (Song) -> Boolean =
    remember(policy.lyricsFlagged) { { song -> song.videoId in policy.lyricsFlagged } }

/**
 * The [LocalExplicitBlocked] predicate for rows played from [sourceId] — a
 * page's browse id, or null to go by whatever each row already carries.
 *
 * A row whose clean release has been found isn't blocked as far as drawing
 * goes — it plays, as that release; see [rememberExplicitRowSwapped]. Rows not
 * yet looked up stay dimmed until they are.
 */
@Composable
fun rememberExplicitRowBlocked(policy: ExplicitPolicy, sourceId: String? = null): (Song) -> Boolean {
    val swapped = rememberExplicitRowSwapped(policy, sourceId)
    return remember(policy, sourceId, swapped) {
        { song ->
            !ExplicitFilter.permits(song, policy, suggestion = false, sourceId = sourceId ?: song.playbackSourceId) &&
                !swapped(song)
        }
    }
}

/**
 * The [LocalExplicitSwapped] predicate for rows played from [sourceId]: blocked,
 * "Play clean versions instead" is on, and the clean release has actually been
 * found — not merely might exist.
 */
@Composable
fun rememberExplicitRowSwapped(policy: ExplicitPolicy, sourceId: String? = null): (Song) -> Boolean {
    val known by CleanVersions.known.collectAsStateWithLifecycle()
    return remember(policy, sourceId, known) {
        { song ->
            song.videoId in known &&
                ExplicitFilter.swapsToClean(song, policy, suggestion = false, sourceId = sourceId ?: song.playbackSourceId)
        }
    }
}

/**
 * The [LocalCleanVersionLookup] for rows on screen: an explicit row the filter
 * would swap gets its clean release looked up in the background.
 */
@Composable
fun rememberCleanVersionLookup(policy: ExplicitPolicy): (Song) -> Unit =
    remember(policy) {
        { song ->
            if (ExplicitFilter.swapsToClean(song, policy, suggestion = false, sourceId = song.playbackSourceId)) {
                CleanVersions.request(song)
            }
        }
    }
