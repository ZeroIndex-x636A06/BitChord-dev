package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What "Prefer music-only version" asks for a playlist's video, and what it keeps between launches. */
class AudioVersionLookupTest {

    private fun video(title: String, channel: String) =
        Song(videoId = "v", title = title, artist = channel, thumbnailUrl = null, isVideo = true)

    @Test
    fun `an upload titled Artist - Title by someone else's channel is read the other way round`() {
        val target = TrackMatcher.uploadTarget(video("Roddy Ricch - The Box (Clean - Lyrics)", "Polar Records"))
        assertEquals("The Box", target?.title)
        assertEquals("Roddy Ricch", target?.artist)
    }

    @Test
    fun `the artist's own upload and a title without a dash are left as titled`() {
        assertNull(TrackMatcher.uploadTarget(video("Roddy Ricch - The Box (Official Video)", "Roddy Ricch")))
        assertNull(TrackMatcher.uploadTarget(video("The Box (Official Video)", "Polar Records")))
        // Brackets before the dash: not "Artist - Title".
        assertNull(TrackMatcher.uploadTarget(video("The Box (Remix) - Topic Mix", "DJ Someone")))
    }

    @Test
    fun `an auto-generated Topic channel is the artist`() {
        assertEquals("Terror Squad", TrackMatcher.targetOf(video("Lean Back", "Terror Squad - Topic")).artist)
    }

    @Test
    fun `songs found by an earlier launch are restored, and an older list is not`() {
        val saved = "audio-versions 1\nvideo1\tsong1\tLean Back\tTerror Squad\t\t4:07\t\t\t\tfalse\t"
        YtMusicRepository.keepAudioVersions(saved) {}
        assertEquals("song1", YtMusicRepository.cachedAudioVersion("video1")?.videoId)
        assertEquals("4:07", YtMusicRepository.cachedAudioVersion("video1")?.durationText)
        YtMusicRepository.keepAudioVersions("audio-versions 0\nvideo2\tsong2\tX\tY\t\t\t\t\t\t\t") {}
        assertNull(YtMusicRepository.cachedAudioVersion("video2"))
    }
}
