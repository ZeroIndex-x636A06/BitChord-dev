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
 * Counted, not matched once: a single "damn" or a band called the Dead
 * Kennedys shouldn't block a song. Strong words ([STRONG]: the f-word, the
 * s-word, slurs and the like) count [STRONG_POINTS] each, milder ones
 * ([MILD]: "ass", "ho", …) one each, and a song is explicit at [THRESHOLD]
 * points — two strong words, or one and a couple of mild ones, or four mild
 * ones. "Hell", "damn" and "God" never count: whole clean catalogues use them.
 *
 * Lyrics sites usually print the uncensored words even for an edited release,
 * so this can flag a clean edit. That is why the result is kept per video id
 * rather than per title, and why a song the explicit filter already found to
 * be a clean release is never put through it.
 */
object ExplicitLyrics {

    /** Points at which a song's lyrics make it explicit. */
    const val THRESHOLD = 4

    /** Points for each strong word. */
    const val STRONG_POINTS = 2

    fun score(lyrics: String): Int {
        // Santa's "ho ho ho" isn't the word being counted.
        val text = lyrics.lowercase(Locale.ROOT).replace(LAUGH, " ")
        val strong = STRONG.sumOf { it.findAll(text).count() } + CENSORED.sumOf { it.findAll(text).count() }
        val mild = MILD.sumOf { it.findAll(text).count() }
        return strong * STRONG_POINTS + mild
    }

    fun isExplicit(lines: List<String>): Boolean = score(lines.joinToString("\n")) >= THRESHOLD

    private val LAUGH = Regex("""(?<![\p{L}\p{N}])ho(?:[\s,!.-]+ho)+(?![\p{L}\p{N}])""")

    private fun words(vararg patterns: String) = patterns.map { Regex("""(?<![\p{L}\p{N}])(?:$it)(?![\p{L}\p{N}])""") }

    private val STRONG = words(
        """(?:mother|mutha|motha)?f+u+c*k+\w*""",
        """f+u+k+(?:in|ing|ed|er|ers)?""",
        """(?:bull|horse)?shit(?:s|ty|tier|tiest|tin|ting|ted|head|heads|hole|holes)?""",
        """bitch\w*""",
        """cunts?""",
        """cock(?:sucker)?s?""",
        """puss(?:y|ies)""",
        """nigg(?:a|er|ah|az|as|ers)""",
        """whores?""",
        """sluts?""",
        """fag(?:got)?s?""",
        """ass ?holes?""",
    )

    /** Starred-out spellings some lyrics sites print instead. */
    private val CENSORED = listOf(
        Regex("""(?<![\p{L}\p{N}])f[*#@]+c?k\w*"""),
        Regex("""(?<![\p{L}\p{N}])sh?[*#@]+t\w*"""),
        Regex("""(?<![\p{L}\p{N}])b[*#@]+t?ch\w*"""),
        Regex("""(?<![\p{L}\p{N}])n[*#@]+gg?(?:a|er)s?"""),
    )

    private val MILD = words(
        """ass(?:es)?""",
        """dicks?""",
        """hoe?s?""",
        """bastards?""",
        """piss(?:ed)?""",
        """tits""",
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
        song.isExplicit != true && song.localUri == null && song.localPath == null && !store.knows(song.videoId)

    /** The verdict for [song] — read now if it hasn't been — or null when it couldn't be read. */
    suspend fun check(song: Song): Boolean? {
        if (song.isExplicit == true) return true
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
