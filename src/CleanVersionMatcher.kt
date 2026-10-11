package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object CleanVersionMatcher {

    fun pick(explicit: Song, candidates: List<Song>): Song? {
        val wantedTitle = coreTitle(explicit.title)
        if (wantedTitle.isBlank()) return null
        val wantedLength = TrackMatcher.secondsOf(explicit.durationText)
        return candidates
            .asSequence()
            .filter { it.isExplicit != true && !it.isVideo && it.videoId != explicit.videoId }
            .filter { coreTitle(it.title) == wantedTitle }
            .filter { TrackMatcher.sharesArtist(explicit.artist, it.artist) }
            .mapNotNull { candidate ->
                val length = TrackMatcher.secondsOf(candidate.durationText)
                val gap = if (wantedLength != null && length != null) abs(wantedLength - length) else 0
                if (gap > MAX_LENGTH_DIFFERENCE_SEC) null else candidate to gap
            }
            .withIndex()
            .minWithOrNull(compareBy({ it.value.second }, { it.index }))
            ?.value?.first
    }

    /** The title without its edition tag, featured credit, case or punctuation. */
    internal fun coreTitle(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)
            .replace(EDITION_TAG, " ")
            .replace(EDITION_SUFFIX, " ")
            .replace(FEATURING, " ")
            .replace(NON_WORD, " ")
            .trim()
            .replace(SPACES, " ")

    /** An edit mutes words; it doesn't drop a verse. Anything further apart is another cut. */
    const val MAX_LENGTH_DIFFERENCE_SEC = 15

    private const val EDITION_WORDS =
        "explicit|clean|dirty|edited|censored|radio edit|radio version|clean version|clean edit|explicit version|album version"
    private val EDITION_TAG = Regex("""[(\[]\s*(?:$EDITION_WORDS)\s*[)\]]""")
    private val EDITION_SUFFIX = Regex("""\s+-\s+(?:$EDITION_WORDS)\s*$""")
    private val FEATURING = Regex("""[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s[^)\]]*[)\]]""")
    private val DIACRITICS = Regex("""\p{Mn}+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}

