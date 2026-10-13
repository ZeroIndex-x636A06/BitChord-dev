package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/** TEMPORARY live probe — never upstream. Runs only with PROBE=1. */
class HomeExplicitProbe {
    private fun say(line: String) { println(line); File("probe-out.txt").appendText(line + "\n") }

    @Test
    fun probe() = runBlocking {
        if (System.getenv("PROBE") != "1") return@runBlocking
        val feed = YtMusicRepository.home().getOrElse { say("home failed: $it"); return@runBlocking }
        var songs = 0; var marked = 0; var checked = 0; var agree = 0; val mismatches = mutableListOf<String>()
        for (shelf in feed.shelves) {
            val tracks = shelf.items.filter { it.videoId != null }
            say("## ${shelf.title}: ${shelf.items.size} items, ${tracks.size} tracks, ${tracks.count { it.isExplicit == true }} marked E")
            for (item in tracks) {
                songs++
                if (item.isExplicit == true) marked++
                if (checked >= 40) continue
                val rows = YtMusicRepository.search("${item.title} ${item.subtitle}", SearchFilter.SONGS).getOrNull().orEmpty()
                    .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
                val same = rows.firstOrNull { it.videoId == item.videoId } ?: continue
                checked++
                val home = item.isExplicit == true
                val search = same.isExplicit == true
                if (home == search) agree++ else mismatches += "'${item.title}' home=$home search=$search"
                say("   ${if (home) "E" else "-"} ${item.title} — ${item.subtitle} (search says ${if (search) "E" else "-"})")
            }
        }
        say("TOTAL: $songs track cards, $marked marked E; cross-checked $checked against search, $agree agree")
        mismatches.forEach { say("MISMATCH $it") }
    }
}
