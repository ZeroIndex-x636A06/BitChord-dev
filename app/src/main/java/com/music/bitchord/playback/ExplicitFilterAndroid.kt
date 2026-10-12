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
 * [ExplicitFilter.swapsToClean] against the listener's current settings: the
 * track is blocked, but will play as its clean release rather than be skipped.
 */
fun Song.explicitSwapsToClean(
    suggestion: Boolean = false,
    sourceId: String? = playbackSourceId,
): Boolean = ExplicitFilter.swapsToClean(
    this,
    AppSettings.explicitPolicy(),
    suggestion,
    sourceId,
    CleanVersions.missing.value,
)

/**
 * The version of this track that should be kept — downloaded, put on a
 * playlist: its clean release when the filter would play that instead and it
 * has been found, otherwise the track itself.
 */
fun Song.versionToKeep(): Song =
    if (isExplicit == true && explicitSwapsToClean()) CleanVersions.cached(this)?.let(::cleanStandIn) ?: this else this

/** Blocked with nothing to swap to: the tracks a queue leaves out. */
fun Song.explicitSkipped(
    suggestion: Boolean = false,
    sourceId: String? = playbackSourceId,
): Boolean = explicitBlocked(suggestion, sourceId) && !explicitSwapsToClean(suggestion, sourceId)

/**
 * Where a "Play" button over a whole list should start: the first track the
 * filter lets through (or will swap for its clean release). Falls back to 0
 * when none is, so the tap still answers — with the prompt — instead of doing
 * nothing.
 */
fun List<Song>.firstPlayableIndex(sourceId: String? = null): Int =
    indexOfFirst { !it.explicitSkipped(sourceId = sourceId ?: it.playbackSourceId) }.coerceAtLeast(0)

/** [firstPlayableIndex] for Shuffle: a random track the filter lets through. */
fun List<Song>.randomPlayableIndex(sourceId: String? = null): Int =
    indices.filter { !this[it].explicitSkipped(sourceId = sourceId ?: this[it].playbackSourceId) }
        .randomOrNull() ?: indices.random()
