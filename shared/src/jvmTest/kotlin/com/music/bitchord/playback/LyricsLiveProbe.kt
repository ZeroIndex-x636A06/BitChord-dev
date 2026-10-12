package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/** TEMPORARY live probe — never upstream. Runs only with PROBE=1. */
class LyricsLiveProbe {

    private val out = StringBuilder()
    private fun say(line: String) { println(line); out.appendLine(line); File("probe-out.txt").appendText(line + "\n") }

    /** The real source, reporting what each phase saw. */
    private class Watching(val real: ExplicitLyricsSource) : ExplicitLyricsSource {
        var exactScore: String = "-"
        val others = mutableListOf<Int>()
        var namesakes: String = "-"
        var t0 = 0L
        var firstOtherMs = -1L
        override suspend fun exact(song: Song): List<String>? = real.exact(song).also {
            exactScore = if (it == null) "none" else "${ExplicitLyrics.score(it.joinToString("\n"), countMasked = false)}"
        }
        override fun others(song: Song): Flow<List<String>> = real.others(song).onEach {
            if (firstOtherMs < 0) firstOtherMs = System.currentTimeMillis() - t0
            others += ExplicitLyrics.score(it.joinToString("\n"))
        }
        override suspend fun sameTitle(song: Song): List<List<String>> = real.sameTitle(song).also { sets ->
            namesakes = sets.joinToString(",") { ExplicitLyrics.score(it.joinToString("\n")).toString() }
        }
    }

    @Test
    fun probe() = runBlocking {
        if (System.getenv("PROBE") != "1") return@runBlocking
        val queries = File(System.getenv("PROBE_QUERIES")).readLines().filter { it.isNotBlank() }
        var over = 0; var total = 0
        for (q in queries) {
            val rows = YtMusicRepository.search(q, SearchFilter.SONGS).getOrNull().orEmpty()
                .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
            say("## $q")
            for (song in rows.filter { it.isExplicit != true }.take(5)) {
                val watching = Watching(StandardExplicitLyrics { LyricsSource.offered.toSet() })
                val checker = ExplicitLyricsChecker(ExplicitLyricsStore { }, watching)
                watching.t0 = System.currentTimeMillis()
                val verdict = checker.check(song)
                val ms = System.currentTimeMillis() - watching.t0
                total++; if (ms > 2_500) over++
                val phase = when {
                    watching.exactScore != "none" && watching.exactScore != "-" -> "own"
                    watching.others.isNotEmpty() -> "others"
                    watching.namesakes != "-" -> "namesakes"
                    else -> "?"
                }
                say("- ${verdictMark(verdict)} ${song.title} — ${song.artist} ${song.durationText} | by $phase in ${ms}ms | own=${watching.exactScore} others=${watching.others} (first ${watching.firstOtherMs}ms) namesakes=[${watching.namesakes}]")
            }
        }
        say("over 2.5s: $over of $total")
        File("probe-out.txt").writeText(out.toString())
    }

    private fun verdictMark(v: Boolean?) = when (v) { true -> "BLOCK"; false -> "play "; null -> "NONE " }
}
