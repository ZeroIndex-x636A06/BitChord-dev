package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Picks the clean release of an explicit track out of a list of search hits.
 *
 * Most explicit songs on YouTube Music also exist as a clean (edited) release:
 * same title, same artist, same length, no "E". Search "eminem rap god" and the
 * Songs shelf carries both. What separates the clean one from the other rows
 * there — a cover, a live take, a remix, the explicit original again — is that
 * it is the *same recording*, minus the words, so the test is deliberately
 * narrow:
 *
 * - not explicit, and not a video;
 * - the same title once edition tags like "(Clean)" or "(Radio Edit)" are
 *   taken off both — a "(Live)" or "(Remix)" stays on and keeps it out;
 * - sharing an artist;
 * - within [MAX_LENGTH_DIFFERENCE_SEC] of the original's length when both say,
 *   since an edit mutes words rather than cutting verses.
 *
 * The closest length wins; ties go to the search's own order.
 */
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

/**
 * Finds, and remembers, the clean release of explicit tracks — for the explicit
 * filter's "Play clean versions instead" option.
 *
 * One Songs search per explicit track, at most once per run of the app: what
 * was found is kept, and so is having found nothing, because the rows for a
 * track with no clean release go back to looking blocked — see [missing]. A
 * search that *failed* (offline, rate-limited) is not remembered as "none"; the
 * next attempt asks again.
 *
 * Shared by the phone and the desktop app, which both search through
 * [YtMusicRepository].
 */
object CleanVersions {

    /**
     * How a lookup searches: the Songs results for a query, or null when the
     * search itself failed. Swapped out by tests.
     */
    internal var search: suspend (String) -> List<Song>? = { query ->
        YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            ?.filterIsInstance<SearchResult.Track>()
            ?.map { it.song }
    }

    private val found = ConcurrentHashMap<String, Song>()
    private val releases = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Song?>>()
    private val _missing = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Video ids of explicit tracks known to have no clean release. Rows watch
     * this: a track that will be swapped is drawn as playable, and one that
     * turns out to have nothing to swap to goes back to looking blocked.
     */
    val missing: StateFlow<Set<String>> = _missing.asStateFlow()

    /** The clean release already found for [song], if one has been. */
    fun cached(song: Song): Song? = found[song.videoId]

    fun isMissing(song: Song): Boolean = song.videoId in _missing.value

    /**
     * Whether [song] is a clean release this found. The lyric check leaves
     * these alone: lyrics sites print the uncensored words for edits too, and
     * reading them would block the very version that was swapped in.
     */
    fun isCleanRelease(song: Song): Boolean = song.videoId in releases

    /**
     * The clean release of [song], looked up if it hasn't been yet; null when
     * there is none or the lookup couldn't run. Concurrent callers for the same
     * track share one search.
     */
    suspend fun find(song: Song): Song? {
        if (song.isExplicit != true) return null
        found[song.videoId]?.let { return it }
        if (isMissing(song)) return null
        val mine = CompletableDeferred<Song?>()
        inFlight.putIfAbsent(song.videoId, mine)?.let { return it.await() }
        try {
            val result = lookUp(song)
            mine.complete(result)
            return result
        } catch (e: CancellationException) {
            mine.complete(null)
            throw e
        } catch (e: Exception) {
            mine.complete(null)
            return null
        } finally {
            inFlight.remove(song.videoId, mine)
        }
    }

    private suspend fun lookUp(song: Song): Song? {
        val queries = TrackMatcher.queries(TrackMatcher.targetOf(song)).take(MAX_QUERIES)
        var answered = false
        for (query in queries) {
            val candidates = search(query) ?: continue
            answered = true
            CleanVersionMatcher.pick(song, candidates)?.let { clean ->
                found[song.videoId] = clean
                releases += clean.videoId
                return clean
            }
        }
        // Only a search that came back and simply had no clean release counts
        // as "none" — a failed one says nothing about the catalogue.
        if (answered) _missing.update { it + song.videoId }
        return null
    }

    /**
     * A batch of suggestions (AutoPlay, radio) held to [policy]: what it lets
     * through as it is, what it blocks swapped for its clean release when
     * [ExplicitPolicy.swapToClean] is on and there is one, and the rest left
     * out. Order is kept.
     *
     * Only the first [lookUpTo] are looked up — a station is fifty tracks and
     * the queue takes ten — and no more than [LOOKUPS_AT_ONCE] at a time.
     * Blocked tracks past that are simply left out, as they would be without
     * the swap.
     */
    suspend fun applyToSuggestions(
        songs: List<Song>,
        policy: ExplicitPolicy,
        lookUpTo: Int,
    ): List<Song> {
        if (!policy.swapToClean) return ExplicitFilter.filter(songs, policy, suggestion = true)
        val gate = Semaphore(LOOKUPS_AT_ONCE)
        return coroutineScope {
            songs.mapIndexed { index, song ->
                async {
                    when {
                        ExplicitFilter.permits(song, policy, suggestion = true) -> song
                        index >= lookUpTo || isMissing(song) -> null
                        else -> gate.withPermit { find(song) }?.let(song::cleanStandIn)
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Forgets everything; for tests. */
    internal fun reset() {
        found.clear()
        releases.clear()
        inFlight.clear()
        _missing.value = emptySet()
    }

    /** Title-and-artist, then title alone: what [TrackMatcher.queries] offers first. */
    private const val MAX_QUERIES = 2

    /** Searches a batch of suggestions may have running together. */
    private const val LOOKUPS_AT_ONCE = 4
}

/**
 * [clean] standing in for this explicit track in the queue: the clean release's
 * own id, title, length and art, but this entry's place — its tier, queue
 * entry, and where it was played from — so the queue still reads as the album
 * or playlist the listener started.
 */
fun Song.cleanStandIn(clean: Song): Song = clean.copy(
    queueTier = queueTier,
    queueEntryId = queueEntryId,
    radioName = radioName,
    playbackSource = playbackSource,
    playbackSourceType = playbackSourceType,
    playbackSourceId = playbackSourceId,
)
