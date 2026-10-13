package com.music.bitchord.playback

import com.music.bitchord.data.model.Song

/**
 * "Block explicit songs" (#720): keeps songs YouTube Music marks explicit out
 * of AutoPlay and radio, and skips them in the queue. [allowed] holds the
 * video ids of songs the listener let through one at a time from the song
 * menu. Shared so the phone and desktop apply the same rule.
 */
object ExplicitFilter {
    fun blocks(song: Song, enabled: Boolean, allowed: Set<String>): Boolean =
        enabled && song.isExplicit == true && song.videoId !in allowed

    fun filter(songs: List<Song>, enabled: Boolean, allowed: Set<String>): List<Song> =
        if (!enabled) songs else songs.filterNot { blocks(it, enabled, allowed) }
}
