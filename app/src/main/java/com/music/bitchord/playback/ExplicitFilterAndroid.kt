package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings

// The phone's conveniences over the shared [ExplicitFilter]: the same rules,
// read against [AppSettings]. Desktop has its own over its own settings.

/** [ExplicitFilter.permits] against the listener's current settings. */
fun Song.explicitBlocked(
    suggestion: Boolean = false,
    sourceId: String? = playbackSourceId,
): Boolean = !ExplicitFilter.permits(this, AppSettings.explicitPolicy(), suggestion, sourceId)

/**
 * Where a "Play" button over a whole list should start: the first track the
 * filter lets through. Falls back to 0 when none is, so the tap still answers
 * — with the prompt — instead of doing nothing.
 */
fun List<Song>.firstPlayableIndex(sourceId: String? = null): Int =
    indexOfFirst { !it.explicitBlocked(sourceId = sourceId ?: it.playbackSourceId) }.coerceAtLeast(0)

/** [firstPlayableIndex] for Shuffle: a random track the filter lets through. */
fun List<Song>.randomPlayableIndex(sourceId: String? = null): Int =
    indices.filter { !this[it].explicitBlocked(sourceId = sourceId ?: this[it].playbackSourceId) }
        .randomOrNull() ?: indices.random()
