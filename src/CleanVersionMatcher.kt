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
        return candidates
            .asSequence()
            .filter { it.isExplicit != true && !it.isVideo && it.videoId != explicit.videoId }
            .filter { coreTitle(it.title) == wantedTitle }
            .filter { TrackMatcher.sharesArtist(explicit.artist, it.artist) }
            .closestIn(explicit, requireLength = false)
    }

    /**
     * A clean edit someone uploaded to YouTube. Accepted only when the upload
     * says it is clean ("Clean", "Clean Version", "Edited", "Radio Edit"), names
     * the song, names the artist (in its title, or as its channel), isn't one
     * of the things that share a song's name without being it (a cover, a
     * remix, karaoke, a slowed or sped-up edit, a live take, a lip sync…), and
     * runs within [MAX_LENGTH_DIFFERENCE_SEC] of the original. Its length has
     * to be known: an upload is too loose a match to take on trust.
     */
    fun pickVideo(explicit: Song, videos: List<Song>): Song? {
        val wantedTitle = coreTitle(explicit.title)
        if (wantedTitle.isBlank()) return null
        val names = artistNames(explicit)
        return videos
            .asSequence()
            .filter { it.videoId != explicit.videoId && it.isExplicit != true }
            .filter { CLEAN_MARK.containsMatchIn(fold(it.title)) }
            .filter { video -> REJECT.findAll(fold(video.title)).none { it.value !in wantedTitle } }
            .filter { video -> titleParts(video.title, names).any { it == wantedTitle } }
            .filter { video ->
                val title = " ${coreTitle(video.title)} "
                names.any { " $it " in title } || TrackMatcher.sharesArtist(explicit.artist, video.artist)
            }
            .closestIn(explicit, requireLength = true)
    }

    private fun Sequence<Song>.closestIn(explicit: Song, requireLength: Boolean): Song? {
        val wantedLength = TrackMatcher.secondsOf(explicit.durationText)
        return mapNotNull { candidate ->
            val length = TrackMatcher.secondsOf(candidate.durationText)
            val gap = when {
                wantedLength != null && length != null -> abs(wantedLength - length)
                requireLength -> return@mapNotNull null
                else -> 0
            }
            if (gap > MAX_LENGTH_DIFFERENCE_SEC) null else candidate to gap
        }
            .withIndex()
            .minWithOrNull(compareBy({ it.value.second }, { it.index }))
            ?.value?.first
    }

    /** The title without its edition or source tag, featured credit, case or punctuation. */
    internal fun coreTitle(title: String): String =
        fold(title)
            .replace(EDITION_TAG, " ")
            .replace(EDITION_SUFFIX, " ")
            .replace(SOURCE_TAG, " ")
            .replace(SOURCE_SUFFIX, " ")
            .replace(FEATURING, " ")
            .tidy()

    /**
     * The pieces of an upload's title that could be the song's name: brackets
     * dropped, split at " - ", " | " and ": ", each piece without the artist's
     * name, a featured credit or a trailing "clean".
     */
    internal fun titleParts(title: String, artistNames: List<String>): List<String> =
        fold(title)
            .replace(BRACKETED, " ")
            .split(PART_BREAK)
            .map { part ->
                var piece = " " + part.replace(FEATURING_BARE, " ").tidy() + " "
                artistNames.forEach { piece = piece.replace(" $it ", " ") }
                piece.tidy().replace(TRAILING_CLEAN, "").tidy()
            }
            .filter { it.isNotBlank() }

    private fun artistNames(song: Song): List<String> =
        (song.artists.map { it.name } + song.artist.split(ARTIST_BREAK))
            .map { it.tidyName() }
            .filter { it.length >= 2 }
            .distinct()

    private fun String.tidyName() = fold(this).tidy()

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase(Locale.ROOT)

    private fun String.tidy(): String = replace(NON_WORD, " ").trim().replace(SPACES, " ")

    /** An edit mutes words; it doesn't drop a verse. Anything further apart is another cut. */
    const val MAX_LENGTH_DIFFERENCE_SEC = 15

    private const val EDITION_WORDS =
        "explicit|clean|dirty|edited|censored|radio edit|radio version|clean version|clean edit|explicit version|album version"
    private val EDITION_TAG = Regex("""[(\[]\s*(?:$EDITION_WORDS)\s*[)\]]""")
    private val EDITION_SUFFIX = Regex("""\s+-\s+(?:$EDITION_WORDS)\s*$""")
    private val SOURCE_TAG = Regex("""[(\[]\s*from\s[^)\]]*[)\]]""")
    private val SOURCE_SUFFIX = Regex("""\s+-\s+from\s.*$""")
    private val FEATURING = Regex("""[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s[^)\]]*[)\]]""")
    private val FEATURING_BARE = Regex("""\b(?:feat\.?|ft\.?|featuring)\s.*$""")
    private val BRACKETED = Regex("""[(\[][^)\]]*[)\]]""")
    private val PART_BREAK = Regex("""\s+[-–—|]\s+|\s*\|\s*|:\s+""")
    private val TRAILING_CLEAN = Regex("""(?:\s+(?:clean|edited|radio edit|clean version|version))+$""")
    private val ARTIST_BREAK = Regex("""\s*(?:,|&| x | feat\.? | ft\.? | and )\s*""")
    private val CLEAN_MARK = Regex("""\b(?:clean|edited|radio edit|censored)\b""")
    private val REJECT = Regex(
        """\b(?:cover|karaoke|instrumental|remix|slowed|sped up|speed up|reverb|8d|nightcore|live|lip ?sync|reaction|""" +
            """fan video|parody|mashup|bass boosted|acapella|a cappella|loop|hours?|piano|guitar|drum|dance|""" +
            """choreography|tutorial|lesson|minecraft|roblox|1950s|80s|lofi|lo fi)\b""",
    )
    private val DIACRITICS = Regex("""\p{Mn}+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}

