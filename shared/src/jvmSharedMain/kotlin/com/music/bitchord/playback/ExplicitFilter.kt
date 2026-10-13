package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import java.text.Normalizer
import java.util.Locale

/**
 * How strictly explicit tracks are kept out of playback.
 *
 * Mirrors the two switches Spotify and Amazon Music offer (issue #720):
 * keep explicit songs out of what the app *chooses* for you, or keep them out
 * of everything unless you deliberately allow a particular song.
 */
enum class ExplicitFilterMode {
    /** No filtering. */
    OFF,

    /**
     * AutoPlay, radio and stations never add an explicit track. Anything the
     * listener picks by hand — an album, a playlist, a search hit — still plays.
     */
    SUGGESTIONS,

    /**
     * Explicit tracks don't play at all. Picking one asks first, and the answer
     * can allow that one song from then on.
     */
    EVERYWHERE,
}

/**
 * Everything the explicit filter decides with, read together so a check never
 * mixes one setting's old value with another's new one.
 *
 * @param allowOwnPlaylists under [ExplicitFilterMode.EVERYWHERE], let explicit
 *   tracks play when they're played from a playlist the listener made — the
 *   songs they put there themselves.
 * @param ownPlaylistIds browse ids of playlists known to be the listener's.
 * @param lyricsFlagged video ids whose lyrics made them explicit — see
 *   [ExplicitLyrics]. Empty unless "Check lyrics for explicit content" is on.
 * @param swapToClean play a blocked track's clean release in its place, when
 *   it has one, rather than skipping it or asking — see [CleanVersions].
 */
data class ExplicitPolicy(
    val mode: ExplicitFilterMode,
    val allowed: Set<String> = emptySet(),
    val allowOwnPlaylists: Boolean = false,
    val ownPlaylistIds: Set<String> = emptySet(),
    val lyricsFlagged: Set<String> = emptySet(),
    val swapToClean: Boolean = false,
) {
    companion object {
        val OFF = ExplicitPolicy(ExplicitFilterMode.OFF)
    }
}

/**
 * Decides whether a track may enter the queue under the explicit filter.
 *
 * Detection is first the catalogue's own explicit flag — the outlined "E"
 * YouTube Music already badges rows with, parsed into [Song.isExplicit]. A
 * track whose source says nothing ([Song.isExplicit] null) is treated as
 * clean, unless the listener has opted into reading lyrics too, in which case
 * one whose lyrics counted as explicit ([ExplicitLyrics]) is treated as if it
 * carried the badge.
 *
 * Shared by the phone and desktop apps, and pure: each app reads its own
 * settings into an [ExplicitPolicy] and asks here, so the rules can't drift
 * apart between them.
 */
object ExplicitFilter {

    /**
     * The keys a per-song bypass is stored under.
     *
     * The video id alone is too narrow: YouTube Music lists the same recording
     * under a different id on its album, its single and in search, so a song
     * allowed from one page would be blocked again on the next. The normalised
     * title and primary credit catch those. Both are stored, and either one
     * matching is enough.
     */
    fun allowKeys(song: Song): Set<String> = buildSet {
        add(ID_PREFIX + song.videoId)
        titleKey(song)?.let(::add)
    }

    /**
     * Whether the filter treats [song] as explicit: the catalogue's badge, or
     * its lyrics when those are being checked ([ExplicitPolicy.lyricsFlagged]).
     */
    fun isExplicit(song: Song, policy: ExplicitPolicy): Boolean =
        song.isExplicit == true || song.videoId in policy.lyricsFlagged

    fun isAllowedBySong(song: Song, allowed: Set<String>): Boolean =
        allowKeys(song).any { it in allowed }

    /**
     * Whether [song] may be queued.
     *
     * @param suggestion true when the app is choosing the track (AutoPlay,
     *   radio, a station) rather than the listener. A suggestion never counts
     *   as coming from the listener's playlist, even though AutoPlay copies
     *   the playlist's id onto what it adds.
     * @param sourceId the page or collection the track is being played from.
     *   Defaults to the one a queued track already carries; a row still on a
     *   page has none of its own, so callers pass the page's.
     */
    fun permits(
        song: Song,
        policy: ExplicitPolicy,
        suggestion: Boolean,
        sourceId: String? = song.playbackSourceId,
    ): Boolean {
        if (!isExplicit(song, policy)) return true
        if (isAllowedBySong(song, policy.allowed)) return true
        return when (policy.mode) {
            ExplicitFilterMode.OFF -> true
            ExplicitFilterMode.SUGGESTIONS -> !suggestion
            ExplicitFilterMode.EVERYWHERE ->
                !suggestion && policy.allowOwnPlaylists && sourceId != null && sourceId in policy.ownPlaylistIds
        }
    }

    /**
     * Whether [song] is blocked but will be played as its clean release
     * instead: [ExplicitPolicy.swapToClean] is on and the track isn't one
     * already known to have no clean release ([missing], from
     * [CleanVersions.missing]). Such a track is not skipped, dimmed or asked
     * about — it is swapped when it plays.
     */
    fun swapsToClean(
        song: Song,
        policy: ExplicitPolicy,
        suggestion: Boolean,
        sourceId: String? = song.playbackSourceId,
        missing: Set<String> = emptySet(),
    ): Boolean =
        policy.swapToClean &&
            // Only what the catalogue badged has a "clean release" to find; a
            // song caught by its lyrics has no edited twin to swap to.
            song.isExplicit == true &&
            song.videoId !in missing &&
            !permits(song, policy, suggestion, sourceId)

    fun filter(
        songs: List<Song>,
        policy: ExplicitPolicy,
        suggestion: Boolean,
        sourceId: String? = null,
    ): List<Song> =
        if (policy.mode == ExplicitFilterMode.OFF) songs
        else songs.filter { permits(it, policy, suggestion, sourceId ?: it.playbackSourceId) }

    private fun titleKey(song: Song): String? {
        val title = normalise(song.title)
        val artist = normalise(
            song.artists.firstOrNull()?.name
                ?: song.artist.split(" & ", ", ", " x ", " feat. ", " ft. ").first(),
        )
        if (title.isBlank() || artist.isBlank()) return null
        return "$TITLE_PREFIX$title|$artist"
    }

    /** Case, accents, punctuation and "(Explicit)"-style tags don't make a different song. */
    private fun normalise(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)
            .replace(EDITION_TAG, "")
            .replace(NON_WORD, " ")
            .trim()
            .replace(SPACES, " ")

    private const val ID_PREFIX = "id:"
    private const val TITLE_PREFIX = "t:"
    private val DIACRITICS = Regex("""\p{Mn}+""")
    private val EDITION_TAG = Regex("""[(\[]\s*(explicit|clean|dirty)(\s+version)?\s*[)\]]""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}
