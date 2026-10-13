package com.music.bitchord.playback

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
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
    fun `a featured artist's own remix is the featured version`() {
        // Real results for "dracula tame impala clean": the version with JENNIE
        // is released as "Dracula (JENNIE Remix)", and the solo one shares its name.
        val withJennie = song("e", title = "Dracula (feat. JENNIE)", artist = "Tame Impala", length = "3:30", explicit = true)
        val uploads = listOf(
            video("solo", "Tame Impala - Dracula (clean version)", "McDuxx", "3:26"),
            video("jennie", "Tame Impala, JENNIE - Dracula (JENNIE Remix) (Clean - Lyrics) (feat. JENNIE)", "Tame Impala", "3:31"),
            video("slowed", "Tame Impala, JENNIE - Dracula (JENNIE Remix) (slowed & reverb)", "Jamie Deen Music", "3:30"),
        )
        assertEquals("jennie", CleanVersionMatcher.pickVideo(withJennie, uploads)?.videoId)
        // The solo song takes the solo upload, and never somebody else's remix.
        val solo = song("e2", title = "Dracula", artist = "Tame Impala", length = "3:26", explicit = true)
        assertEquals("solo", CleanVersionMatcher.pickVideo(solo, uploads)?.videoId)
        assertNull(CleanVersionMatcher.pickVideo(solo, uploads.drop(1)))
        // With no upload naming the featured artist, one that leaves the credit off still counts.
        assertEquals("solo", CleanVersionMatcher.pickVideo(withJennie, uploads.take(1))?.videoId)
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
        CleanVersions.saveDelayMs = 0
        // Lyrics aren't read in tests: every unbadged row counts as proven unless a test says otherwise.
        CleanVersions.provenClean = { true }
        queries.clear()
    }

    @Test
    fun `an unbadged row is only taken once its lyrics prove it clean`() = runBlocking {
        // The Box: YouTube Music lists a copy of the explicit recording with no badge.
        CleanVersions.provenClean = { it.videoId == "real-clean" }
        CleanVersions.search = { _, filter ->
            if (filter == SearchFilter.VIDEOS) {
                emptyList()
            } else {
                listOf(song("unbadged-explicit"), song("real-clean"))
            }
        }
        assertEquals("real-clean", CleanVersions.find(explicit)?.videoId)
    }

    @Test
    fun `nothing proven falls back to a clean upload, never the unbadged copy`() = runBlocking {
        CleanVersions.provenClean = { false }
        CleanVersions.search = { _, filter ->
            if (filter == SearchFilter.VIDEOS) {
                listOf(video("upload", "Eminem - Rap God (Clean)", "Edits", "6:05"))
            } else {
                listOf(song("unbadged-explicit"))
            }
        }
        assertEquals("upload", CleanVersions.find(explicit)?.videoId)
    }

    @Test
    fun `lyrics that couldn't be read aren't remembered as none`() = runBlocking {
        CleanVersions.provenClean = { null }
        CleanVersions.search = { _, filter -> if (filter == SearchFilter.VIDEOS) emptyList() else listOf(song("maybe")) }
        assertNull(CleanVersions.find(explicit))
        assertFalse(CleanVersions.isMissing(explicit))
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
    fun `songs come before uploads, and an upload becomes a clean audio version`() = runBlocking {
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
        // The clean audio version: the song's name and artist, the upload's audio.
        assertEquals("upload", clean.videoId)
        assertEquals("Rap God", clean.title)
        assertEquals("Eminem", clean.artist)
        assertFalse(clean.isVideo)
        assertEquals(false, clean.isExplicit)
        assertTrue(CleanVersions.isCleanRelease(clean))
        assertTrue(CleanVersions.isCleanUpload(clean))
        // "Convert to video": the upload as itself, and back.
        val upload = CleanVersions.asVideo(clean)!!
        assertEquals("Eminem - Rap God (Clean)", upload.title)
        assertTrue(upload.isVideo)
        assertEquals("Rap God", CleanVersions.asAudio(upload)!!.title)
        assertNull(CleanVersions.asAudio(explicit))
        // Off a playlist it was added to, the bare upload is the clean song again.
        val fromPlaylist = video("upload", "Eminem - Rap God (Clean)", "Edits", "6:05")
        assertEquals("Rap God", CleanVersions.asCreated(fromPlaylist).title)
        assertEquals("Other", CleanVersions.asCreated(video("x", "Other", "Someone", "3:00")).title)
    }

    @Test
    fun `an upload is still both forms after a restart`() = runBlocking {
        var saved = ""
        CleanVersions.attachStorage(null) { saved = it }
        CleanVersions.search = { _, filter ->
            if (filter == SearchFilter.VIDEOS) listOf(video("upload", "Eminem - Rap God (Clean)", "Edits", "6:05")) else emptyList()
        }
        CleanVersions.find(explicit)
        CleanVersions.reset()
        CleanVersions.attachStorage(saved) { }
        val clean = CleanVersions.cached(explicit)!!
        assertEquals("Rap God", clean.title)
        assertFalse(clean.isVideo)
        assertEquals("Eminem - Rap God (Clean)", CleanVersions.asVideo(clean)!!.title)
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
    fun `answers from an older lookup are asked again`() {
        // Before version 4 an upload was saved already dressed as the song.
        val older = """{"v":3,"found":[{"for":"explicit","id":"clean","title":"Rap God","artist":"Eminem"}],""" +
            """"missing":[{"for":"other","at":${System.currentTimeMillis()}}]}"""
        CleanVersions.attachStorage(older) { }
        assertNull(CleanVersions.cached(explicit))
        assertFalse(CleanVersions.isMissing(song("other", explicit = true)))
    }

    @Test
    fun `a failed search is not remembered as missing`() = runBlocking {
        CleanVersions.search = { _, _ -> null }
        assertNull(CleanVersions.find(explicit))
        assertFalse(CleanVersions.isMissing(explicit))
    }

    @Test
    fun `a row without a length gets one from the song's own search result`() = runBlocking {
        // Home cards and recent searches carry no length, and an upload is
        // never taken without one: the song's own Songs row supplies it.
        val card = song("explicit", explicit = true, length = null)
        CleanVersions.search = { _, filter ->
            if (filter == SearchFilter.VIDEOS) listOf(video("upload", "Eminem - Rap God (Clean)", "Edits", "6:05"))
            else listOf(song("explicit", explicit = true))
        }
        assertEquals("upload", CleanVersions.find(card)?.videoId)
    }

    @Test
    fun `the oldest answers are let go first`() = runBlocking {
        var saved = ""
        CleanVersions.attachStorage(null) { saved = it }
        CleanVersions.search = { q, _ -> listOf(song("clean-$q", title = q.replace(Regex(" eminem.*", RegexOption.IGNORE_CASE), ""))) }
        repeat(2_001) { CleanVersions.find(song("e$it", title = "Song $it", explicit = true)) }
        assertNull(CleanVersions.cached(song("e0", explicit = true)))
        assertEquals(2_000, CleanVersions.known.value.size)
        assertFalse("\"for\":\"e0\"" in saved)
        assertTrue("\"for\":\"e2000\"" in saved)
    }

    @Test
    fun `a lookup whose uploads search failed is not remembered as missing`() = runBlocking {
        CleanVersions.search = { _, filter -> if (filter == SearchFilter.VIDEOS) null else emptyList() }
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
        val swearing = object : ExplicitLyricsSource {
            override suspend fun exact(song: Song): List<String> {
                read++
                return listOf("fuck", "shit")
            }
            override fun others(song: Song): Flow<List<String>> = flowOf(listOf("fuck", "shit")).onStart { read++ }
            override suspend fun sameTitle(song: Song): List<List<String>> = emptyList()
        }
        val checker = ExplicitLyricsChecker(ExplicitLyricsStore { }, swearing)
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

    // ---- Editions -----------------------------------------------------------

    @Test
    fun `an album listed in both editions shows only the clean one`() {
        fun card(id: String, title: String, explicit: Boolean?) =
            com.music.bitchord.data.model.ShelfItem(title, "Album • Eminem • 2013", null, null, id, isExplicit = explicit)
        val explicitMmlp2 = card("e", "The Marshall Mathers LP2", true)
        val cleanMmlp2 = card("c", "The Marshall Mathers LP2", null)
        val recovery = card("r", "Recovery", true)
        val kept = CleanEditions.cards(listOf(explicitMmlp2, recovery, cleanMmlp2))
        // Recovery has no clean card here, so it stays.
        assertEquals(listOf("r", "c"), kept.map { it.browseId })
        // Track cards are left alone.
        val track = com.music.bitchord.data.model.ShelfItem("Rap God", "Eminem", null, "v", null, isExplicit = true)
        assertEquals(1, CleanEditions.cards(listOf(track, card("c2", "Rap God", null))).count { it.videoId == "v" })
    }
}
