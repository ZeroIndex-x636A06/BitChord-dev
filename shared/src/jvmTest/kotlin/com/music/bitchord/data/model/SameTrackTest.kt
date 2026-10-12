package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SameTrackTest {

    private fun song(id: String, title: String, artist: String, duration: String?) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = duration)

    @Test
    fun `two recordings that share a name are told apart by length`() {
        // Xscape (Deluxe): the 2014 production and the original demo.
        val produced = song("a", "Chicago", "Michael Jackson", "4:06")
        val original = song("b", "Chicago", "Michael Jackson", "4:44")
        assertFalse(produced.isSameTrackAs(original))
        assertFalse(original.isSameTrackAs(produced))
    }

    @Test
    fun `one recording from two sources still matches`() {
        val youtube = song("yt", "Chicago", "Michael Jackson", "4:06")
        val download = song("local", "Chicago", "Michael Jackson", "4:05")
        assertTrue(youtube.isSameTrackAs(download))
    }

    @Test
    fun `a row with no stated length is never ruled out by length`() {
        val playing = song("a", "Chicago", "Michael Jackson", "4:06")
        val noLength = song("b", "Chicago", "Michael Jackson", null)
        assertTrue(noLength.isSameTrackAs(playing))
        assertTrue(playing.isSameTrackAs(noLength))
    }

    @Test
    fun `a different name or credit never matches`() {
        val playing = song("a", "Chicago", "Michael Jackson", "4:06")
        assertFalse(song("b", "Chicago", "Sufjan Stevens", "4:06").isSameTrackAs(playing))
        assertFalse(song("c", "Xscape", "Michael Jackson", "4:06").isSameTrackAs(playing))
        assertFalse(playing.isSameTrackAs(null))
    }

    @Test
    fun `a list holding the playing id lights only that row`() {
        val first = song("a", "Chicago", "Michael Jackson", "4:06")
        val twin = song("b", "Chicago", "Michael Jackson", "4:06")
        val matcher = listOf(first, twin).nowPlayingMatcher(twin)
        assertFalse(matcher(first))
        assertTrue(matcher(twin))
    }

    @Test
    fun `a list without the playing id falls back to name and length`() {
        val row = song("a", "Chicago", "Michael Jackson", "4:06")
        val other = song("b", "Chicago", "Michael Jackson", "4:44")
        val download = song("local", "Chicago", "Michael Jackson", "4:05")
        val matcher = listOf(row, other).nowPlayingMatcher(download)
        assertTrue(matcher(row))
        assertFalse(matcher(other))
        assertFalse(listOf(row).nowPlayingMatcher(null)(row))
    }

    @Test
    fun `rows with no length to tell them apart light up once`() {
        // Search lists Michael Jackson's two "Chicago"s without lengths.
        val first = song("a", "Chicago", "Michael Jackson", null)
        val second = song("b", "Chicago", "Michael Jackson", null)
        val exact = listOf(first, second).nowPlayingMatcher(second)
        assertFalse(exact(first))
        assertTrue(exact(second))
        val elsewhere = listOf(first, second).nowPlayingMatcher(song("c", "Chicago", "Michael Jackson", "4:06"))
        assertTrue(elsewhere(first))
        assertFalse(elsewhere(second))
    }
}
