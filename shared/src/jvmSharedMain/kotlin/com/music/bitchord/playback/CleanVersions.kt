package com.music.bitchord.playback

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Picks the clean version of an explicit song out of search results.
 *
 * Most explicit songs on YouTube Music also exist as a clean release: same
 * title, same artist, same length, no "E". [pick] takes that, and nothing that
 * is merely similar — a cover, a live take or a remix keeps its tag and stays
 * out, and a cut more than [MAX_LENGTH_DIFFERENCE_SEC] longer or shorter is
 * another edit.
 *
 * Songs with no clean release often have a clean edit someone uploaded to
 * YouTube ("Eminem - Lose Yourself (Clean)"). [pickVideo] reads those, which
 * name the song in all sorts of ways and need stricter checks.
 */
object CleanVersionMatcher {

    /** The clean release of [explicit] among [candidates], the closest length first; null when there is none. */
    fun pick(explicit: Song, candidates: List<Song>): Song? {
        val wantedTitle = coreTitle(explicit.title)
        if (wantedTitle.isBlank()) return null
        return candidates.asSequence()
            .filter { it.isExplicit != true && !it.isVideo && it.videoId != explicit.videoId }
            .filter { coreTitle(it.title) == wantedTitle && TrackMatcher.sharesArtist(explicit.artist, it.artist) }
            .closestIn(explicit, requireLength = false)
    }

    /**
     * A clean edit someone uploaded to YouTube. Taken only when the upload says
     * it is clean, names the song and the artist (in its title, or as its
     * channel), isn't a cover, remix, slowed edit, live take or the like, and
     * runs within [MAX_LENGTH_DIFFERENCE_SEC] of the original. Its length has to
     * be known: an upload is too loose a match to take on trust.
     *
     * A song with a featured artist is often released as "<featured> Remix"
     * (Tame Impala's "Dracula (feat. JENNIE)" is "Dracula (JENNIE Remix)"), so a
     * remix named for one of the song's own featured artists still counts, and
     * an upload naming the featured artist is preferred.
     */
    fun pickVideo(explicit: Song, videos: List<Song>): Song? {
        val wantedTitle = coreTitle(explicit.title)
        if (wantedTitle.isBlank()) return null
        val names = artistNames(explicit)
        val featured = featuredNames(explicit)
        val matches = videos.asSequence()
            .filter { it.videoId != explicit.videoId && it.isExplicit != true }
            .filter { CLEAN_MARK.containsMatchIn(fold(it.title)) }
            .filter { video ->
                val title = " ${fold(video.title).tidy()} "
                REJECT.findAll(fold(video.title)).none { word ->
                    word.value !in wantedTitle && !(word.value == "remix" && featured.any { " $it remix " in title })
                }
            }
            .filter { video -> titleParts(video.title, names).any { it == wantedTitle } }
            .filter { video ->
                val title = " ${coreTitle(video.title)} "
                names.any { " $it " in title } || TrackMatcher.sharesArtist(explicit.artist, video.artist)
            }
            .toList()
        val namingFeatured = matches.filter { video ->
            val title = " ${fold(video.title).tidy()} "
            featured.any { " $it " in title }
        }
        return namingFeatured.asSequence().closestIn(explicit, requireLength = true)
            ?: matches.asSequence().closestIn(explicit, requireLength = true)
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

    /**
     * The title without edition or source tags ("(Clean)", "(From "8 Mile")"),
     * a featured credit, case or punctuation.
     */
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
     * dropped, split at " - ", " | " and ": ", and each piece without the
     * artist's name, a featured credit or a trailing "clean". Then all of them
     * together, for a title split over several pieces ("Artist - Song - Remix").
     */
    private fun titleParts(title: String, artistNames: List<String>): List<String> {
        val parts = fold(title)
            .replace(BRACKETED, " ")
            .split(PART_BREAK)
            .map { part ->
                var piece = " " + part.replace(FEATURING_BARE, " ").tidy() + " "
                artistNames.forEach { piece = piece.replace(" $it ", " ") }
                piece.tidy().replace(TRAILING_CLEAN, "").tidy()
            }
            .filter { it.isNotBlank() }
        return parts + parts.joinToString(" ")
    }

    private fun artistNames(song: Song): List<String> =
        (song.artists.map { it.name } + song.artist.split(ARTIST_BREAK))
            .map { fold(it).tidy() }
            .filter { it.length >= 2 }
            .distinct()

    /** The song's featured artists: its "(feat. …)" credit and every credited artist after the first. */
    private fun featuredNames(song: Song): List<String> {
        val credit = FEATURING.find(fold(song.title))?.value
            ?.replace(FEATURING_WORD, " ")
            ?.split(ARTIST_BREAK)
            .orEmpty()
        val credited = song.artists.drop(1).map { it.name } + song.artist.split(ARTIST_BREAK).drop(1)
        return (credit + credited).map { fold(it).tidy() }.filter { it.length >= 2 }.distinct()
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase(Locale.ROOT)

    private fun String.tidy(): String = replace(NON_WORD, " ").trim().replace(SPACES, " ")

    /** An edit mutes words; it doesn't drop a verse. Anything further apart is another cut. */
    const val MAX_LENGTH_DIFFERENCE_SEC = 15

    private const val EDITION_WORDS =
        "explicit|clean|dirty|edited|censored|radio edit|radio version|clean version|clean edit|" +
            "explicit version|album version"
    private val EDITION_TAG = Regex("""[(\[]\s*(?:$EDITION_WORDS)\s*[)\]]""")
    private val EDITION_SUFFIX = Regex("""\s+-\s+(?:$EDITION_WORDS)\s*$""")
    private val SOURCE_TAG = Regex("""[(\[]\s*from\s[^)\]]*[)\]]""")
    private val SOURCE_SUFFIX = Regex("""\s+-\s+from\s.*$""")
    private val FEATURING = Regex("""[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s[^)\]]*[)\]]""")
    private val FEATURING_WORD = Regex("""^[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s|[)\]]$""")
    private val FEATURING_BARE = Regex("""\b(?:feat\.?|ft\.?|featuring)\s.*$""")
    private val BRACKETED = Regex("""[(\[][^)\]]*[)\]]""")
    private val PART_BREAK = Regex("""\s+[-–—|]\s+|\s*\|\s*|:\s+""")
    private val TRAILING_CLEAN = Regex("""(?:\s+(?:clean|edited|radio edit|clean version|version))+$""")
    private val ARTIST_BREAK = Regex("""\s*(?:,|&| x | feat\.? | ft\.? | and )\s*""")
    internal val CLEAN_MARK = Regex("""\b(?:clean|edited|radio edit|censored)\b""")
    private val REJECT = Regex(
        """\b(?:cover|karaoke|instrumental|remix|slowed|sped up|speed up|reverb|8d|nightcore|live|""" +
            """lip ?sync|reaction|""" +
            """fan video|parody|mashup|bass boosted|acapella|a cappella|loop|hours?|piano|guitar|drum|dance|""" +
            """choreography|tutorial|lesson|lofi|lo fi)\b""",
    )
    private val DIACRITICS = Regex("""\p{Mn}+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}

/**
 * Finds the clean version of explicit songs for "Play clean versions instead".
 *
 * A lookup searches YouTube Music's Songs for "<title> <artist>", then the same
 * with "clean" added (some clean releases only surface that way), then YouTube's
 * videos for an uploaded clean edit, and stops at the first match. An upload is
 * handed out dressed as the song it edits ([asCleanUpload]), so it looks like
 * any other clean version, but stays marked as coming from a YouTube video.
 *
 * Answers, including "there is none", are kept for the rest of the session. A
 * search that failed (offline, rate-limited) isn't, so the next attempt asks
 * again.
 */
object CleanVersions {

    /**
     * How a lookup searches; null when the search itself failed. Without the
     * account's visitor id, since signed in YouTube Music leaves out some of
     * the clean releases this looks for. Swapped out by tests.
     */
    internal var search: suspend (String, SearchFilter) -> List<Song>? = { query, filter ->
        YtMusicRepository.searchSignedOut(query, filter).getOrNull()
            ?.mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
    }

    private val answers = ConcurrentHashMap<String, Answer>()

    /**
     * Lookups under way, by video id. Run in their own scope, so a caller giving
     * up on one (a player effect restarting) doesn't end it for the others.
     */
    private val inFlight = ConcurrentHashMap<String, Deferred<Song?>>()
    private val lookups = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Uploaded clean edits handed out so far, by upload id, as the song each one edits. */
    private val uploads = ConcurrentHashMap<String, Song>()

    /** Where each clean version handed out so far came from, by its id. */
    private val sources = ConcurrentHashMap<String, Source>()

    private class Answer(val clean: Song?)

    /** Where a clean version came from, named as the listener knows it. */
    enum class Source(val label: String) {
        /** YouTube Music's own clean release. */
        YOUTUBE_MUSIC("YouTube Music"),

        /** A clean edit someone uploaded to YouTube. */
        YOUTUBE("YouTube"),
    }

    /**
     * Where the clean version playing as [videoId], or found for the explicit
     * song [videoId], came from; null when it isn't one, or wasn't found this
     * session.
     */
    fun sourceOf(videoId: String): Source? = sources[answers[videoId]?.clean?.videoId ?: videoId]

    /**
     * The clean version of [song], looked up if it hasn't been; null when there
     * is none or the lookup couldn't run. Callers asking about the same song at
     * once share one lookup.
     */
    suspend fun find(song: Song): Song? {
        if (song.isExplicit != true) return null
        answers[song.videoId]?.let { return it.clean }
        val lookup = inFlight.computeIfAbsent(song.videoId) { id ->
            lookups.async(start = CoroutineStart.LAZY) { runCatching { lookUp(song) }.getOrNull() }
                .also { job -> job.invokeOnCompletion { inFlight.remove(id, job) } }
        }
        lookup.start()
        return lookup.await()
    }

    private suspend fun lookUp(song: Song): Song? {
        val name = TrackMatcher.queries(TrackMatcher.targetOf(song)).firstOrNull() ?: return null
        val steps = listOf(
            name to SearchFilter.SONGS,
            "$name clean" to SearchFilter.SONGS,
            "$name clean" to SearchFilter.VIDEOS,
        )
        // Rows that carry no length (Home cards) take it from the song's own
        // search result, since an upload is never taken without one.
        var target = song
        var failed = false
        for ((query, filter) in steps) {
            val rows = search(query, filter)
            if (rows == null) {
                failed = true
                continue
            }
            val clean = if (filter == SearchFilter.VIDEOS) {
                CleanVersionMatcher.pickVideo(target, rows)?.let { asCleanUpload(song, it) }
            } else {
                target = withLengthFrom(target, rows)
                CleanVersionMatcher.pick(target, rows)?.also { sources[it.videoId] = Source.YOUTUBE_MUSIC }
            }
            if (clean != null) {
                val source = sourceOf(clean.videoId)?.label
                val found = "clean version of '${song.title}': ${clean.videoId} from $source, via \"$query\""
                TrackLog.d(TAG, found, song.videoId)
                answers[song.videoId] = Answer(clean)
                return clean
            }
        }
        val note = if (failed) " (a search failed; will ask again)" else ""
        TrackLog.d(TAG, "no clean version of '${song.title}'$note", song.videoId)
        if (!failed) answers[song.videoId] = Answer(null)
        return null
    }

    /** [song] with the length its own search result gives it, when it had none. */
    private fun withLengthFrom(song: Song, rows: List<Song>): Song {
        if (song.durationText != null) return song
        val own = rows.firstOrNull { it.videoId == song.videoId && it.durationText != null } ?: return song
        return song.copy(durationText = own.durationText)
    }

    /**
     * [upload], a clean edit of [explicit] uploaded to YouTube, as the clean
     * version of that song. On the surface it is the song: its title, artist,
     * album and art. Underneath it plays the upload as audio and is marked
     * [Song.isVideoOrigin], as the app marks any video played as a song, so
     * AutoMix leaves it alone. Remembered, so converting the upload to audio
     * later gives the same song ([uploadAsSong]).
     */
    fun asCleanUpload(explicit: Song, upload: Song): Song {
        val dressed = explicit.copy(
            videoId = upload.videoId,
            durationText = upload.durationText ?: explicit.durationText,
            isExplicit = false,
            isVideo = false,
            isVideoOrigin = true,
            setVideoId = null,
            localUri = null,
            localPath = null,
            queueEntryId = null,
        )
        uploads[upload.videoId] = dressed
        sources[upload.videoId] = Source.YOUTUBE
        return dressed
    }

    /** The song an uploaded clean edit was handed out as, if [videoId] is one. */
    fun uploadAsSong(videoId: String): Song? = uploads[videoId]

    /** Whether a video's title says it is a clean edit ("Clean Version", "Radio Edit", "Censored"). */
    fun saysClean(video: Song): Boolean =
        CleanVersionMatcher.CLEAN_MARK.containsMatchIn(video.title.lowercase(Locale.ROOT))

    /** Forgets every answer. For tests. */
    internal fun reset() {
        answers.clear()
        inFlight.clear()
        uploads.clear()
        sources.clear()
    }

    private const val TAG = "CleanVersions"
}

/**
 * [clean] standing in for this explicit song in the queue: the clean version's
 * id, title, length and art, in this entry's place (its tier, queue entry and
 * where it was played from), so the queue still reads as the album or playlist
 * the listener started. Marked as not explicit, which YouTube Music never says
 * of a clean song, so finding a better-quality copy can't land on the explicit
 * one.
 */
fun Song.cleanStandIn(clean: Song): Song = clean.copy(
    isExplicit = false,
    queueTier = queueTier,
    queueEntryId = queueEntryId,
    radioName = radioName,
    playbackSource = playbackSource,
    playbackSourceType = playbackSourceType,
    playbackSourceId = playbackSourceId,
)

/**
 * With "Play clean versions instead" on, an album or single listed in both of
 * its editions shows only the clean one: the explicit card is left out when a
 * card of the same release without the badge (same title once edition tags are
 * set aside, same subtitle) is in the same list. One listed alone stays, and
 * its songs still play clean.
 */
object CleanEditions {
    fun shelves(shelves: List<HomeShelf>): List<HomeShelf> = shelves.map { shelf ->
        val kept = cards(shelf.items)
        if (kept.size == shelf.items.size) shelf else shelf.copy(items = kept)
    }

    fun cards(items: List<ShelfItem>): List<ShelfItem> = keepClean(
        items,
        key = { key(it.title, it.subtitle) },
        explicit = { it.isExplicit == true },
        collection = { it.browseId != null && it.videoId == null },
    )

    fun searchRows(rows: List<SearchResult>): List<SearchResult> = keepClean(
        rows,
        key = { key((it as SearchResult.Browse).item.title, it.item.subtitle) },
        explicit = { (it as SearchResult.Browse).item.isExplicit == true },
        collection = { it is SearchResult.Browse },
    )

    private fun <T> keepClean(
        items: List<T>,
        key: (T) -> String,
        explicit: (T) -> Boolean,
        collection: (T) -> Boolean,
    ): List<T> {
        val clean = items.filter { collection(it) && !explicit(it) }.mapTo(HashSet(), key)
        if (clean.isEmpty()) return items
        return items.filterNot { collection(it) && explicit(it) && key(it) in clean }
    }

    private fun key(title: String, subtitle: String): String =
        CleanVersionMatcher.coreTitle(title) + "|" + subtitle.trim().lowercase(Locale.ROOT)
}
