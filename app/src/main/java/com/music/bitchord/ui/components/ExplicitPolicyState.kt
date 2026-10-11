package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.ExplicitFilter
import com.music.bitchord.playback.ExplicitPolicy

/** The explicit filter's settings as Compose state, so rows re-dim the moment they change. */
@Composable
fun rememberExplicitPolicy(): ExplicitPolicy {
    val mode by AppSettings.explicitFilter.collectAsStateWithLifecycle()
    val allowed by AppSettings.explicitAllowed.collectAsStateWithLifecycle()
    val allowOwn by AppSettings.explicitAllowOwnPlaylists.collectAsStateWithLifecycle()
    val ownIds by AppSettings.explicitOwnPlaylistIds.collectAsStateWithLifecycle()
    return remember(mode, allowed, allowOwn, ownIds) {
        ExplicitPolicy(mode, allowed, allowOwn, ownIds)
    }
}

/**
 * The [LocalExplicitBlocked] predicate for rows played from [sourceId] — a
 * page's browse id, or null to go by whatever each row already carries.
 */
@Composable
fun rememberExplicitRowBlocked(policy: ExplicitPolicy, sourceId: String? = null): (Song) -> Boolean =
    remember(policy, sourceId) {
        { song -> !ExplicitFilter.permits(song, policy, suggestion = false, sourceId = sourceId ?: song.playbackSourceId) }
    }
