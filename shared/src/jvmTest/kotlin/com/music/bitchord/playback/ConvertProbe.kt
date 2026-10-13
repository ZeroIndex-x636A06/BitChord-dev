package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/** TEMPORARY live probe — never upstream. Runs only with PROBE=1. */
class ConvertProbe {
    private fun say(line: String) { println(line); File("probe-out.txt").appendText(line + "\n") }
    private fun Song.d() = "$videoId '$title' / '$artist' len=$durationText E=$isExplicit video=$isVideo"

    private val wanted = listOf(
        "Roddy Ricch - The Box (Clean - Lyrics)" to "Polar Records",
        "Internet Money - Lemonade (Clean" to "lunaristic music",
        "Juice WRLD - Wishing Well (Clean" to "Polar Records",
        "NLE Choppa - Walk Em Down (Clean" to "Polar Records",
        "XXXTENTACION - MOONLIGHT (Clean" to "Sir Sammy",
        "Jack Harlow - WHATS POPPIN (Clean" to "Polar Records",
        "DaBaby - BOP (Clean - Lyrics)" to "Polar Records",
        "Drake - Toosie Slide (Clean - Lyrics)" to "Polar Records",
    )

    @Test
    fun probe() = runBlocking {
        if (System.getenv("PROBE") != "1") return@runBlocking
        File("probe-out.txt").delete()
        for ((title, channel) in wanted) {
            val rows = YtMusicRepository.search("$title $channel", SearchFilter.VIDEOS).getOrNull().orEmpty()
                .mapNotNull { (it as? SearchResult.Track)?.song }
            val v = rows.firstOrNull { it.artist.equals(channel, true) } ?: rows.firstOrNull()
            if (v == null) { say("NO VIDEO for $title"); continue }
            say("VIDEO ${v.d()}")
            say("  uploadTarget=${com.music.bitchord.data.sources.TrackMatcher.uploadTarget(v)}")
            val r = runCatching { YtMusicRepository.resolveAudio(v) }
            say("  -> ${r.getOrNull()?.d() ?: "FAILED " + r.exceptionOrNull()?.message}  cleanRelease=${r.getOrNull()?.let { CleanVersions.isCleanRelease(it) }} upload=${r.getOrNull()?.let { CleanVersions.isCleanUpload(it) }}")
        }
    }
}
