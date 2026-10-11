package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Lyrics sites usually print the uncensored words even for an edited release,
 * so this can flag a clean edit. That is why the result is kept per video id
 * rather than per title, and why a song the explicit filter already found to
 * be a clean release is never put through it.
 */
object ExplicitLyrics {

    /** Points at which a song's lyrics make it explicit. */
    const val THRESHOLD = 4

    /** Points for a word that makes a song explicit by itself. */
    const val SEVERE_POINTS = THRESHOLD

    /** Points for each strong word. */
    const val STRONG_POINTS = 2

    fun score(lyrics: String): Int = matches(lyrics).entries.sumOf { (term, count) -> term.points * count }

    fun isExplicit(lines: List<String>): Boolean = score(lines.joinToString("\n")) >= THRESHOLD

    /** One counted word: how it's listed in a report, what it's worth, and how to find it. */
    class Term(val name: String, val points: Int, val pattern: Regex) {
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

    private val LAUGH = Regex("""(?<![\p{L}\p{N}])ho(?:[\s,!.-]+ho)+(?![\p{L}\p{N}])""")

    private fun term(name: String, points: Int, pattern: String) =
        Term(name, points, Regex("""(?<![\p{L}\p{N}])(?:$pattern)(?![\p{L}\p{N}])"""))

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
        // Starred-out spellings some lyrics sites print instead.
        Term("f-word (starred)", SEVERE_POINTS, Regex("""(?<![\p{L}\p{N}])f[*#@]+c?k\w*""")),
        Term("s-word (starred)", STRONG_POINTS, Regex("""(?<![\p{L}\p{N}])sh?[*#@]+t\w*""")),
        Term("b-word (starred)", STRONG_POINTS, Regex("""(?<![\p{L}\p{N}])b[*#@]+t?ch\w*""")),
        Term("n-word (starred)", SEVERE_POINTS, Regex("""(?<![\p{L}\p{N}])n[*#@]+gg?(?:a|er)s?""")),
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
 * second lookup.
 */
class ExplicitLyricsStore(private val save: (String) -> Unit) {

    private val verdicts = LinkedHashMap<String, Boolean>()
    private val _flagged = MutableStateFlow<Set<String>>(emptySet())

    /** Video ids whose lyrics made them explicit. */
    val flagged: StateFlow<Set<String>> = _flagged.asStateFlow()

    @Synchronized
    fun load(encoded: String?) {
        verdicts.clear()
        encoded.orEmpty().lineSequence().forEach { line ->
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
        save(verdicts.entries.joinToString("\n") { (id, bad) -> (if (bad) "1:" else "0:") + id })
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
    }
}

/**
 * Reads a song's lyrics and records whether they make it explicit.
 *
 * [fetch] is each app's own lyrics lookup — its enabled sources, in its own
 * order — returning the lines, or null when it found none. A song with no
 * lyrics anywhere (an instrumental, or one no source carries) is recorded as
 * clean so it isn't looked up on every play; a lookup that *failed* isn't
 * recorded at all.
 */
class ExplicitLyricsChecker(
    private val store: ExplicitLyricsStore,
    private val fetch: suspend (Song) -> List<String>?,
) {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Boolean?>>()

    /** Whether [song] still has to be read before the filter can judge it. */
    fun needsCheck(song: Song): Boolean =
        song.isExplicit != true && song.localUri == null && song.localPath == null &&
            !CleanVersions.isCleanRelease(song) && !store.knows(song.videoId)

    /** The verdict for [song] — read now if it hasn't been — or null when it couldn't be read. */
    suspend fun check(song: Song): Boolean? {
        if (song.isExplicit == true) return true
        if (CleanVersions.isCleanRelease(song)) return false
        if (store.knows(song.videoId)) return song.videoId in store.flagged.value
        val mine = CompletableDeferred<Boolean?>()
        inFlight.putIfAbsent(song.videoId, mine)?.let { return it.await() }
        try {
            val lines = fetch(song)
            val explicit = lines != null && ExplicitLyrics.isExplicit(lines)
            store.record(song.videoId, explicit)
            mine.complete(explicit)
            return explicit
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
}
