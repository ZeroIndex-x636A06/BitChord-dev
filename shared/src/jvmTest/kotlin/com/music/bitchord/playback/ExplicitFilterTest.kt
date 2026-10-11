package com.music.bitchord.playback

import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.Song
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class ExplicitFilterTest {

    private fun song(
        id: String,
        title: String = "Song $id",
        artist: String = "Artist",
        explicit: Boolean? = null,
        sourceId: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        isExplicit = explicit,
        playbackSourceId = sourceId,
    )

    private val dirty = song("dirty", explicit = true)
    private val clean = song("clean", explicit = false)
    private val unknown = song("unknown", explicit = null)

    @Test
    fun `off lets everything through`() {
        for (s in listOf(dirty, clean, unknown)) {
            assertTrue(ExplicitFilter.permits(s, ExplicitPolicy(ExplicitFilterMode.OFF, emptySet()), suggestion = true))
            assertTrue(ExplicitFilter.permits(s, ExplicitPolicy(ExplicitFilterMode.OFF, emptySet()), suggestion = false))
        }
    }

    @Test
    fun `suggestions mode keeps explicit tracks out of autoplay only`() {
        val mode = ExplicitFilterMode.SUGGESTIONS
        assertFalse(ExplicitFilter.permits(dirty, ExplicitPolicy(mode, emptySet()), suggestion = true))
        assertTrue(ExplicitFilter.permits(dirty, ExplicitPolicy(mode, emptySet()), suggestion = false))
        assertTrue(ExplicitFilter.permits(clean, ExplicitPolicy(mode, emptySet()), suggestion = true))
    }

    @Test
    fun `everywhere mode blocks explicit tracks the listener picks too`() {
        val mode = ExplicitFilterMode.EVERYWHERE
        assertFalse(ExplicitFilter.permits(dirty, ExplicitPolicy(mode, emptySet()), suggestion = true))
        assertFalse(ExplicitFilter.permits(dirty, ExplicitPolicy(mode, emptySet()), suggestion = false))
        assertTrue(ExplicitFilter.permits(clean, ExplicitPolicy(mode, emptySet()), suggestion = false))
    }

    @Test
    fun `a track whose source says nothing is treated as clean`() {
        assertTrue(ExplicitFilter.permits(unknown, ExplicitPolicy(ExplicitFilterMode.EVERYWHERE, emptySet()), suggestion = true))
    }

    @Test
    fun `an allowed song gets through every mode`() {
        val allowed = ExplicitFilter.allowKeys(dirty)
        for (mode in ExplicitFilterMode.values()) {
            assertTrue(ExplicitFilter.permits(dirty, ExplicitPolicy(mode, allowed), suggestion = true))
            assertTrue(ExplicitFilter.permits(dirty, ExplicitPolicy(mode, allowed), suggestion = false))
        }
    }

    @Test
    fun `allowing a song covers the same recording listed under another id`() {
        val fromSearch = song("abc", title = "HUMBLE.", artist = "Kendrick Lamar", explicit = true)
        val fromAlbum = Song(
            videoId = "xyz",
            title = "HUMBLE. (Explicit)",
            artist = "Kendrick Lamar",
            thumbnailUrl = null,
            artists = listOf(ArtistRef("Kendrick Lamar")),
            isExplicit = true,
        )
        val allowed = ExplicitFilter.allowKeys(fromSearch)
        assertTrue(ExplicitFilter.permits(fromAlbum, ExplicitPolicy(ExplicitFilterMode.EVERYWHERE, allowed), suggestion = false))
    }

    @Test
    fun `allowing one song does not allow another by the same artist`() {
        val a = song("a", title = "One", artist = "Same Artist", explicit = true)
        val b = song("b", title = "Two", artist = "Same Artist", explicit = true)
        val allowed = ExplicitFilter.allowKeys(a)
        assertFalse(ExplicitFilter.permits(b, ExplicitPolicy(ExplicitFilterMode.EVERYWHERE, allowed), suggestion = false))
    }

    @Test
    fun `filter keeps order and drops only what is blocked`() {
        val list = listOf(clean, dirty, unknown)
        assertEquals(
            listOf("clean", "unknown"),
            ExplicitFilter.filter(list, ExplicitPolicy(ExplicitFilterMode.SUGGESTIONS, emptySet()), suggestion = true)
                .map { it.videoId },
        )
        assertEquals(list, ExplicitFilter.filter(list, ExplicitPolicy(ExplicitFilterMode.OFF, emptySet()), suggestion = true))
    }

    private val mine = "VLPLmine"
    private val everywhereOwn = ExplicitPolicy(
        ExplicitFilterMode.EVERYWHERE,
        allowOwnPlaylists = true,
        ownPlaylistIds = setOf(mine),
    )

    @Test
    fun `own playlist exception lets explicit songs play from your playlist`() {
        assertTrue(ExplicitFilter.permits(dirty, everywhereOwn, suggestion = false, sourceId = mine))
        assertTrue(ExplicitFilter.permits(song("q", explicit = true, sourceId = mine), everywhereOwn, suggestion = false))
    }

    @Test
    fun `own playlist exception does not reach other pages or suggestions`() {
        assertFalse(ExplicitFilter.permits(dirty, everywhereOwn, suggestion = false, sourceId = "VLPLsomeoneelse"))
        assertFalse(ExplicitFilter.permits(dirty, everywhereOwn, suggestion = false, sourceId = null))
        // AutoPlay copies the playlist's id onto what it adds; that is still a suggestion.
        assertFalse(ExplicitFilter.permits(song("a", explicit = true, sourceId = mine), everywhereOwn, suggestion = true))
    }

    @Test
    fun `own playlist exception is off unless switched on`() {
        val off = everywhereOwn.copy(allowOwnPlaylists = false)
        assertFalse(ExplicitFilter.permits(dirty, off, suggestion = false, sourceId = mine))
    }

    @Test
    fun `filter applies a page id to rows that carry none`() {
        val rows = listOf(dirty, clean)
        assertEquals(rows, ExplicitFilter.filter(rows, everywhereOwn, suggestion = false, sourceId = mine))
        assertEquals(listOf(clean), ExplicitFilter.filter(rows, everywhereOwn, suggestion = false))
    }
}
