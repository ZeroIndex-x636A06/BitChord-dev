package com.music.bitchord.playback

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
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

    private fun video(id: String, title: String, channel: String, length: String?) =
        song(id, title = title, artist = channel, length = length, video = true)

    private val explicit = song("explicit", explicit = true)

    @BeforeTest
    fun setUp() = CleanVersions.reset()

    // ---- Matching -----------------------------------------------------------

    @Test
    fun `the clean release of the same song is picked`() {
        val candidates = listOf(
            song("explicit", explicit = true),
            song("other", title = "Rap Killa", artist = "Karan Aujla"),
            song("clean"),
        )
        assertEquals("clean", CleanVersionMatcher.pick(explicit, candidates)?.videoId)
    }

    @Test
    fun `edition and soundtrack tags don't make it a different song`() {
        val tagged = song("tagged", title = "Rap God (Clean)")
        assertEquals("tagged", CleanVersionMatcher.pick(explicit, listOf(tagged))?.videoId)
        val loseYourself = song("e", title = "Lose Yourself", length = "5:21", explicit = true)
        val soundtrack = song("st", title = "Lose Yourself (From \"8 Mile\" Soundtrack)", length = "5:22")
        assertEquals("st", CleanVersionMatcher.pick(loseYourself, listOf(soundtrack))?.videoId)
    }

    @Test
    fun `explicit rows, videos, other versions, other artists and other cuts are never picked`() {
        val candidates = listOf(
            song("dirty-again", explicit = true),
            song("video", video = true),
            song("live", title = "Rap God (Live)"),
            song("remix", title = "Rap God Remix"),
            song("cover", artist = "Kane Vortex"),
            song("short", length = "3:30"),
        )
        assertNull(CleanVersionMatcher.pick(explicit, candidates))
    }

    @Test
    fun `the closest length wins`() {
        val candidates = listOf(song("near", length = "6:10"), song("nearest", length = "6:05"))
        assertEquals("nearest", CleanVersionMatcher.pick(explicit, candidates)?.videoId)
    }

    @Test
    fun `an upload that says it's the clean edit is picked`() {
        val explicit = song("e", title = "Lose Yourself", length = "5:21", explicit = true)
        val uploads = listOf(
            video("lyrics", "Eminem - Lose Yourself (Clean) | Lyrics", "Throwback Hits", "5:21"),
            video("short", "Lose yourself clean", "Lucien Whittenberger", "5:04"),
            video("other", "The Way I Am (Clean Version)", "Eminem", "5:03"),
        )
        assertEquals("lyrics", CleanVersionMatcher.pickVideo(explicit, uploads)?.videoId)
    }

    @Test
    fun `uploads that aren't the clean edit are never picked`() {
        val explicit = song("e", title = "Gold Digger", artist = "Kanye West", length = "3:28", explicit = true)
        val uploads = listOf(
            video("not-clean", "Kanye West - Gold Digger", "Kanye West", "3:28"),
            video("cover", "HALEY CAN RAP??? GOLD DIGGER COVER (CLEAN)", "Haley Leathers", "3:30"),
            video("no-artist", "Gold Digger (Clean)", "Random Channel", "3:28"),
            video("no-length", "Kanye West - Gold Digger (Clean)", "Only Clean Edits", null),
            video("slowed", "Kanye West - Gold Digger (Clean) slowed", "Edits", "3:28"),
        )
        assertNull(CleanVersionMatcher.pickVideo(explicit, uploads))
    }

    @Test
    fun `a featured artist's own remix is the featured version`() {
        val withJennie =
            song("e", title = "Dracula (feat. JENNIE)", artist = "Tame Impala", length = "3:30", explicit = true)
        val uploads = listOf(
            video("solo", "Tame Impala - Dracula (clean version)", "McDuxx", "3:26"),
            video(
                "jennie",
                "Tame Impala, JENNIE - Dracula (JENNIE Remix) (Clean - Lyrics) (feat. JENNIE)",
                "Tame Impala",
                "3:31",
            ),
        )
        assertEquals("jennie", CleanVersionMatcher.pickVideo(withJennie, uploads)?.videoId)
        val solo = song("e2", title = "Dracula", artist = "Tame Impala", length = "3:26", explicit = true)
        assertEquals("solo", CleanVersionMatcher.pickVideo(solo, uploads)?.videoId)
    }

    @Test
    fun `an upload naming the song across several pieces is the song`() {
        val remix = song(
            "e",
            title = "Costa Rica [Remix] (feat. The Kid LAROI)",
            artist = "Bankrol Hayden",
            length = "1:55",
            explicit = true,
        )
        val uploads = listOf(
            // The original, not the remix: another song.
            video("original", "Bankrol Hayden - Costa Rica (Clean - Lyrics)", "Polar Records", "1:54"),
            video("remix", "[CLEAN] Bankrol Hayden - Costa Rica (feat. The Kid LAROI) - Remix", "Link - Edits", "1:56"),
        )
        assertEquals("remix", CleanVersionMatcher.pickVideo(remix, uploads)?.videoId)
    }

    // ---- Lookup -------------------------------------------------------------

    @Test
    fun `songs come before uploads, and an upload is dressed as the song`() = runBlocking {
        val queries = mutableListOf<String>()
        CleanVersions.search = { query, filter ->
            queries += "$query/${filter.name}"
            if (filter == SearchFilter.VIDEOS) {
                listOf(video("upload", "Eminem - Rap God (Clean)", "Edits", "6:05"))
            } else {
                emptyList()
            }
        }
        val clean = CleanVersions.find(explicit)
        assertEquals(3, queries.size)
        assertEquals("upload", clean?.videoId)
        // Looks like the song, but stays marked as a YouTube video underneath.
        assertEquals("Rap God", clean?.title)
        assertEquals("Eminem", clean?.artist)
        assertFalse(clean!!.isVideo)
        assertTrue(clean.isVideoOrigin)
        // The menu can still say where it came from, asked as either song.
        assertEquals(CleanVersions.Source.YOUTUBE, CleanVersions.sourceOf("upload"))
        assertEquals(CleanVersions.Source.YOUTUBE, CleanVersions.sourceOf(explicit.videoId))
        // Converting the upload to audio later gives the same song.
        assertEquals(clean, CleanVersions.uploadAsSong("upload"))
    }

    @Test
    fun `an answer is remembered, and so is having none`() = runBlocking {
        var searches = 0
        CleanVersions.search = { _, _ -> searches++; listOf(song("clean")) }
        CleanVersions.find(explicit)
        CleanVersions.find(explicit)
        assertEquals(1, searches)
        assertEquals(CleanVersions.Source.YOUTUBE_MUSIC, CleanVersions.sourceOf("clean"))
        assertNull(CleanVersions.sourceOf("unrelated"))
        CleanVersions.reset()
        searches = 0
        CleanVersions.search = { _, _ -> searches++; emptyList() }
        assertNull(CleanVersions.find(explicit))
        assertNull(CleanVersions.find(explicit))
        assertEquals(3, searches)
    }

    @Test
    fun `a failed search is not remembered as none`() = runBlocking {
        var searches = 0
        CleanVersions.search = { _, _ -> searches++; null }
        assertNull(CleanVersions.find(explicit))
        assertNull(CleanVersions.find(explicit))
        assertEquals(6, searches)
    }

    @Test
    fun `clean songs are never looked up`() = runBlocking {
        var searches = 0
        CleanVersions.search = { _, _ -> searches++; emptyList() }
        assertNull(CleanVersions.find(song("clean")))
        assertEquals(0, searches)
    }

    @Test
    fun `a stand-in keeps the queue entry's place`() {
        val queued = explicit.copy(queueEntryId = "entry", playbackSource = "Album", playbackSourceId = "MPRE")
        val standIn = queued.cleanStandIn(song("clean"))
        assertEquals("clean", standIn.videoId)
        assertEquals("entry", standIn.queueEntryId)
        assertEquals("MPRE", standIn.playbackSourceId)
        assertEquals(false, standIn.isExplicit)
    }

    // ---- Editions -----------------------------------------------------------

    @Test
    fun `an album listed in both editions shows only the clean one`() {
        fun card(id: String, title: String, explicit: Boolean?) =
            ShelfItem(title, "Album • Eminem • 2013", null, null, id, isExplicit = explicit)
        val cards = listOf(
            card("e", "The Marshall Mathers LP2", true),
            card("r", "Recovery", true),
            card("c", "The Marshall Mathers LP2", null),
        )
        val kept = CleanEditions.cards(cards)
        // Recovery has no clean card here, so it stays.
        assertEquals(listOf("r", "c"), kept.map { it.browseId })
        // Song cards are left alone.
        val track = ShelfItem("Rap God", "Eminem", null, "v", null, isExplicit = true)
        assertTrue(track in CleanEditions.cards(listOf(track, card("c2", "Rap God", null))))
    }
}
