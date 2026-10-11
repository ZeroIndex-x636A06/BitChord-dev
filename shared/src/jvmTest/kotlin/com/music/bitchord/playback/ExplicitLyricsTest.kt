package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
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
        assertTrue(ExplicitLyrics.isExplicit(listOf("What the fuck", "this is bullshit")))
    }

    @Test
    fun `one strong word alone does not`() {
        assertFalse(ExplicitLyrics.isExplicit(listOf("Oh shit, I forgot my keys", "then I went home")))
    }

    @Test
    fun `a strong word and a few mild ones add up`() {
        // The shape of Jump Around: one strong word, an "ass", two "ho"s.
        val lines = listOf("I'll serve your ass", "smacking the ho", "you son of a bitch", "another ho")
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
    fun `starred-out spellings still count`() {
        assertTrue(ExplicitLyrics.isExplicit(listOf("f**k it", "sh*t happens")))
    }

    @Test
    fun `case and inflection don't hide a word`() {
        assertEquals(2 * ExplicitLyrics.STRONG_POINTS, ExplicitLyrics.score("FUCKING Motherfucker"))
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
    fun `the oldest verdicts go first when the store is full`() {
        val store = ExplicitLyricsStore { }
        repeat(ExplicitLyricsStore.MAX_ENTRIES + 1) { store.record("id$it", it == 0) }
        assertFalse(store.knows("id0"))
        assertTrue(store.knows("id${ExplicitLyricsStore.MAX_ENTRIES}"))
        assertTrue(store.flagged.value.isEmpty())
    }

    // ---- Checking ----------------------------------------------------------

    @Test
    fun `a song is read once`() = runBlocking {
        var fetched = 0
        val store = ExplicitLyricsStore { }
        val checker = ExplicitLyricsChecker(store) { fetched++; listOf("fuck", "shit") }
        assertTrue(checker.needsCheck(song("a")))
        assertEquals(true, checker.check(song("a")))
        assertEquals(true, checker.check(song("a")))
        assertEquals(1, fetched)
        assertFalse(checker.needsCheck(song("a")))
        assertEquals(setOf("a"), store.flagged.value)
    }

    @Test
    fun `no lyrics counts as clean but a failed lookup is not remembered`() = runBlocking {
        val store = ExplicitLyricsStore { }
        assertEquals(false, ExplicitLyricsChecker(store) { null }.check(song("instrumental")))
        assertTrue(store.knows("instrumental"))
        assertNull(ExplicitLyricsChecker(store) { error("offline") }.check(song("offline")))
        assertFalse(store.knows("offline"))
    }

    @Test
    fun `songs the catalogue already marks are never read`() = runBlocking {
        var fetched = 0
        val checker = ExplicitLyricsChecker(ExplicitLyricsStore { }) { fetched++; emptyList() }
        assertFalse(checker.needsCheck(song("e", explicit = true)))
        assertEquals(true, checker.check(song("e", explicit = true)))
        assertEquals(0, fetched)
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
