package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CleanVersionsTest {

    private fun song(
        id: String,
        title: String = "Rap God",
        artist: String = "Eminem",
        length: String? = "6:04",
        explicit: Boolean? = null,
        video: Boolean = false,
    ) = Song(
        videoId = id, title = title, artist = artist, thumbnailUrl = null,
        durationText = length, isExplicit = explicit, isVideo = video,
    )

    private val explicit = song("explicit", explicit = true)

    // ---- Matching -----------------------------------------------------------

    @Test
    fun `the clean release of the same recording is picked`() {
        // Shape of the real Songs shelf for "rap god eminem".
        val candidates = listOf(
            song("explicit", explicit = true),
            song("other", title = "Rap Killa", artist = "Karan Aujla"),
            song("clean"),
        )
        assertEquals("clean", CleanVersionMatcher.pick(explicit, candidates)?.videoId)
    }

    @Test
    fun `edition tags don't make it a different song`() {
        assertEquals(
            "tagged",
            CleanVersionMatcher.pick(explicit, listOf(song("tagged", title = "Rap God (Clean)")))?.videoId,
        )
        assertEquals(
            "radio",
            CleanVersionMatcher.pick(explicit, listOf(song("radio", title = "Rap God - Radio Edit", length = "6:01")))?.videoId,
        )
        val tagged = song("x", title = "Rap God (Explicit)", explicit = true)
        assertEquals("clean", CleanVersionMatcher.pick(tagged, listOf(song("clean")))?.videoId)
    }

    @Test
    fun `explicit rows, videos, other versions and other artists are never picked`() {
        val candidates = listOf(
            song("dirty-again", explicit = true),
            song("video", video = true),
            song("live", title = "Rap God (Live)"),
            song("remix", title = "Rap God Remix"),
            song("cover", artist = "Kane Vortex"),
        )
        assertNull(CleanVersionMatcher.pick(explicit, candidates))
    }

    @Test
    fun `a soundtrack tag doesn't make it a different song`() {
        // Lose Yourself's clean release is listed under its soundtrack's name.
        val explicit = song("e", title = "Lose Yourself", length = "5:21", explicit = true)
        val soundtrack = song("st", title = "Lose Yourself (From \"8 Mile\" Soundtrack)", length = "5:22")
        assertEquals("st", CleanVersionMatcher.pick(explicit, listOf(soundtrack))?.videoId)
    }

    // ---- Uploaded clean edits ------------------------------------------------

    private fun video(id: String, title: String, channel: String, length: String?) =
        song(id, title = title, artist = channel, length = length, video = true)

    @Test
    fun `an upload that says it's the clean edit is picked`() {
        val explicit = song("e", title = "Lose Yourself", length = "5:21", explicit = true)
        // Real titles from YouTube's results for "Lose Yourself Eminem clean".
        val uploads = listOf(
            video("lyrics", "Eminem - Lose Yourself (Clean) | Lyrics", "Throwback Hits", "5:21"),
            video("short", "Lose yourself clean", "Lucien Whittenberger", "5:04"),
            video("other", "The Way I Am (Clean Version)", "Eminem", "5:03"),
        )
        assertEquals("lyrics", CleanVersionMatcher.pickVideo(explicit, uploads)?.videoId)
    }

    @Test
    fun `the song's name can sit anywhere in an upload's title`() {
        val explicit = song("e", title = "Gold Digger (feat. Jamie Foxx)", artist = "Kanye West", length = "3:28", explicit = true)
        val shapes = listOf(
            "Kanye West feat Jamie Foxx - Gold Digger (Clean Version)",
            "Gold Digger (Clean) - Kanye West",
            "Kanye West | Gold Digger | [BEST CLEAN VERSION]",
        )
        shapes.forEach { title ->
            assertEquals("v", CleanVersionMatcher.pickVideo(explicit, listOf(video("v", title, "Some Channel", "3:29")))?.videoId, title)
        }
    }

    @Test
    fun `uploads that aren't the clean edit are never picked`() {
        val explicit = song("e", title = "Gold Digger", artist = "Kanye West", length = "3:28", explicit = true)
        val uploads = listOf(
            video("not-clean", "Kanye West - Gold Digger", "Kanye West", "3:28"),
            video("cover", "HALEY CAN RAP??? GOLD DIGGER COVER (CLEAN)", "Haley Leathers", "3:30"),
            video("lipsync", "Year 12 camp lip sync Kanye West - Gold Digger (Clean Version)", "Vanessa B", "3:28"),
            video("no-artist", "Gold Digger (Clean)", "Random Channel", "3:28"),
            video("no-length", "Kanye West - Gold Digger (Clean)", "Only Clean Edits", null),
            video("slowed", "Kanye West - Gold Digger (Clean) slowed", "Edits", "3:28"),
        )
        assertNull(CleanVersionMatcher.pickVideo(explicit, uploads))
    }

    @Test
    fun `a different length is a different cut`() {
        assertNull(CleanVersionMatcher.pick(explicit, listOf(song("short", length = "3:30"))))
        // Unknown length on either side doesn't rule it out.
        assertEquals("unknown", CleanVersionMatcher.pick(explicit, listOf(song("unknown", length = null)))?.videoId)
    }

    @Test
    fun `the closest length wins`() {
        val candidates = listOf(song("near", length = "6:10"), song("nearest", length = "6:05"))
        assertEquals("nearest", CleanVersionMatcher.pick(explicit, candidates)?.videoId)
    }

    // ---- Lookup and memory --------------------------------------------------

    private val queries = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        CleanVersions.reset()
        queries.clear()
    }

    @AfterTest
    fun tearDown() = CleanVersions.reset()

    @Test
    fun `a found clean release is remembered`() = runBlocking {
        CleanVersions.search = { q, _ -> queries += q; listOf(song("clean")) }
        assertEquals("clean", CleanVersions.find(explicit)?.videoId)
        assertEquals("clean", CleanVersions.find(explicit)?.videoId)
        assertEquals(1, queries.size)
        assertEquals("clean", CleanVersions.cached(explicit)?.videoId)
    }

    @Test
    fun `no clean release is remembered as missing`() = runBlocking {
        CleanVersions.search = { q, _ -> queries += q; listOf(song("cover", artist = "Someone Else")) }
        assertNull(CleanVersions.find(explicit))
        assertTrue(CleanVersions.isMissing(explicit))
        val asked = queries.size
        assertNull(CleanVersions.find(explicit))
        assertEquals(asked, queries.size)
    }

    @Test
    fun `songs come before uploads, and the upload plays under the song's name`() = runBlocking {
        CleanVersions.search = { q, filter ->
            queries += "$filter:$q"
            if (filter == com.music.bitchord.data.model.SearchFilter.VIDEOS) {
                listOf(video("upload", "Eminem - Rap God (Clean)", "Edits", "6:05"))
            } else {
                emptyList()
            }
        }
        val clean = CleanVersions.find(explicit)!!
        assertEquals(3, queries.size)
        assertTrue(queries.last().startsWith("VIDEOS:"))
        assertEquals("upload", clean.videoId)
        assertEquals("Rap God", clean.title)
        assertEquals("Eminem", clean.artist)
        assertFalse(clean.isVideo)
        assertEquals(false, clean.isExplicit)
        assertTrue(CleanVersions.isCleanRelease(clean))
    }

    @Test
    fun `what was found survives a restart, and lists learn which songs swap`() = runBlocking {
        var saved = ""
        CleanVersions.attachStorage(null) { saved = it }
        CleanVersions.search = { _, _ -> listOf(song("clean")) }
        CleanVersions.find(explicit)
        assertEquals(setOf("explicit"), CleanVersions.known.value)
        CleanVersions.search = { _, _ -> emptyList() }
        CleanVersions.find(song("other", title = "Other", explicit = true))
        CleanVersions.reset()
        CleanVersions.attachStorage(saved) { }
        assertEquals("clean", CleanVersions.cached(explicit)?.videoId)
        assertEquals(setOf("explicit"), CleanVersions.known.value)
        assertTrue(CleanVersions.isMissing(song("other", explicit = true)))
        assertTrue(CleanVersions.isCleanRelease(song("clean")))
    }

    @Test
    fun `a failed search is not remembered as missing`() = runBlocking {
        CleanVersions.search = { _, _ -> null }
        assertNull(CleanVersions.find(explicit))
        assertFalse(CleanVersions.isMissing(explicit))
    }

    @Test
    fun `clean tracks are never looked up`() = runBlocking {
        CleanVersions.search = { q, _ -> queries += q; emptyList() }
        assertNull(CleanVersions.find(song("clean")))
        assertTrue(queries.isEmpty())
    }

    @Test
    fun `a stand-in keeps the queue entry's place`() {
        val queued = explicit.copy(queueEntryId = "entry", playbackSource = "Album", playbackSourceId = "MPRE")
        val standIn = queued.cleanStandIn(song("clean"))
        assertEquals("clean", standIn.videoId)
        assertEquals("entry", standIn.queueEntryId)
        assertEquals("MPRE", standIn.playbackSourceId)
        assertEquals("Album", standIn.playbackSource)
    }

    @Test
    fun `suggestions swap what has a clean release and drop what doesn't`() = runBlocking {
        CleanVersions.search = { q, _ ->
            if (q.startsWith("rap god", ignoreCase = true)) listOf(song("rap-god-clean")) else emptyList()
        }
        val policy = ExplicitPolicy(ExplicitFilterMode.SUGGESTIONS, swapToClean = true)
        val station = listOf(
            song("clean-one", title = "Lose Yourself"),
            song("rap-god", explicit = true),
            song("no-clean", title = "Kim", explicit = true),
            song("too-far", title = "Rap God", explicit = true),
        )
        val out = CleanVersions.applyToSuggestions(station, policy, lookUpTo = 3)
        assertEquals(listOf("clean-one", "rap-god-clean"), out.map { it.videoId })
        // Without the swap, blocked suggestions are just left out.
        val plain = CleanVersions.applyToSuggestions(station, policy.copy(swapToClean = false), lookUpTo = 3)
        assertEquals(listOf("clean-one"), plain.map { it.videoId })
    }

    @Test
    fun `a found clean release is never put through the lyric check`() = runBlocking {
        CleanVersions.search = { _, _ -> listOf(song("clean")) }
        val clean = CleanVersions.find(explicit)!!
        var read = 0
        val checker = ExplicitLyricsChecker(ExplicitLyricsStore { }) { read++; listOf("fuck", "shit") }
        assertFalse(checker.needsCheck(clean))
        assertEquals(false, checker.check(clean))
        assertEquals(0, read)
    }

    // ---- Policy -------------------------------------------------------------

    @Test
    fun `only a blocked track with a possible clean release swaps`() {
        val swapping = ExplicitPolicy(ExplicitFilterMode.EVERYWHERE, swapToClean = true)
        assertTrue(ExplicitFilter.swapsToClean(explicit, swapping, suggestion = false))
        assertFalse(ExplicitFilter.swapsToClean(explicit, swapping, suggestion = false, missing = setOf("explicit")))
        assertFalse(ExplicitFilter.swapsToClean(song("clean"), swapping, suggestion = false))
        assertFalse(
            ExplicitFilter.swapsToClean(explicit, swapping.copy(swapToClean = false), suggestion = false),
        )
        // Allowed by hand: plays as it is.
        val allowed = swapping.copy(allowed = ExplicitFilter.allowKeys(explicit))
        assertFalse(ExplicitFilter.swapsToClean(explicit, allowed, suggestion = false))
        // Suggestions-only mode swaps what the app picks, not what the listener does.
        val suggestions = swapping.copy(mode = ExplicitFilterMode.SUGGESTIONS)
        assertTrue(ExplicitFilter.swapsToClean(explicit, suggestions, suggestion = true))
        assertFalse(ExplicitFilter.swapsToClean(explicit, suggestions, suggestion = false))
        // A song blocked only by its lyrics has no edited release to find.
        val byLyrics = swapping.copy(lyricsFlagged = setOf("jump"))
        assertFalse(ExplicitFilter.swapsToClean(song("jump", title = "Jump Around", artist = "House of Pain"), byLyrics, suggestion = false))
    }
}
