package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.delay

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

/**
 * Whether a song the catalogue didn't badge has been found explicit from its
 * lyrics ("Check lyrics for explicit content"). Rows show the "E" for these
 * too, so a greyed row says why. Default: none are.
 */
val LocalExplicitByLyrics = compositionLocalOf<(Song) -> Boolean> { { false } }

/**
 * Starts the quick part of the lyric check for a song a list is showing, so a
 * tap on it has its verdict ready — see the app's lyric check. Provided by the
 * app, which only does anything while "Check lyrics for explicit content" is
 * on. Default: nothing.
 */
val LocalLyricsPrecheck = compositionLocalOf<(Song) -> Unit> { {} }

/**
 * Asks for [song]'s [LocalLyricsPrecheck] once it has stayed on screen for
 * [PRECHECK_AFTER_MS] — long enough that a list flung past isn't looked up
 * row by row.
 */
@Composable
fun RequestLyricsPrecheck(song: Song) {
    if (song.isExplicit == true) return
    val precheck = LocalLyricsPrecheck.current
    LaunchedEffect(song.videoId, precheck) {
        delay(PRECHECK_AFTER_MS)
        precheck(song)
    }
}

private const val PRECHECK_AFTER_MS = 400L

/**
 * Whether a row should wear the "E": badged by the catalogue, or by its
 * lyrics. Every row that might show one asks this, which is why it is also
 * where a row on screen asks for its [RequestLyricsPrecheck].
 */
@Composable
fun Song.showsExplicit(): Boolean {
    RequestLyricsPrecheck(this)
    return isExplicit == true || LocalExplicitByLyrics.current(this)
}

/** [showsExplicit] for a feed card. */
@Composable
fun ShelfItem.showsExplicit(): Boolean {
    val id = videoId ?: return false
    val song = asCardSong(id)
    RequestLyricsPrecheck(song)
    return isExplicit == true || LocalExplicitByLyrics.current(song)
}

private fun ShelfItem.asCardSong(id: String) =
    Song(videoId = id, title = title, artist = subtitle, thumbnailUrl = thumbnailUrl, isExplicit = isExplicit)

/** How far a blocked row is dimmed — the same as a Spotify track with no match. */
const val BLOCKED_ROW_ALPHA = 0.45f

/** [LocalExplicitBlocked] for a feed card, which is a song only when it carries a video id. */
@Composable
fun ShelfItem.isExplicitBlocked(): Boolean {
    val id = videoId ?: return false
    val blocked = LocalExplicitBlocked.current
    return blocked(asCardSong(id))
}

/** Dims to [BLOCKED_ROW_ALPHA] when [blocked]. */
fun Modifier.explicitDimmed(blocked: Boolean): Modifier =
    if (blocked) alpha(BLOCKED_ROW_ALPHA) else this
