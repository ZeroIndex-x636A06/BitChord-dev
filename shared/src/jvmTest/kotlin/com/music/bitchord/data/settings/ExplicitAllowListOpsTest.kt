package com.music.bitchord.data.settings

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.ExplicitFilter
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class ExplicitAllowListOpsTest {

    private fun song(id: String, title: String, artist: String = "Artist") = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = "https://example.invalid/$id",
        isExplicit = true,
    )

    @Test
    fun `allowing keeps a readable record, newest first`() {
        val list = ExplicitAllowListOps.withSong(emptyList(), song("a", "First"), now = 1)
            .let { ExplicitAllowListOps.withSong(it, song("b", "Second"), now = 2) }
        assertEquals(listOf("Second", "First"), list.map { it.title })
        assertEquals("https://example.invalid/b", list.first().thumbnailUrl)
        assertEquals(2L, list.first().allowedAtMillis)
    }

    @Test
    fun `allowing the same recording twice keeps one entry`() {
        val once = ExplicitAllowListOps.withSong(emptyList(), song("a", "Same Song"), now = 1)
        val twice = ExplicitAllowListOps.withSong(once, song("other-id", "Same Song"), now = 2)
        assertEquals(1, twice.size)
    }

    @Test
    fun `removing from another listing of the same song removes the entry`() {
        val list = ExplicitAllowListOps.withSong(emptyList(), song("search-id", "Track"), now = 1)
        assertTrue(ExplicitAllowListOps.withoutSong(list, song("album-id", "Track (Explicit)")).isEmpty())
    }

    @Test
    fun `removing one song leaves the others`() {
        val list = ExplicitAllowListOps.withSong(emptyList(), song("a", "One"), now = 1)
            .let { ExplicitAllowListOps.withSong(it, song("b", "Two"), now = 2) }
        assertEquals(listOf("One"), ExplicitAllowListOps.withoutSong(list, song("b", "Two")).map { it.title })
    }

    @Test
    fun `keys cover every stored song`() {
        val a = song("a", "One")
        val b = song("b", "Two")
        val list = ExplicitAllowListOps.withSong(ExplicitAllowListOps.withSong(emptyList(), a, 1), b, 2)
        val keys = ExplicitAllowListOps.keysOf(list)
        assertTrue(ExplicitFilter.isAllowedBySong(a, keys))
        assertTrue(ExplicitFilter.isAllowedBySong(b, keys))
    }

    @Test
    fun `store persists every change and reloads what it wrote`() {
        var saved: String? = "untouched"
        val store = ExplicitAllowListStore { saved = it }
        store.allow(song("a", "One"), now = 5)
        val written = saved
        assertTrue(written != null && "One" in written)

        val reloaded = ExplicitAllowListStore { }
        reloaded.load(written)
        assertEquals(listOf("One"), reloaded.songs.value.map { it.title })
        assertTrue(ExplicitFilter.isAllowedBySong(song("a", "One"), reloaded.keys.value))
    }

    @Test
    fun `store writes null once the list is empty`() {
        var saved: String? = "untouched"
        val store = ExplicitAllowListStore { saved = it }
        store.allow(song("a", "One"), now = 1)
        store.clear()
        assertEquals(null, saved)
        assertTrue(store.keys.value.isEmpty())
    }

    @Test
    fun `unreadable stored data loads as an empty list`() {
        val store = ExplicitAllowListStore { }
        store.load("{not json")
        assertTrue(store.songs.value.isEmpty())
    }
}
