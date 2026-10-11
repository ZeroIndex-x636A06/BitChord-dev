package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song

/**
 * Whether a song row should be drawn greyed out because the explicit filter
 * blocks it.
 *
 * Blocked songs stay in their lists — an album or playlist with a gap where a
 * track was reads as broken — and are dimmed instead, the way Spotify shows
 * them. Tapping one still works: it asks whether to allow the song.
 *
 * Provided by the app, which owns the filter's settings. The default blocks
 * nothing, so a host that never provides it (the desktop app, for now) draws
 * every row as it always has. A page whose rows play from somewhere the filter
 * treats differently — the listener's own playlist — provides its own.
 */
val LocalExplicitBlocked = compositionLocalOf<(Song) -> Boolean> { { false } }

/** How far a blocked row is dimmed — the same as a Spotify track with no match. */
const val BLOCKED_ROW_ALPHA = 0.45f

/** [LocalExplicitBlocked] for a feed card, which is a song only when it carries a video id. */
@Composable
fun ShelfItem.isExplicitBlocked(): Boolean {
    val id = videoId ?: return false
    if (isExplicit != true) return false
    val blocked = LocalExplicitBlocked.current
    return blocked(Song(videoId = id, title = title, artist = subtitle, thumbnailUrl = thumbnailUrl, isExplicit = true))
}

/** Dims to [BLOCKED_ROW_ALPHA] when [blocked]. */
fun Modifier.explicitDimmed(blocked: Boolean): Modifier =
    if (blocked) alpha(BLOCKED_ROW_ALPHA) else this
