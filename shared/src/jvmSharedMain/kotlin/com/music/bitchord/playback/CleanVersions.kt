package com.music.bitchord.playback

import com.music.bitchord.data.lyrics.YouTubeMusicLyrics
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import java.util.Collections
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

    fun pick(explicit: Song, candidates: List<Song>): Song? = picks(explicit, candidates).firstOrNull()

    /**
     * Every row that could be [explicit]'s clean release, closest length
     * first — what [pick] chooses from. An unbadged row is only a candidate:
     * YouTube Music leaves the badge off some copies of the explicit recording
     * too, so [CleanVersions] has each proven clean before taking it.
     */
    fun picks(explicit: Song, candidates: List<Song>): List<Song> {
        val wantedTitle = coreTitle(explicit.title)
        if (wantedTitle.isBlank()) return emptyList()
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
            .sortedWith(compareBy({ it.value.second }, { it.index }))
            .map { it.value.first }
            .distinctBy { it.videoId }
            .toList()
    }

    /**
     * A clean edit someone uploaded to YouTube. Accepted only when the upload
     * says it is clean ("Clean", "Clean Version", "Edited", "Radio Edit"), names
     * the song, names the artist (in its title, or as its channel), isn't one
     * of the things that share a song's name without being it (a cover, a
     * remix, karaoke, a slowed or sped-up edit, a live take, a lip sync…), and
     * runs within [MAX_LENGTH_DIFFERENCE_SEC] of the original. Its length has
     * to be known: an upload is too loose a match to take on trust.
     *
     * A song with a featured artist is often released as "<featured> Remix" —
     * Tame Impala's *Dracula (feat. JENNIE)* is "Dracula (JENNIE Remix)" — so
     * a remix named for one of the song's own featured artists is the song,
     * not a remix of it. Such a song can also have a solo version of the same
     * name, so an upload that names the featured artist is preferred; one that
     * doesn't is still taken when it's all there is, since plenty of uploads
     * leave the featured credit off.
     */
    fun pickVideo(explicit: Song, videos: List<Song>): Song? {
        val wantedTitle = coreTitle(explicit.title)
        if (wantedTitle.isBlank()) return null
        val names = artistNames(explicit)
        val featured = featuredNames(explicit)
        val matches = videos
            .asSequence()
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

    /** The song's featured artists: its "(feat. …)" credit and every credited artist after the first. */
    private fun featuredNames(song: Song): List<String> {
        val credit = FEATURING.find(fold(song.title))?.value
            ?.replace(FEATURING_WORD, " ")
            ?.split(ARTIST_BREAK)
            .orEmpty()
        val credited = song.artists.drop(1).map { it.name } + song.artist.split(ARTIST_BREAK).drop(1)
        return (credit + credited)
            .map { it.tidyName() }
            .filter { it.length >= 2 }
            .distinct()
    }

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
    private val FEATURING_WORD = Regex("""^[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s|[)\]]$""")
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

    /**
     * Whether [Song] — a catalogue row with no explicit badge — is really
     * clean: YouTube Music's own lyrics for that very recording have no
     * explicit words (a clean edit's are censored). Null when they couldn't be
     * asked; false when there are none, since an unbadged copy of the explicit
     * recording also comes without a badge, and taking it would play the very
     * words the swap is for. Swapped out by tests.
     */
    internal var provenClean: suspend (Song) -> Boolean? = { song ->
        runCatching { YouTubeMusicLyrics.lyricsOrThrow(song.videoId) }
            .map { lines ->
                val words = lines?.map { it.text }?.filter { it.isNotBlank() }.orEmpty()
                words.isNotEmpty() && !ExplicitLyrics.isExplicit(words, countMasked = false)
            }
            .getOrNull()
    }

    /** Oldest first, so the oldest answers are the ones let go at [MAX_REMEMBERED]. */
    private val found: MutableMap<String, Song> = Collections.synchronizedMap(LinkedHashMap())
    private val missingSince = ConcurrentHashMap<String, Long>()
    private val releases = ConcurrentHashMap.newKeySet<String>()

    /** Clean edits uploaded to YouTube, by upload id, as themselves — see [asVideo]. */
    private val uploadVideos = ConcurrentHashMap<String, Song>()

    /** The same uploads presented as the song each one edits — see [asAudio]. */
    private val uploadSongs = ConcurrentHashMap<String, Song>()
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
    fun cached(song: Song): Song? = found[song.videoId]?.let(::present)

    /**
     * An uploaded clean edit is handed out as the clean audio version of the
     * song it edits ([asAudio]) — a song in its own right, which downloads,
     * goes on playlists and plays like any other. "Convert to video" shows the
     * upload as itself ([asVideo]).
     */
    private fun present(clean: Song): Song = uploadSongs[clean.videoId] ?: clean

    /**
     * [song] as the app should show it: an uploaded clean edit that arrives as
     * the bare upload — off a playlist it was added to, say, since YouTube only
     * keeps the video — becomes its clean audio version again. Anything else
     * is returned as it is.
     */
    fun asCreated(song: Song): Song = if (song.isVideo) asAudio(song) ?: song else song

    /** Whether [song] is a clean edit someone uploaded to YouTube, in either form. */
    fun isCleanUpload(song: Song): Boolean = uploadSongs.containsKey(song.videoId)

    /**
     * [song], an uploaded clean edit, as the clean audio version of the song it
     * edits: that song's name, artist, album and art, still playing the upload.
     * Kept apart from the explicit original — it is never "converted" to that,
     * which would play the words it was swapped in to avoid. Null for anything
     * else.
     */
    fun asAudio(song: Song): Song? = uploadSongs[song.videoId]?.let { dressed ->
        song.copy(
            title = dressed.title,
            artist = dressed.artist,
            artists = dressed.artists,
            artistId = dressed.artistId,
            thumbnailUrl = dressed.thumbnailUrl,
            albumId = dressed.albumId,
            albumName = dressed.albumName,
            isExplicit = false,
            isVideo = false,
            isVideoOrigin = false,
        )
    }

    /** Whether [video]'s title says it is a clean edit ("Clean Version", "Radio Edit", "Censored"). */
    fun saysClean(video: Song): Boolean = SAYS_CLEAN.containsMatchIn(video.title)

    /**
     * The clean song for [upload], a video that says it is a clean edit of
     * [explicit]: YouTube Music's clean release of it when there is one;
     * otherwise [upload] itself, kept from now on as [explicit]'s clean audio
     * version ([asAudio]) — so the explicit song swaps to it elsewhere too.
     */
    suspend fun cleanFor(upload: Song, explicit: Song): Song {
        find(explicit)?.takeIf { !isCleanUpload(it) }?.let { return it }
        val video = rememberUpload(explicit, upload)
        remember(explicit.videoId, video)
        TrackLog.d(TAG, "clean version of '${explicit.title}': the upload '${upload.title}' it was played as", upload.videoId)
        return present(video)
    }

    /** [song], an uploaded clean edit, as itself: the video's own title, channel and thumbnail. */
    fun asVideo(song: Song): Song? = uploadVideos[song.videoId]?.let { video ->
        song.copy(
            title = video.title,
            artist = video.artist,
            artists = video.artists,
            artistId = video.artistId,
            thumbnailUrl = video.thumbnailUrl,
            albumId = null,
            albumName = null,
            isExplicit = false,
            isVideo = true,
            isVideoOrigin = true,
        )
    }

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
     * Looks up the clean releases of the explicit songs a list is about to
     * show — the first [PREPARE_ROWS] — before it is shown, waiting at most
     * [budgetMs], so their "C" is there from the start instead of popping in.
     * The rest are [request]ed behind it.
     */
    suspend fun prepare(songs: List<Song>, budgetMs: Long) {
        val pending = songs.filter {
            it.isExplicit == true && !found.containsKey(it.videoId) && !isMissing(it)
        }.distinctBy { it.videoId }
        if (pending.isEmpty()) return
        val first = pending.take(PREPARE_ROWS).map { song ->
            background.async { preparing.withPermit { find(song) } }
        }
        pending.drop(PREPARE_ROWS).forEach(::request)
        if (budgetMs > 0) withTimeoutOrNull(budgetMs) { first.awaitAll() }
    }

    private val preparing = Semaphore(PREPARES_AT_ONCE)

    /**
     * The clean release of [song], looked up if it hasn't been yet; null when
     * there is none or the lookup couldn't run. Concurrent callers for the same
     * track share one search.
     */
    suspend fun find(song: Song): Song? {
        if (song.isExplicit != true) return null
        found[song.videoId]?.let { return present(it) }
        if (isMissing(song)) return null
        val mine = CompletableDeferred<Song?>()
        inFlight.putIfAbsent(song.videoId, mine)?.let { return it.await() }
        try {
            val result = lookUp(song)?.let(::present)
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
        var failed = false
        val steps = listOf(
            Triple(name, SearchFilter.SONGS, false),
            Triple("$name clean", SearchFilter.SONGS, false),
            Triple("$name clean", SearchFilter.VIDEOS, true),
        )
        // What the matcher compares against. Rows that don't carry a length —
        // Home's cards, recent searches — get it from the song's own search
        // result, since an upload is never taken without one.
        var target = song
        for ((query, filter, videos) in steps) {
            val candidates = search(query, filter)
            if (candidates == null) {
                TrackLog.d(TAG, "clean version: \"$query\" (${filter.name}) failed", song.videoId)
                failed = true
                continue
            }
            if (!videos) target = withLengthFrom(target, candidates)
            val clean = if (videos) {
                CleanVersionMatcher.pickVideo(target, candidates)?.let { rememberUpload(song, it) }
            } else {
                provenPick(song, CleanVersionMatcher.picks(target, candidates)) { failed = true }
            }
            if (clean != null) {
                TrackLog.d(TAG, "clean version of '${song.title}': ${clean.videoId} via \"$query\" (${filter.name})", song.videoId)
                remember(song.videoId, clean)
                return clean
            }
            TrackLog.d(TAG, "clean version: \"$query\" (${filter.name}) — ${candidates.size} rows, no match", song.videoId)
        }
        // Only "none" when every search came back and none had a clean
        // release. If one failed — the uploads search, say, after both Songs
        // searches came back empty — the answer may be in the one that failed,
        // so nothing is remembered and the next look asks again.
        if (!failed) rememberMissing(song.videoId, System.currentTimeMillis())
        else TrackLog.d(TAG, "clean version: not looked up fully, will ask again", song.videoId)
        return null
    }

    /**
     * The first of [picks] (at most [PROVEN_TRIES]) whose own lyrics prove it
     * clean — see [provenClean]. [onFailed] hears of a check that couldn't be
     * made, so a miss isn't remembered as "none".
     */
    private suspend fun provenPick(song: Song, picks: List<Song>, onFailed: () -> Unit): Song? {
        for (candidate in picks.take(PROVEN_TRIES)) {
            when (provenClean(candidate)) {
                true -> return candidate
                false -> TrackLog.d(TAG, "clean version: ${candidate.videoId} isn't proven clean by its lyrics", song.videoId)
                null -> {
                    TrackLog.d(TAG, "clean version: couldn't read ${candidate.videoId}'s lyrics", song.videoId)
                    onFailed()
                }
            }
        }
        return null
    }

    /**
     * [song] with the length its search results give it, when it had none: its
     * own row if the results have it, else the explicit row of the same title
     * and artist.
     */
    private fun withLengthFrom(song: Song, rows: List<Song>): Song {
        if (song.durationText != null) return song
        val own = rows.firstOrNull { it.videoId == song.videoId && it.durationText != null }
        if (own != null) return song.copy(durationText = own.durationText, artist = own.artist, artists = own.artists)
        val wanted = CleanVersionMatcher.coreTitle(song.title)
        val twin = rows.firstOrNull {
            it.isExplicit == true && !it.isVideo && it.durationText != null &&
                CleanVersionMatcher.coreTitle(it.title) == wanted && TrackMatcher.sharesArtist(song.artist, it.artist)
        } ?: return song
        return song.copy(durationText = twin.durationText)
    }

    /**
     * Keeps both forms of an uploaded clean edit of [song] — the upload as
     * itself, and its clean audio version ([asAudio]) — and hands back the
     * former; [present] decides which goes out.
     */
    private fun rememberUpload(song: Song, upload: Song): Song {
        val video = upload.copy(
            isExplicit = false,
            isVideo = true,
            isVideoOrigin = true,
            setVideoId = null,
            localUri = null,
            localPath = null,
            queueEntryId = null,
        )
        uploadVideos[upload.videoId] = video
        uploadSongs[upload.videoId] = song.asCleanUpload(upload)
        return video
    }

    /**
     * An uploaded clean edit, presented as the song it is an edit of: the
     * upload's id and length, the original's name, artist, album and art.
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
        synchronized(found) {
            found.remove(explicitId)
            found[explicitId] = clean
            while (found.size > MAX_REMEMBERED) {
                val oldest = found.keys.first()
                found.remove(oldest)?.let {
                    releases -= it.videoId
                    uploadVideos.remove(it.videoId)
                    uploadSongs.remove(it.videoId)
                }
            }
        }
        releases += clean.videoId
        missingSince.remove(explicitId)
        publish()
    }

    private fun rememberMissing(explicitId: String, at: Long) {
        missingSince[explicitId] = at
        publish()
    }

    private fun publish() {
        _known.value = synchronized(found) { found.keys.toSet() }
        _missing.update { missingSince.keys.toSet() }
        scheduleSave()
    }

    /**
     * Writes the state out once lookups go quiet for [saveDelayMs], rather than
     * after every one: scrolling a long playlist answers dozens in a row, and
     * each save rewrites the whole list. Losing the last couple of seconds to a
     * crash only costs those songs a second lookup.
     */
    private fun scheduleSave() {
        val save = save ?: return
        if (saveDelayMs <= 0) {
            save(encode())
            return
        }
        synchronized(this) {
            pendingSave?.cancel()
            pendingSave = background.launch {
                delay(saveDelayMs)
                save(encode())
            }
        }
    }

    private var pendingSave: Job? = null

    /** How long [scheduleSave] waits; 0 saves at once, which tests use. */
    internal var saveDelayMs = SAVE_DELAY_MS

    private fun encode(): String {
        val newestMissing = missingSince.entries.sortedByDescending { it.value }.take(MAX_REMEMBERED)
        return buildJsonObject {
            put("v", LOOKUP_VERSION)
            put("found", buildJsonArray {
                synchronized(found) { found.entries.toList() }.takeLast(MAX_REMEMBERED).forEach { (id, clean) ->
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
                        // An upload: the song it stands in for, for [asAudio].
                        uploadSongs[clean.videoId]?.let { song ->
                            put("upload", buildJsonObject {
                                put("title", song.title)
                                put("artist", song.artist)
                                song.thumbnailUrl?.let { put("art", it) }
                                song.artistId?.let { put("artistId", it) }
                                song.albumId?.let { put("albumId", it) }
                                song.albumName?.let { put("album", it) }
                            })
                        }
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
        // Answers from an older lookup aren't believed: "none" may have been
        // asked in a way that couldn't see the clean release (signed in, before
        // 2) or saved after a search that failed partway (before 3), and before
        // 4 an upload was saved already dressed as the song, so it can't be
        // offered as the video it is.
        val sameLookup = (root["v"] as? JsonPrimitive)?.longOrNull == LOOKUP_VERSION.toLong()
        if (sameLookup) (root["found"] as? JsonArray)?.forEach { element ->
            val o = element as? JsonObject ?: return@forEach
            val forId = o.text("for") ?: return@forEach
            val id = o.text("id") ?: return@forEach
            val upload = o["upload"] as? JsonObject
            val clean = Song(
                videoId = id,
                title = o.text("title").orEmpty(),
                artist = o.text("artist").orEmpty(),
                thumbnailUrl = o.text("art"),
                durationText = o.text("length"),
                artistId = o.text("artistId"),
                albumId = o.text("albumId"),
                albumName = o.text("album"),
                isExplicit = false,
                isVideo = upload != null,
                isVideoOrigin = upload != null,
            )
            found[forId] = clean
            releases += id
            if (upload != null) {
                uploadVideos[id] = clean
                uploadSongs[id] = clean.copy(
                    title = upload.text("title").orEmpty(),
                    artist = upload.text("artist").orEmpty(),
                    thumbnailUrl = upload.text("art"),
                    artistId = upload.text("artistId"),
                    albumId = upload.text("albumId"),
                    albumName = upload.text("album"),
                    isVideo = false,
                    isVideoOrigin = false,
                )
            }
        }
        if (sameLookup) (root["missing"] as? JsonArray)?.forEach { element ->
            val o = element as? JsonObject ?: return@forEach
            val forId = o.text("for") ?: return@forEach
            val at = (o["at"] as? JsonPrimitive)?.longOrNull ?: return@forEach
            // Catalogues gain clean releases; "none" is asked again after a while.
            if (now - at < MISSING_FOR_MS) missingSince[forId] = at
        }
        _known.value = synchronized(found) { found.keys.toSet() }
        _missing.value = missingSince.keys.toSet()
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Forgets everything; for tests. */
    internal fun reset() {
        pendingSave?.cancel()
        pendingSave = null
        found.clear()
        missingSince.clear()
        releases.clear()
        uploadVideos.clear()
        uploadSongs.clear()
        inFlight.clear()
        save = null
        _missing.value = emptySet()
        _known.value = emptySet()
    }

    private const val TAG = "BitChord"

    private val SAYS_CLEAN = Regex("""\b(?:clean|edited|radio edit|censored)\b""", RegexOption.IGNORE_CASE)

    /** Bumped when the lookup changes in a way that could now find what it missed. */
    private const val LOOKUP_VERSION = 5

    /** Unbadged rows checked for one clean release before giving up on Songs. */
    private const val PROVEN_TRIES = 3

    /** Searches a batch of suggestions may have running together. */
    private const val LOOKUPS_AT_ONCE = 4

    /** Lookups for a list about to be shown, run together — see [prepare]. */
    private const val PREPARES_AT_ONCE = 6
    private const val PREPARE_ROWS = 30

    /** Lookups for what lists are showing, run together in the background. */
    private const val BACKGROUND_LOOKUPS = 2

    /**
     * Most answers of each kind kept, the oldest let go first. A found entry is
     * a couple of hundred bytes, so the whole store tops out around half a
     * megabyte.
     */
    private const val MAX_REMEMBERED = 2_000

    private const val SAVE_DELAY_MS = 2_000L

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

/**
 * With "Play clean versions instead" on, an album or single listed in both its
 * editions shows only the clean one: the explicit card is left out wherever a
 * card of the same release without the badge — same title once edition tags
 * are set aside, same billing line — is in the same list. One listed alone
 * stays; its tracks still play clean.
 */
object CleanEditions {
    fun <T> keepClean(
        items: List<T>,
        title: (T) -> String,
        subtitle: (T) -> String,
        explicit: (T) -> Boolean,
        collection: (T) -> Boolean = { true },
    ): List<T> {
        val clean = items.filter { collection(it) && !explicit(it) }.mapTo(HashSet()) { key(title(it), subtitle(it)) }
        if (clean.isEmpty()) return items
        return items.filterNot { collection(it) && explicit(it) && key(title(it), subtitle(it)) in clean }
    }

    fun shelves(shelves: List<HomeShelf>): List<HomeShelf> = shelves.map { shelf ->
        val kept = cards(shelf.items)
        if (kept.size == shelf.items.size) shelf else shelf.copy(items = kept)
    }

    fun cards(items: List<ShelfItem>): List<ShelfItem> = keepClean(
        items,
        title = { it.title },
        subtitle = { it.subtitle },
        explicit = { it.isExplicit == true },
        collection = { it.browseId != null && it.videoId == null },
    )

    fun searchRows(rows: List<SearchResult>): List<SearchResult> = keepClean(
        rows,
        title = { (it as? SearchResult.Browse)?.item?.title.orEmpty() },
        subtitle = { (it as? SearchResult.Browse)?.item?.subtitle.orEmpty() },
        explicit = { (it as? SearchResult.Browse)?.item?.isExplicit == true },
        collection = { it is SearchResult.Browse },
    )

    private fun key(title: String, subtitle: String): String =
        CleanVersionMatcher.coreTitle(title) + "|" + subtitle.trim().lowercase(java.util.Locale.ROOT)
}
