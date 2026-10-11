package com.music.bitchord.playback

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Picks the clean release of an explicit track out of a list of search hits.
 *
 * Most explicit songs on YouTube Music also exist as a clean (edited) release:
 * same title, same artist, same length, no "E". What separates it from the
 * other rows of a search — a cover, a live take, a remix, the explicit
 * original again — is that it is the *same recording*, minus the words, so the
 * test is deliberately narrow:
 *
 * - not explicit;
 * - the same title once edition and source tags like "(Clean)", "(Radio Edit)"
 *   or "(From "8 Mile" Soundtrack)" are taken off both — a "(Live)" or
 *   "(Remix)" stays on and keeps it out;
 * - sharing an artist;
 * - within [MAX_LENGTH_DIFFERENCE_SEC] of the original's length, since an edit
 *   mutes words rather than cutting verses.
 *
 * The closest length wins; ties go to the search's own order.
 *
 * Plenty of songs have no clean release on YouTube Music at all, only clean
 * edits uploaded to YouTube ("Eminem - Lose Yourself (Clean)"). [pickVideo]
 * reads those, which name the song differently and need different care.
 */
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

/**
 * Finds, and remembers, the clean release of explicit tracks — for the explicit
 * filter's "Play clean versions instead" option.
 *
 * A lookup asks, in order and stopping at the first answer: YouTube Music's
 * Songs for "<title> <artist>", the same with "clean" added (some clean
 * releases only surface that way), then YouTube's videos for a clean edit
 * someone uploaded. What it finds — and that it found nothing — is kept, and
 * kept across launches once [attachStorage] has been called, so a song is
 * looked up once. A search that *failed* (offline, rate-limited) is not
 * remembered; the next attempt asks again.
 *
 * Lists only mark a song as swappable ([known]) once its clean release has
 * actually been found; [request] looks up the songs a list is showing, a few
 * at a time, so the marks fill in as they're found.
 *
 * Shared by the phone and the desktop app, which both search through
 * [YtMusicRepository].
 */
object CleanVersions {

    /**
     * How a lookup searches: the results for a query under a filter, or null
     * when the search itself failed. Signed out, because signed in YouTube
     * Music leaves out the very clean twins this is looking for. Swapped out
     * by tests.
     */
    internal var search: suspend (String, SearchFilter) -> List<Song>? = { query, filter ->
        YtMusicRepository.searchSignedOut(query, filter).getOrNull()
            ?.mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
    }

    private val found = ConcurrentHashMap<String, Song>()
    private val missingSince = ConcurrentHashMap<String, Long>()
    private val releases = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Song?>>()
    private val _missing = MutableStateFlow<Set<String>>(emptySet())
    private val _known = MutableStateFlow<Set<String>>(emptySet())
    private var save: ((String) -> Unit)? = null

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backgroundLookups = Semaphore(BACKGROUND_LOOKUPS)

    /**
     * Video ids of explicit tracks known to have no clean release. Rows watch
     * this: one found to have nothing to swap to looks blocked again.
     */
    val missing: StateFlow<Set<String>> = _missing.asStateFlow()

    /** Video ids of explicit tracks whose clean release has been found. Rows mark these with a "C". */
    val known: StateFlow<Set<String>> = _known.asStateFlow()

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
     * Restores what earlier lookups found from [encoded], and from then on hands
     * [save] the new state after every lookup. Each app keeps it where it keeps
     * its other caches.
     */
    @Synchronized
    fun attachStorage(encoded: String?, save: (String) -> Unit) {
        decode(encoded)
        this.save = save
    }

    /**
     * Looks [song] up in the background, a couple at a time — for lists, which
     * want to know which of their explicit rows can be swapped. Does nothing
     * for a song already answered or being looked up.
     */
    fun request(song: Song) {
        if (song.isExplicit != true || found.containsKey(song.videoId) || isMissing(song) || inFlight.containsKey(song.videoId)) return
        background.launch { backgroundLookups.withPermit { find(song) } }
    }

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
        val name = TrackMatcher.queries(TrackMatcher.targetOf(song)).firstOrNull() ?: return null
        var answered = false
        val steps = listOf(
            Triple(name, SearchFilter.SONGS, false),
            Triple("$name clean", SearchFilter.SONGS, false),
            Triple("$name clean", SearchFilter.VIDEOS, true),
        )
        for ((query, filter, videos) in steps) {
            val candidates = search(query, filter)
            if (candidates == null) {
                TrackLog.d(TAG, "clean version: \"$query\" (${filter.name}) failed", song.videoId)
                continue
            }
            answered = true
            val clean = if (videos) {
                CleanVersionMatcher.pickVideo(song, candidates)?.let { song.asCleanUpload(it) }
            } else {
                CleanVersionMatcher.pick(song, candidates)
            }
            if (clean != null) {
                TrackLog.d(TAG, "clean version of '${song.title}': ${clean.videoId} via \"$query\" (${filter.name})", song.videoId)
                remember(song.videoId, clean)
                return clean
            }
            TrackLog.d(TAG, "clean version: \"$query\" (${filter.name}) — ${candidates.size} rows, no match", song.videoId)
        }
        // Only a search that came back and simply had no clean release counts
        // as "none" — a failed one says nothing about the catalogue.
        if (answered) rememberMissing(song.videoId, System.currentTimeMillis())
        return null
    }

    /**
     * An uploaded clean edit, presented as the song it is an edit of: the
     * upload's id and length, the original's name, artist, album and art. Not
     * a "video" as far as the app is concerned, so "Prefer music-only version"
     * doesn't go looking for its audio — which would be the explicit original.
     */
    private fun Song.asCleanUpload(upload: Song): Song = copy(
        videoId = upload.videoId,
        durationText = upload.durationText ?: durationText,
        isExplicit = false,
        isVideo = false,
        isVideoOrigin = false,
        setVideoId = null,
        localUri = null,
        localPath = null,
        queueEntryId = null,
    )

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

    // ---- Memory -------------------------------------------------------------

    private fun remember(explicitId: String, clean: Song) {
        found[explicitId] = clean
        releases += clean.videoId
        missingSince.remove(explicitId)
        publish()
    }

    private fun rememberMissing(explicitId: String, at: Long) {
        missingSince[explicitId] = at
        publish()
    }

    private fun publish() {
        _known.value = found.keys.toSet()
        _missing.update { missingSince.keys.toSet() }
        save?.invoke(encode())
    }

    private fun encode(): String {
        val newestMissing = missingSince.entries.sortedByDescending { it.value }.take(MAX_REMEMBERED)
        return buildJsonObject {
            put("v", LOOKUP_VERSION)
            put("found", buildJsonArray {
                found.entries.take(MAX_REMEMBERED).forEach { (id, clean) ->
                    add(buildJsonObject {
                        put("for", id)
                        put("id", clean.videoId)
                        put("title", clean.title)
                        put("artist", clean.artist)
                        clean.thumbnailUrl?.let { put("art", it) }
                        clean.durationText?.let { put("length", it) }
                        clean.artistId?.let { put("artistId", it) }
                        clean.albumId?.let { put("albumId", it) }
                        clean.albumName?.let { put("album", it) }
                    })
                }
            })
            put("missing", buildJsonArray {
                newestMissing.forEach { (id, at) -> add(buildJsonObject { put("for", id); put("at", at) }) }
            })
        }.toString()
    }

    private fun decode(encoded: String?) {
        val root = encoded?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return
        val now = System.currentTimeMillis()
        (root["found"] as? JsonArray)?.forEach { element ->
            val o = element as? JsonObject ?: return@forEach
            val forId = o.text("for") ?: return@forEach
            val id = o.text("id") ?: return@forEach
            found[forId] = Song(
                videoId = id,
                title = o.text("title").orEmpty(),
                artist = o.text("artist").orEmpty(),
                thumbnailUrl = o.text("art"),
                durationText = o.text("length"),
                artistId = o.text("artistId"),
                albumId = o.text("albumId"),
                albumName = o.text("album"),
                isExplicit = false,
            )
            releases += id
        }
        // "None" from an older lookup isn't believed: it may have been asked in
        // a way that couldn't see the clean release (signed in, before 2).
        val sameLookup = (root["v"] as? JsonPrimitive)?.longOrNull == LOOKUP_VERSION.toLong()
        if (sameLookup) (root["missing"] as? JsonArray)?.forEach { element ->
            val o = element as? JsonObject ?: return@forEach
            val forId = o.text("for") ?: return@forEach
            val at = (o["at"] as? JsonPrimitive)?.longOrNull ?: return@forEach
            // Catalogues gain clean releases; "none" is asked again after a while.
            if (now - at < MISSING_FOR_MS) missingSince[forId] = at
        }
        _known.value = found.keys.toSet()
        _missing.value = missingSince.keys.toSet()
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Forgets everything; for tests. */
    internal fun reset() {
        found.clear()
        missingSince.clear()
        releases.clear()
        inFlight.clear()
        save = null
        _missing.value = emptySet()
        _known.value = emptySet()
    }

    private const val TAG = "BitChord"

    /** Bumped when the lookup changes in a way that could now find what it missed. */
    private const val LOOKUP_VERSION = 2

    /** Searches a batch of suggestions may have running together. */
    private const val LOOKUPS_AT_ONCE = 4

    /** Lookups for what lists are showing, run together in the background. */
    private const val BACKGROUND_LOOKUPS = 2

    /** Most answers of each kind kept across launches. */
    private const val MAX_REMEMBERED = 2_000

    /** How long "no clean release" is believed before asking again: 30 days. */
    private const val MISSING_FOR_MS = 30L * 24 * 60 * 60 * 1000
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
