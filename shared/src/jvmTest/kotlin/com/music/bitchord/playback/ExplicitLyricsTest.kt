package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExplicitLyricsTest {

    private fun song(id: String, explicit: Boolean? = null) =
        Song(videoId = id, title = "Song $id", artist = "Artist", thumbnailUrl = null, isExplicit = explicit)

    // ---- Counting ----------------------------------------------------------

    @Test
    fun `two strong words make a song explicit`() {
        assertTrue(ExplicitLyrics.isExplicit(listOf("Oh shit", "you son of a bitch")))
    }

    @Test
    fun `a single f-word is enough`() {
        // In da Club, Hot in Herre and Sabotage each have just the one.
        assertTrue(ExplicitLyrics.isExplicit(listOf("Fuck it, let's go", "then nothing else")))
    }

    @Test
    fun `one strong word alone does not`() {
        assertFalse(ExplicitLyrics.isExplicit(listOf("Oh shit, I forgot my keys", "then I went home")))
    }

    @Test
    fun `a strong word and a few mild ones add up`() {
        // The shape of Jump Around: one strong word, an "ass", two "ho"s.
        val lines = listOf("I'll serve your ass", "smacking the ho", "you son of a bitch")
        assertTrue(ExplicitLyrics.isExplicit(lines))
    }

    @Test
    fun `words that clean songs use never count`() {
        val lines = listOf(
            "I'm on the highway to hell", "damn it all", "oh my God",
            "pass the glass", "Dickens and Moby", "Ho ho ho, merry Christmas", "ho, ho, ho",
            "a cocktail and a classic", "shiitake mushrooms",
        )
        assertEquals(0, ExplicitLyrics.score(lines.joinToString("\n")))
    }

    @Test
    fun `ho on its own is never counted`() {
        val lines = listOf(
            "Up on the housetop, ho, ho, ho! Who wouldn't go?",
            "Heigh-ho, heigh-ho, it's home from work we go",
            "Yo ho, yo ho, a pirate's life for me",
            "Ho! Hey! Ho! Hey!", "Land ho!", "Westward ho", "Tally-ho",
            "Cock-a-doodle-doo", "What's new, pussycat?", "pussy willow",
        )
        // The rooster is the one mild word left in there.
        assertEquals(1, ExplicitLyrics.score(lines.joinToString("\n")))
        assertFalse(ExplicitLyrics.isExplicit(lines))
    }

    @Test
    fun `ho as a noun still counts`() {
        val found = ExplicitLyrics.matches("smacking the ho\nall these hoes\nyour ho").mapKeys { it.key.name }
        assertEquals(mapOf("ho" to 3), found)
    }

    @Test
    fun `starred-out spellings still count`() {
        assertTrue(ExplicitLyrics.isExplicit(listOf("f**k it", "sh*t happens")))
    }

    @Test
    fun `masked words count by their first letter`() {
        // What edited releases and some sources print instead.
        assertEquals(ExplicitLyrics.SEVERE_POINTS, ExplicitLyrics.score("F*** you"))
        assertEquals(ExplicitLyrics.SEVERE_POINTS, ExplicitLyrics.score("n***a please"))
        assertEquals(ExplicitLyrics.STRONG_POINTS, ExplicitLyrics.score("you b***h"))
        // Masked words that don't count unmasked don't count masked either.
        assertEquals(0, ExplicitLyrics.score("d**n it all to h*ll"))
    }

    @Test
    fun `case and inflection don't hide a word`() {
        assertEquals(2 * ExplicitLyrics.SEVERE_POINTS, ExplicitLyrics.score("FUCKING Motherfucker"))
    }

    // ---- Memory ------------------------------------------------------------

    @Test
    fun `verdicts survive a reload and only explicit ones are flagged`() {
        var saved = ""
        val store = ExplicitLyricsStore { saved = it }
        store.record("bad", true)
        store.record("fine", false)
        val reloaded = ExplicitLyricsStore { }
        reloaded.load(saved)
        assertEquals(setOf("bad"), reloaded.flagged.value)
        assertTrue(reloaded.knows("fine"))
    }

    @Test
    fun `verdicts from an older check are read again`() {
        val store = ExplicitLyricsStore { }
        store.load("1:old\n0:also-old")
        assertFalse(store.knows("old"))
        assertTrue(store.flagged.value.isEmpty())
    }

    @Test
    fun `the oldest verdicts go first when the store is full`() {
        val store = ExplicitLyricsStore { }
        repeat(ExplicitLyricsStore.MAX_ENTRIES + 1) { store.record("id$it", it == 0) }
        assertFalse(store.knows("id0"))
        assertTrue(store.knows("id${ExplicitLyricsStore.MAX_ENTRIES}"))
        assertTrue(store.flagged.value.isEmpty())
    }

    // ---- Checking ----------------------------------------------------------

    /** Words from each place a check reads, as the tests want them. */
    private class Words(
        val exact: List<String>? = null,
        val others: List<List<String>> = emptyList(),
        val sameTitle: List<List<String>> = emptyList(),
        val offline: Boolean = false,
        val cleanEdit: Boolean = false,
    ) : ExplicitLyricsSource {
        var editAsked = 0
        override suspend fun isCleanEdit(song: Song): Boolean {
            editAsked++
            return cleanEdit
        }
        var asked = 0
        var namesakesAsked = 0
        override suspend fun exact(song: Song): List<String>? {
            asked++
            if (offline) throw java.io.IOException("offline")
            return exact
        }
        override fun others(song: Song): Flow<List<String>> = others.asFlow()
        override suspend fun sameTitle(song: Song): List<List<String>> {
            namesakesAsked++
            return sameTitle
        }
    }

    private val swearing = listOf("fuck it", "you son of a bitch")
    private val clean = listOf("la la la", "I love you")

    private fun checker(words: Words, store: ExplicitLyricsStore = ExplicitLyricsStore { }) =
        ExplicitLyricsChecker(store, words)

    @Test
    fun `a song is read once`() = runBlocking {
        val words = Words(exact = swearing)
        val store = ExplicitLyricsStore { }
        val checker = checker(words, store)
        assertTrue(checker.needsCheck(song("a")))
        assertEquals(true, checker.check(song("a")))
        assertEquals(true, checker.check(song("a")))
        assertEquals(1, words.asked)
        assertFalse(checker.needsCheck(song("a")))
        assertEquals(setOf("a"), store.flagged.value)
    }

    @Test
    fun `a precheck settles songs YouTube Music has lyrics for, and nothing else`() = runBlocking {
        val store = ExplicitLyricsStore { }
        val withWords = Words(exact = swearing)
        checker(withWords, store).precheck(song("own"))
        assertTrue(store.knows("own"))
        assertEquals(setOf("own"), store.flagged.value)

        // No lyrics of its own: nothing recorded, and the tap's full check
        // doesn't ask YouTube Music a second time.
        val without = Words(others = listOf(clean))
        val checker = checker(without, store)
        checker.precheck(song("none"))
        assertFalse(store.knows("none"))
        assertEquals(false, checker.check(song("none")))
        assertEquals(1, without.asked)
    }

    @Test
    fun `this video's own lyrics settle it`() = runBlocking {
        // A clean edit: YouTube Music's words for it are clean, while lyrics
        // sites print the original's (the clean HUMBLE. reads 36 on Apple's).
        assertEquals(false, checker(Words(exact = clean, others = listOf(swearing))).check(song("edit")))
        assertEquals(true, checker(Words(exact = swearing, others = listOf(clean))).check(song("upload")))
        // Masked in its own words: muted in the recording, so clean.
        assertEquals(false, checker(Words(exact = listOf("f*** it", "you b***h"))).check(song("muted")))
    }

    @Test
    fun `any source showing the words is enough`() = runBlocking {
        // One source prints it censored, another whole.
        val words = Words(others = listOf(listOf("you son of a b----", "la la"), swearing))
        assertEquals(true, checker(words).check(song("x")))
        assertEquals(false, checker(Words(others = listOf(clean, clean))).check(song("y")))
    }

    @Test
    fun `explicit words found by name lose to clean namesakes`() = runBlocking {
        // A "Forget You" tribute: one source matched it to "F*** You", while
        // every song actually called "Forget You" is clean.
        val tribute = Words(others = listOf(swearing), sameTitle = List(6) { clean })
        assertEquals(false, checker(tribute).check(song("tribute")))
        // A cover whose namesakes agree stays blocked (Vanilla Ice's Jump Around).
        val cover = Words(others = listOf(swearing), sameTitle = listOf(swearing, swearing, clean, swearing))
        assertEquals(true, checker(cover).check(song("cover")))
        // Too few namesakes to overrule anything.
        assertEquals(true, checker(Words(others = listOf(swearing), sameTitle = listOf(clean))).check(song("rare")))
    }

    @Test
    fun `a song nobody has words for goes by the songs that share its title`() = runBlocking {
        // A remake of Jump Around: no words anywhere, while five of the six
        // songs called "Jump Around" are explicit.
        val namesakes = Words(sameTitle = listOf(swearing, swearing, clean, swearing, swearing, swearing))
        assertEquals(true, checker(namesakes).check(song("remake")))
        // Mostly clean namesakes, or too few to go by: clean.
        assertEquals(false, checker(Words(sameTitle = listOf(swearing, clean, clean))).check(song("a")))
        assertEquals(false, checker(Words(sameTitle = listOf(swearing, swearing))).check(song("b")))
    }

    @Test
    fun `sources running out of time still leave the namesakes to decide`() = runBlocking {
        // The remake of Jump Around: no source has its words, and the slowest
        // of them outlast the wait. Its namesakes are in hand by then.
        val slow = object : ExplicitLyricsSource {
            override suspend fun exact(song: Song): List<String>? = null
            override fun others(song: Song): Flow<List<String>> = flow {
                kotlinx.coroutines.delay(ExplicitLyricsChecker.OTHERS_MS + 1_000)
            }
            override suspend fun sameTitle(song: Song) = listOf(swearing, swearing, clean, swearing)
        }
        assertEquals(true, ExplicitLyricsChecker(ExplicitLyricsStore { }, slow).check(song("remake")))
    }

    @Test
    fun `an instrumental is clean whatever words its name finds`() = runBlocking {
        // Sources match the original's words by name: "Bodak Yellow (8 Bit
        // Version)" turned up the original's. Nobody sings on these.
        val words = Words(others = listOf(swearing), sameTitle = List(5) { swearing })
        for (title in listOf("Lose Yourself (Instrumental Karaoke Version)", "Bodak Yellow (8 Bit Version)")) {
            assertEquals(false, checker(words).check(song(title).copy(title = title)), title)
        }
        assertEquals(0, words.namesakesAsked)
        // Nor a lullaby act's cover of a rap hit.
        val lullaby = song("rb").copy(title = "The Real Slim Shady", artist = "Rockabye Baby!")
        assertEquals(false, checker(words).check(lullaby))
        // Unless the video's own lyrics say there is singing after all.
        val sung = Words(exact = swearing)
        assertEquals(true, checker(sung).check(song("p").copy(title = "Sicko Mode (Piano Acoustic)")))
    }

    @Test
    fun `the lyrics settings are respected`() = runBlocking {
        // YouTube Music's lyrics switched off: never asked; the rest decide.
        val noYtm = object : ExplicitLyricsSource by Words(exact = clean, others = listOf(swearing)) {
            override fun hasExact() = false
        }
        assertEquals(true, ExplicitLyricsChecker(ExplicitLyricsStore { }, noYtm).check(song("a")))
        // Lyrics switched off: nothing read, nothing recorded, nothing waited on.
        val store = ExplicitLyricsStore { }
        val off = object : ExplicitLyricsSource by Words(exact = swearing) {
            override fun canRead() = false
        }
        val checker = ExplicitLyricsChecker(store, off)
        assertFalse(checker.needsCheck(song("b")))
        assertNull(checker.check(song("b")))
        assertFalse(store.knows("b"))
    }

    @Test
    fun `being offline is not remembered`() = runBlocking {
        val store = ExplicitLyricsStore { }
        val checker = checker(Words(offline = true), store)
        assertNull(checker.check(song("offline")))
        assertFalse(store.knows("offline"))
        // Nor waited on again straight away.
        assertFalse(checker.needsCheck(song("offline")))
    }

    @Test
    fun `songs the catalogue already marks are never read`() = runBlocking {
        val words = Words(exact = clean)
        val checker = checker(words)
        assertFalse(checker.needsCheck(song("e", explicit = true)))
        assertEquals(true, checker.check(song("e", explicit = true)))
        assertEquals(0, words.asked)
    }

    // ---- Clean edits -------------------------------------------------------

    @Test
    fun `a clean album's track is clean even when its words are not`() = runBlocking {
        // Clean Eminem albums: the lyrics are the uncensored original's.
        val words = Words(exact = swearing, others = listOf(swearing), cleanEdit = true)
        assertEquals(false, checker(words).check(song("arose")))
        // Asked only once the words read explicit.
        val plain = Words(exact = clean, cleanEdit = true)
        assertEquals(false, checker(plain).check(song("plain")))
        assertEquals(0, plain.editAsked)
        // Not a clean edit: the words still decide.
        assertEquals(true, checker(Words(exact = swearing)).check(song("upload")))
    }

    @Test
    fun `a precheck settles a clean album's track as clean`() = runBlocking {
        val store = ExplicitLyricsStore { }
        checker(Words(exact = swearing, cleanEdit = true), store).precheck(song("edit"))
        assertTrue(store.knows("edit"))
        assertFalse("edit" in store.flagged.value)
        checker(Words(exact = swearing), store).precheck(song("raw"))
        assertTrue("raw" in store.flagged.value)
    }

    private fun track(id: String, title: String, explicit: Boolean? = null, length: String? = "4:35", artist: String = "Eminem") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = length, isExplicit = explicit)

    @Test
    fun `a clean edit is the unmarked twin of an explicit release`() {
        val clean = track("c", "Arose")
        val rows = listOf(track("e", "Arose", explicit = true), track("c", "Arose"))
        assertTrue(CleanEdit.isCleanEdit(clean, rows))
        // Masked titles and edition tags still match.
        val masked = track("m", "Just Don't Give a F**", length = "3:47")
        val maskedRows = listOf(
            track("x", "Just Don't Give a Fuck", explicit = true, length = "3:50"),
            track("m", "Just Don't Give a F**", length = "3:47"),
        )
        assertTrue(CleanEdit.isCleanEdit(masked, maskedRows))
        assertTrue(CleanEdit.sameTitle("Not Afraid (Clean)", "Not Afraid"))
    }

    @Test
    fun `no explicit twin, or no clean one, is not a clean edit`() {
        val song = track("j", "Jump Around", artist = "House of Pain")
        // Only unmarked versions: nothing explicit to be the edit of.
        assertFalse(CleanEdit.isCleanEdit(song, listOf(song, track("k", "Jump Around", artist = "House of Pain"))))
        // Only the explicit release, and this one unlisted: maybe a lost badge.
        assertFalse(CleanEdit.isCleanEdit(song, listOf(track("e", "Jump Around", explicit = true, artist = "House of Pain"))))
        // Listed itself with the badge: it is the explicit one.
        assertFalse(
            CleanEdit.isCleanEdit(song, listOf(track("j", "Jump Around", explicit = true, artist = "House of Pain"), track("c", "Jump Around", artist = "House of Pain"))),
        )
        // Another artist's song, a live take, or a different length isn't a twin.
        val stan = track("s", "Stan", length = "6:43")
        assertFalse(CleanEdit.sameSong(stan, track("o", "Stan", artist = "Someone Else", length = "6:43")))
        assertFalse(CleanEdit.sameSong(stan, track("l", "Stan (Live At Wembley 2014)", length = "6:43")))
        assertFalse(CleanEdit.sameSong(stan, track("r", "Stan", length = "3:48")))
    }

    // ---- The filter --------------------------------------------------------

    @Test
    fun `a lyric-flagged song is blocked like a badged one`() {
        val policy = ExplicitPolicy(ExplicitFilterMode.EVERYWHERE, lyricsFlagged = setOf("jump"))
        assertFalse(ExplicitFilter.permits(song("jump"), policy, suggestion = false))
        assertTrue(ExplicitFilter.permits(song("other"), policy, suggestion = false))
        // Allowing it by hand still works.
        val allowed = policy.copy(allowed = ExplicitFilter.allowKeys(song("jump")))
        assertTrue(ExplicitFilter.permits(song("jump"), allowed, suggestion = false))
    }
}
