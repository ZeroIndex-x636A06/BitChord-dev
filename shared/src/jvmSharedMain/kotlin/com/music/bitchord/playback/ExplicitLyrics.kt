package com.music.bitchord.playback

import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.YouTubeMusicLyrics
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Judges a song explicit from its words, for tracks YouTube Music never badged.
 *
 * The catalogue's "E" misses songs that plainly earn one — *Jump Around* plays
 * through the filter with several swear words in it — so "Check lyrics for
 * explicit content" reads the lyrics the app already knows how to fetch and
 * counts what's in them.
 *
 * Weighed, not matched once. The words that earn a record its label by
 * themselves — the f-word and slurs — count [SEVERE_POINTS], enough on their
 * own, since that is how explicit labels are given. Other strong words (the
 * s-word, the b-word, …) count [STRONG_POINTS], so it takes two, or one and a
 * couple of milder ones; mild words ("ass", "ho" used as a noun, …) count one.
 * A song is explicit at [THRESHOLD] points. "Hell", "damn" and "God" never
 * count: whole clean catalogues use them. Nor does "ho" on its own — Santa's
 * "ho ho ho", "Heigh-Ho", "Yo ho", *Ho Hey*.
 *
 * Tuned against real lyrics from LRCLIB for about a hundred songs. None of
 * the clean ones reach the threshold — Christmas standards, *Ho Hey*,
 * *Heigh-Ho*, *Highway to Hell*, *Uptown Funk* (one b-word), *Baby Got Back*.
 * The two that catch people out do contain the words: *Bad Romance* says the
 * b-word five times, and *Money for Nothing* has a slur. What it can't catch
 * is a song explicit for what it's *about* (*Pumped Up Kicks*, *Low*) rather
 * than the words it uses.
 *
 * Which lyrics are read, and in what order, is [ExplicitLyricsChecker]'s
 * business; results are kept per video id, since a clean edit and its
 * original share a title.
 */
object ExplicitLyrics {

    /** Points at which a song's lyrics make it explicit. */
    const val THRESHOLD = 4

    /** Points for a word that makes a song explicit by itself. */
    const val SEVERE_POINTS = THRESHOLD

    /** Points for each strong word. */
    const val STRONG_POINTS = 2

    /**
     * [countMasked] off leaves masked spellings ("f***") out — for a
     * recording's own lyrics, where a mask means the word was taken out of
     * the recording too.
     */
    fun score(lyrics: String, countMasked: Boolean = true): Int =
        matches(lyrics).entries.sumOf { (term, count) -> if (term.masked && !countMasked) 0 else term.points * count }

    fun isExplicit(lines: List<String>, countMasked: Boolean = true): Boolean =
        score(lines.joinToString("\n"), countMasked) >= THRESHOLD

    /** One counted word: how it's listed in a report, what it's worth, and how to find it. */
    class Term(val name: String, val points: Int, val pattern: Regex, val masked: Boolean = false) {
        override fun toString() = name
    }

    /**
     * Which counted words [lyrics] contains, and how often. [score] is built on
     * this; it also says *why* a song was flagged, which is what the tests check.
     */
    fun matches(lyrics: String): Map<Term, Int> {
        // A laugh or a shanty isn't the word being counted: "ho ho ho", "ho, ho"
        // and the like are taken out before anything is looked for.
        val text = lyrics.lowercase(Locale.ROOT).replace(LAUGH, " ")
        return TERMS.associateWith { it.pattern.findAll(text).count() }.filterValues { it > 0 }
    }

    /** A mask straight after a word's first letter, and whatever is left of the word. */
    private const val MASKED = """[*#@]+[a-z*#@]*"""

    private val LAUGH = Regex("""(?<![\p{L}\p{N}])ho(?:[\s,!.-]+ho)+(?![\p{L}\p{N}])""")

    private fun term(name: String, points: Int, pattern: String) =
        Term(name, points, Regex("""(?<![\p{L}\p{N}])(?:$pattern)(?![\p{L}\p{N}])"""), masked = "masked" in name)

    private fun severe(name: String, pattern: String) = term(name, SEVERE_POINTS, pattern)
    private fun strong(name: String, pattern: String) = term(name, STRONG_POINTS, pattern)
    private fun mild(name: String, pattern: String) = term(name, 1, pattern)

    private val TERMS = listOf(
        severe("f-word", """(?:mother|mutha|motha)?f+u+c*k+\w*|f+u+k+(?:in|ing|ed|er|ers)?"""),
        strong("s-word", """(?:bull|horse)?shit(?:s|ty|tier|tiest|tin|ting|ted|head|heads|hole|holes)?"""),
        strong("b-word", """bitch\w*"""),
        severe("c-word", """cunts?"""),
        severe("cocksucker", """cocksuckers?"""),
        // Not "pussycat", "pussy cat" or "pussy willow".
        strong("p-word", """puss(?:y|ies)(?! ?cats?| willows?)"""),
        severe("n-word", """nigg(?:a|er|ah|az|as|ers)"""),
        strong("whore", """whores?"""),
        strong("slut", """sluts?"""),
        severe("f-slur", """fag(?:got)?s?"""),
        strong("asshole", """ass ?holes?"""),
        // Masked spellings — "f**k", "f***", "sh*t", "b***h", "n****" — which
        // some sources print in place of the word. Known by the first letter
        // and a mask straight after it; letters whose masked words don't count
        // ("d**n", "h*ll") are left out. See [score] for where they don't count.
        severe("f-word (masked)", """f$MASKED"""),
        severe("n-word (masked)", """n$MASKED"""),
        severe("c-word (masked)", """c[*#@]+[*#@n]*ts?"""),
        strong("s-word (masked)", """sh?$MASKED"""),
        strong("b-word (masked)", """b$MASKED"""),
        strong("p-word (masked)", """p$MASKED"""),
        mild("ass (masked)", """a[*#@]{2,}[a-z*#@]*"""),
        mild("ass", """ass(?:es)?"""),
        mild("dick", """dicks?"""),
        // A rooster's "cock-a-doodle" and "cock of the walk" are why this one is mild.
        mild("cock", """cocks?"""),
        // Only as a noun. On its own "ho" is a laugh, a shanty ("yo ho"),
        // a call ("land ho", "heigh-ho", "Ho Hey") — never counted.
        mild("ho", """(?:a|the|that|this|your|my|his|her|some|dumb|stupid|lil|little|old|broke) hoe?s?|hoes|hos|ho's"""),
        mild("bastard", """bastards?"""),
        mild("piss", """piss(?:ed|es|ing)?"""),
        mild("tits", """tits|titties"""),
    )
}

/**
 * What lyric checks have found, by video id, kept across launches so a song is
 * read once. Each app gives it a way to store its encoded form; [load] reads it
 * back.
 *
 * Stored as one line per song, `1:<id>` or `0:<id>`, oldest first, and trimmed
 * to the newest [MAX_ENTRIES] — a cache, so losing the oldest only costs a
 * second lookup. The first line names the [VERSION] of the check that wrote
 * them; verdicts from another version are dropped, so songs the old check
 * got wrong are read again.
 */
class ExplicitLyricsStore(private val save: (String) -> Unit) {

    private val verdicts = LinkedHashMap<String, Boolean>()
    private val _flagged = MutableStateFlow<Set<String>>(emptySet())

    /** Video ids whose lyrics made them explicit. */
    val flagged: StateFlow<Set<String>> = _flagged.asStateFlow()

    @Synchronized
    fun load(encoded: String?) {
        verdicts.clear()
        val lines = encoded.orEmpty().lines()
        if (lines.firstOrNull() != HEADER) {
            publish()
            return
        }
        lines.drop(1).forEach { line ->
            val id = line.substringAfter(':', "")
            if (id.isNotBlank()) verdicts[id] = line.startsWith("1:")
        }
        publish()
    }

    @Synchronized
    fun knows(videoId: String): Boolean = videoId in verdicts

    @Synchronized
    fun record(videoId: String, explicit: Boolean) {
        verdicts.remove(videoId)
        verdicts[videoId] = explicit
        while (verdicts.size > MAX_ENTRIES) verdicts.remove(verdicts.keys.first())
        publish()
        save(HEADER + "\n" + verdicts.entries.joinToString("\n") { (id, bad) -> (if (bad) "1:" else "0:") + id })
    }

    /** Forgets every verdict, so songs are read again. */
    @Synchronized
    fun clear() {
        verdicts.clear()
        publish()
        save("")
    }

    private fun publish() {
        _flagged.value = verdicts.filterValues { it }.keys.toSet()
    }

    companion object {
        const val MAX_ENTRIES = 5_000

        /** Bumped when the check changes enough that its old verdicts shouldn't stand. */
        const val VERSION = 3
        private const val HEADER = "v$VERSION"
    }
}

/**
 * Where a lyric check gets its words. [StandardExplicitLyrics] is the one both
 * apps use; tests hand in their own.
 */
interface ExplicitLyricsSource {
    /**
     * YouTube Music's own lyrics for this very video, or null when it has none.
     * Throws when YouTube Music couldn't be asked at all — offline is not "no
     * lyrics".
     */
    suspend fun exact(song: Song): List<String>?

    /** Every other lyrics source's words for the song, each as that source answers. */
    fun others(song: Song): Flow<List<String>>

    /** The words of other recordings with the song's title. */
    suspend fun sameTitle(song: Song): List<List<String>>
}

/**
 * The lyric check's words, from the same places the lyrics panel reads:
 * YouTube Music for [exact] — the service the app already plays from, so it is
 * asked whatever the lyrics settings say — and the lyrics sources switched on
 * in Settings for the rest. A source switched off is never contacted.
 */
class StandardExplicitLyrics(private val sources: () -> Set<LyricsSource>) : ExplicitLyricsSource {

    override suspend fun exact(song: Song): List<String>? =
        YouTubeMusicLyrics.lyricsOrThrow(song.videoId)
            ?.map { it.text.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    override fun others(song: Song): Flow<List<String>> = LyricsRepository.everySource(
        videoId = song.videoId,
        title = song.title,
        artist = song.artist,
        durationMs = song.durationMillis(),
        album = song.albumName,
        // Already asked, for this very video, by [exact].
        sources = sources() - LyricsSource.YOUTUBE_MUSIC,
        perSourceMs = PER_SOURCE_MS,
        // With the rest, not after them: a tap is waiting.
        geniusLast = false,
    )

    override suspend fun sameTitle(song: Song): List<List<String>> =
        if (LyricsSource.LRCLIB in sources()) LrcLib.sameTitle(song.title) else emptyList()

    private companion object {
        /**
         * How long one source may take. A source that hasn't answered by then
         * rarely has the song — in the live measurements most answers came
         * within two seconds — and a tap is waiting on the verdict.
         */
        const val PER_SOURCE_MS = 3_000L
    }
}

/**
 * Reads a song's lyrics and records whether they make it explicit.
 *
 * Which words count, in order:
 *
 * 1. **YouTube Music's lyrics for this very video**, when it has them. They
 *    are the only words tied to the recording rather than to a name, so they
 *    settle it: a clean edit's are clean even when every lyrics site prints
 *    the original's (the clean *HUMBLE.* reads 36 points on Apple's lyrics and
 *    0 on its own), and an unmarked explicit upload's are not.
 * 2. **Every other source**, all asked at once, and explicit if *any* of them
 *    is — sources print the same song censored, masked or whole, and the one
 *    that hides the words must not outvote the one that shows them. Otherwise
 *    it is clean once one source has words and none of them is explicit.
 *    These sources match by name, and a loose match can hand back another
 *    song's words — "Forget You" tributes answered with "F*** You" — so an
 *    explicit answer here is overruled when every song sharing the title
 *    (step 3's namesakes, at least [SAME_TITLE_MIN] with words) is clean.
 * 3. **Nothing anywhere** — usually a remake or cover nobody has transcribed.
 *    Such a song sings what the songs sharing its title sing, so it is explicit
 *    when at least [SAME_TITLE_MIN] of them have words and most of those are
 *    explicit. Asked alongside step 2, so it's in hand when step 2 comes up
 *    empty.
 *
 * Without words of its own, a song whose title says nobody sings on it
 * ("Instrumental", "Karaoke", "8 Bit") is clean: whatever words other sources
 * have are the original's, matched by name. So is one from an act that only
 * makes instrumental or child-safe covers (Rockabye Baby!'s lullabies of rap
 * hits, Kidz Bop).
 *
 * A check that couldn't finish — offline, or out of [BUDGET_MS] before any
 * source answered — isn't recorded, so the song is read again next time.
 *
 * [precheck] does step 1 alone, for songs a list is showing: it is one quick
 * request to YouTube Music, settles most songs, and means a tap on them has
 * nothing left to wait for.
 */
class ExplicitLyricsChecker(
    private val store: ExplicitLyricsStore,
    private val lyrics: ExplicitLyricsSource,
    /** Where each verdict and how it was reached is written — the app's track log. */
    private val log: (videoId: String, message: String) -> Unit = { _, _ -> },
) {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Boolean?>>()
    private val ownInFlight = ConcurrentHashMap<String, CompletableDeferred<List<String>?>>()

    /** Songs YouTube Music has no lyrics for, so a tap after a [precheck] doesn't ask again. */
    private val noOwnWords: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** When a check last came back without a verdict, by video id — see [needsCheck]. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /**
     * Whether [song] still has to be read before the filter can judge it. Not
     * for [RETRY_AFTER_MS] after a check that couldn't be finished (offline,
     * out of time): playback waits on this, and waiting a second time on the
     * same failure would only double the wait.
     */
    fun needsCheck(song: Song): Boolean =
        song.isExplicit != true && song.localUri == null && song.localPath == null &&
            !store.knows(song.videoId) &&
            (failedAt[song.videoId]?.let { now() - it >= RETRY_AFTER_MS } ?: true)

    /** The verdict for [song] — read now if it hasn't been — or null when it couldn't be read. */
    suspend fun check(song: Song): Boolean? {
        if (song.isExplicit == true) return true
        if (store.knows(song.videoId)) return song.videoId in store.flagged.value
        val mine = CompletableDeferred<Boolean?>()
        inFlight.putIfAbsent(song.videoId, mine)?.let { return it.await() }
        val started = now()
        val how = StringBuilder()
        try {
            val explicit = withTimeoutOrNull(BUDGET_MS) { judge(song, how) }
            if (explicit != null) store.record(song.videoId, explicit) else failedAt[song.videoId] = now()
            log(
                song.videoId,
                "explicit lyrics: '${song.title}' — ${when (explicit) { true -> "explicit"; false -> "clean"; null -> "unread" }}" +
                    " ($how; ${now() - started} ms)",
            )
            mine.complete(explicit)
            return explicit
        } catch (e: CancellationException) {
            mine.complete(null)
            throw e
        } catch (e: Exception) {
            failedAt[song.videoId] = now()
            log(song.videoId, "explicit lyrics: '${song.title}' — unread (${e.message ?: e::class.simpleName})")
            mine.complete(null)
            return null
        } finally {
            inFlight.remove(song.videoId, mine)
        }
    }

    /**
     * Step 1 alone, for a song on screen: when YouTube Music has lyrics for
     * this very video they settle it, and the verdict is recorded so a tap on
     * it plays at once. Otherwise nothing is recorded; a tap does the rest.
     */
    suspend fun precheck(song: Song) {
        if (!needsCheck(song)) return
        val own = runCatching { ownWords(song) }.getOrNull()
        if (!own.isNullOrEmpty() && !store.knows(song.videoId)) {
            store.record(song.videoId, ExplicitLyrics.isExplicit(own, countMasked = false))
        }
    }

    /** [ExplicitLyricsSource.exact], asked once per song however many want it at the same time. */
    private suspend fun ownWords(song: Song): List<String>? {
        if (song.videoId in noOwnWords) return null
        val mine = CompletableDeferred<List<String>?>()
        ownInFlight.putIfAbsent(song.videoId, mine)?.let { return it.await() }
        try {
            val words = lyrics.exact(song)
            if (words.isNullOrEmpty()) {
                if (noOwnWords.size >= MAX_REMEMBERED_MISSES) noOwnWords.clear()
                noOwnWords += song.videoId
            }
            mine.complete(words)
            return words
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            ownInFlight.remove(song.videoId, mine)
        }
    }

    private suspend fun judge(song: Song, how: StringBuilder): Boolean? {
        val exact = ownWords(song)
        // A mask in a recording's own words means the word is muted in it too.
        if (!exact.isNullOrEmpty()) {
            how.append("own lyrics, ").append(ExplicitLyrics.score(exact.joinToString("\n"), countMasked = false)).append(" pts")
            return ExplicitLyrics.isExplicit(exact, countMasked = false)
        }
        how.append("no own lyrics")

        if (UNSUNG.containsMatchIn(song.title.lowercase(Locale.ROOT)) ||
            CLEAN_COVER_ACTS.containsMatchIn(song.artist.lowercase(Locale.ROOT))
        ) {
            how.append(", instrumental or clean cover")
            return false
        }

        return coroutineScope {
            // Wanted only if nothing has words for the song itself, but asked
            // now so it isn't waited on after everything else.
            val namesakes = async { runCatching { lyrics.sameTitle(song) }.getOrDefault(emptyList()) }
            var heard = false
            var finished = false
            val explicitAnswer = withTimeoutOrNull(OTHERS_MS) {
                lyrics.others(song).firstOrNull { lines ->
                    if (lines.isNotEmpty()) heard = true
                    ExplicitLyrics.isExplicit(lines)
                }.also { finished = true }
            }
            if (!finished) how.append(", other sources out of time")
            when {
                explicitAnswer != null -> {
                    val sung = namesakes.await().filter { it.isNotEmpty() }
                    how.append(", another source explicit; namesakes ").append(sung.scores())
                    !(sung.size >= SAME_TITLE_MIN && sung.none(ExplicitLyrics::isExplicit))
                }
                heard -> {
                    how.append(", other sources clean")
                    false
                }
                else -> {
                    // Nothing has words for the song itself — whether every
                    // source said so or ran out of time — so the songs that
                    // share its title speak for it, if there are enough.
                    val sung = namesakes.await().filter { it.isNotEmpty() }
                    how.append(", namesakes ").append(sung.scores())
                    when {
                        sung.size >= SAME_TITLE_MIN -> sung.count(ExplicitLyrics::isExplicit) * 2 > sung.size
                        // Too few to go by, and the sources never finished: no verdict.
                        !finished -> null
                        else -> false
                    }
                }
            }.also { namesakes.cancel() }
        }
    }

    private fun List<List<String>>.scores(): String =
        joinToString(",", "[", "]") { ExplicitLyrics.score(it.joinToString("\n")).toString() }

    companion object {
        /**
         * Longest a whole check may run before it gives up unrecorded. A tap
         * waits for the verdict, so this is also the longest a tap can wait.
         */
        const val BUDGET_MS = 8_000L

        /** Longest the other sources are waited on. */
        const val OTHERS_MS = 6_000L

        /** How long a song whose check couldn't finish is left unread before it is tried again. */
        const val RETRY_AFTER_MS = 5 * 60_000L

        private fun now() = System.currentTimeMillis()

        /** Videos remembered as having no YouTube Music lyrics, before the list starts over. */
        private const val MAX_REMEMBERED_MISSES = 2_000

        /** Namesakes with words needed before they can speak for a song with none. */
        const val SAME_TITLE_MIN = 3

        /** Titles that say there's no singing to read. */
        private val UNSUNG = Regex("""\b(?:instrumental|karaoke|backing track|piano|music box|lullaby|lullabies|8[ -]?bit|orchestral)\b""")

        /** Acts whose covers are instrumental, or sung clean on purpose. */
        private val CLEAN_COVER_ACTS = Regex(
            """\b(?:rockabye baby|kidz bop|vitamin string quartet|twinkle twinkle little rock star|lullaby|lullabies|music box)\b""",
        )
    }
}
