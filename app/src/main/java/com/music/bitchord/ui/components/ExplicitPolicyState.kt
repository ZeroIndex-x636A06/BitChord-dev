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
 * A row that will be swapped for its clean release isn't blocked as far as
 * drawing goes; see [rememberExplicitRowSwapped]. One whose clean release
 * turned out not to exist goes back to being dimmed as soon as that's known.
 */
@Composable
fun rememberExplicitRowBlocked(policy: ExplicitPolicy, sourceId: String? = null): (Song) -> Boolean {
    val missing by CleanVersions.missing.collectAsStateWithLifecycle()
    return remember(policy, sourceId, missing) {
        { song ->
            val from = sourceId ?: song.playbackSourceId
            !ExplicitFilter.permits(song, policy, suggestion = false, sourceId = from) &&
                !ExplicitFilter.swapsToClean(song, policy, suggestion = false, sourceId = from, missing = missing)
        }
    }
}

/** The [LocalExplicitSwapped] predicate for rows played from [sourceId]. */
@Composable
fun rememberExplicitRowSwapped(policy: ExplicitPolicy, sourceId: String? = null): (Song) -> Boolean {
    val missing by CleanVersions.missing.collectAsStateWithLifecycle()
    return remember(policy, sourceId, missing) {
        { song ->
            ExplicitFilter.swapsToClean(
                song,
                policy,
                suggestion = false,
                sourceId = sourceId ?: song.playbackSourceId,
                missing = missing,
            )
        }
    }
}
