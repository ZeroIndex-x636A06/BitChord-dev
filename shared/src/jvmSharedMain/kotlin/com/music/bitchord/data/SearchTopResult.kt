package com.music.bitchord.data

import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher

/**
 * Puts the song back at the top of an All search whose promoted result was its
 * music video.
 *
 * Search "rap god" and YouTube Music promotes the official video; search "rap
 * gos" and it promotes something else entirely. The All page keeps videos out,
 * so the exact query — the one that should have worked best — lost its top
 * result and showed a page with no "Top result" card at all, while the typo
 * kept one. Signed in, the song itself is often missing from the rest of that
 * page too, because YouTube does not list the top result twice.
 *
 * The video is still the best clue to what was meant, so its song stands in for
 * it: taken from the page when the page has it, otherwise looked up the same
 * way "Prefer music-only version" finds a video's audio.
 */
object SearchTopResult {

    /**
     * [rows] with [topVideo]'s song moved to the front as the top result, or
     * null when the page holds no song that is that video's.
     */
    fun promoteFromPage(rows: List<SearchResult>, topVideo: Song): List<SearchResult>? {
        if (rows.any { it is SearchResult.TopTrack }) return rows
        val songs = rows.filterIsInstance<SearchResult.Track>().map { it.song }
        val target = TrackMatcher.targetOf(topVideo)
        val match = TrackMatcher.best(songs, target)
            ?: TrackMatcher.bestOfficialAudioForVideo(songs, target)
            ?: return null
        return withTopResult(rows, match)
    }

    /** [rows] led by [song] as the top result, without listing it a second time. */
    fun withTopResult(rows: List<SearchResult>, song: Song): List<SearchResult> =
        listOf(SearchResult.TopTrack(song)) + rows.filterNot {
            (it is SearchResult.Track && it.song.videoId == song.videoId) ||
                it is SearchResult.TopTrack
        }
}
