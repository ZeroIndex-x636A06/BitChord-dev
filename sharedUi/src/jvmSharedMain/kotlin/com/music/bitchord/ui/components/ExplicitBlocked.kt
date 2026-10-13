package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.ExplicitFilter
import com.music.bitchord.ui.AppUi

/** How dim a blocked explicit row is drawn, the same as an unavailable one. */
const val BLOCKED_ROW_ALPHA = 0.45f

/** A tap on a blocked explicit song, waiting on the app's alert: allow it, turn blocking off, or cancel. */
data class ExplicitPrompt(val videoId: String, val title: String, val play: () -> Unit)

/**
 * Whether "Block explicit songs" keeps [song] from playing. Song rows take this
 * as their `explicitBlocked` parameter and are drawn greyed out when it is
 * true, which is only ever the case while blocking is on.
 */
@Composable
fun rememberExplicitBlocked(song: Song): Boolean = rememberExplicitBlocked(song.isExplicit, song.videoId)

/** [rememberExplicitBlocked] for a Home card. */
@Composable
fun rememberExplicitBlocked(item: ShelfItem): Boolean = rememberExplicitBlocked(item.isExplicit, item.videoId)

@Composable
private fun rememberExplicitBlocked(isExplicit: Boolean?, videoId: String?): Boolean {
    val enabled by AppUi.host.blockExplicit.collectAsStateWithLifecycle()
    val allowed by AppUi.host.explicitAllowed.collectAsStateWithLifecycle()
    return ExplicitFilter.blocks(isExplicit, videoId, enabled, allowed)
}

/** A song row's tap: plays as usual, unless [blocked], when it asks first (see [ExplicitPrompt]). */
fun explicitGatedClick(song: Song, blocked: Boolean, onClick: () -> Unit): () -> Unit =
    explicitGatedClick(song.videoId, song.title, blocked, onClick)

/** [explicitGatedClick] for a Home card. */
fun explicitGatedClick(item: ShelfItem, blocked: Boolean, onClick: () -> Unit): () -> Unit =
    explicitGatedClick(item.videoId, item.title, blocked, onClick)

private fun explicitGatedClick(videoId: String?, title: String, blocked: Boolean, onClick: () -> Unit): () -> Unit =
    if (blocked && videoId != null) { { AppUi.host.askExplicitBlocked(ExplicitPrompt(videoId, title, onClick)) } } else onClick
