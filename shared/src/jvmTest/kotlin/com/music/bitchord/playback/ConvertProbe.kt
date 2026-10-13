package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.YouTubeMusicLyrics
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/** TEMPORARY live probe — never upstream. Runs only with PROBE=1. */
class ConvertProbe {
    private fun say(line: String) { println(line); File("probe-out.txt").appendText(line + "\n") }

    private suspend fun lyr(id: String): String {
        val w = runCatching { YouTubeMusicLyrics.lyricsOrThrow(id) }.getOrNull()?.map { it.text }
        return if (w == null) "nolyrics" else "score=" + ExplicitLyrics.score(w.joinToString("\n"), countMasked = false) +
            " masked=" + ExplicitLyrics.score(w.joinToString("\n"))
    }

    @Test
    fun probe() = runBlocking {
        if (System.getenv("PROBE") != "1") return@runBlocking
        File("probe-out.txt").delete()
        for ((q, anon) in listOf("The Box Roddy Ricch" to true, "The Box Roddy Ricch clean" to true, "The Box Roddy Ricch" to false,
            "HOLIDAY Lil Nas X" to true, "HOLIDAY Lil Nas X clean" to true, "Rags2Riches Rod Wave" to true, "Rags2Riches Rod Wave clean" to true)) {
            val rows = (if (anon) YtMusicRepository.searchSignedOut(q, SearchFilter.SONGS) else YtMusicRepository.search(q, SearchFilter.SONGS))
                .getOrNull().orEmpty().mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
            say("Q '$q' signedOut=$anon")
            rows.take(8).forEach { s -> say("   ${s.videoId} '${s.title}' / ${s.artist} alb='${s.albumName}' len=${s.durationText} E=${s.isExplicit} ${lyr(s.videoId)}") }
        }
        say("nQAVBn0kNs4 ${lyr("nQAVBn0kNs4")}")
    }
}
