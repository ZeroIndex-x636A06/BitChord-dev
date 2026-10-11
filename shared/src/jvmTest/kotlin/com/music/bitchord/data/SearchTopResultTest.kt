package com.music.bitchord.data

import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.SearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shapes taken from YouTube Music's answer to "rap god" (October 2026): the
 * promoted card is Eminem's official video, and the song itself is an
 * ordinary row further down.
 */
class SearchTopResultTest {

    private fun artistRun(name: String, id: String) = """
        { "text": "$name", "navigationEndpoint": { "browseEndpoint": {
          "browseId": "$id", "browseEndpointContextSupportedConfigs": {
            "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ARTIST" } } } } }
    """

    private fun thumb(w: Int, h: Int) = """
        "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [
          { "url": "https://i.ytimg.com/x.jpg", "width": $w, "height": $h } ] } } }
    """

    private val videoCard = """
        { "musicCardShelfRenderer": {
          "title": { "runs": [{ "text": "Rap God" }] },
          "subtitle": { "runs": [
            { "text": "Video" }, { "text": " • " }, ${artistRun("Eminem", "UC_EMINEM")},
            { "text": " • " }, { "text": "1.5B views" }, { "text": " • " }, { "text": "6:10" } ] },
          "onTap": { "watchEndpoint": { "videoId": "XbGs_qK2PQA" } },
          ${thumb(400, 225)}
        } }
    """

    private val songCard = """
        { "musicCardShelfRenderer": {
          "title": { "runs": [{ "text": "Rap God" }] },
          "subtitle": { "runs": [
            { "text": "Song" }, { "text": " • " }, ${artistRun("Eminem", "UC_EMINEM")},
            { "text": " • " }, { "text": "6:04" } ] },
          "onTap": { "watchEndpoint": { "videoId": "MBJFPq2Llps" } },
          ${thumb(544, 544)}
        } }
    """

    private fun songRow(id: String, title: String, artist: String, artistId: String) = """
        { "musicResponsiveListItemRenderer": {
          "playlistItemData": { "videoId": "$id" },
          ${thumb(120, 120)},
          "flexColumns": [
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "$title" }] } } },
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [
              { "text": "Song" }, { "text": " • " }, ${artistRun(artist, artistId)} ] } } },
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "2B plays" }] } } }
          ]
        } }
    """

    private val eminemSong = songRow("MBJFPq2Llps", "Rap God", "Eminem", "UC_EMINEM")
    private val montanaSong = songRow("JfdXRXIdSu4", "Rap God", "Montana Of 300", "UC_MONTANA")
    private val otherSong = songRow("44E0yQ8U4Ug", "God Of Rap", "Afu-Ra", "UC_AFURA")

    private fun page(card: String, vararg rows: String): InnertubeParser.SearchPage {
        val json = """
            { "contents": { "tabbedSearchResultsRenderer": { "tabs": [{ "tabRenderer": { "content": {
              "sectionListRenderer": { "contents": [
                $card,
                { "itemSectionRenderer": { "contents": [ ${rows.joinToString(",")} ] } }
              ] } } } }] } } }
        """
        return InnertubeParser.parseSearchPage(Json.parseToJsonElement(json).jsonObject)
    }

    private fun List<SearchResult>.ids() = map {
        when (it) {
            is SearchResult.TopTrack -> "top:${it.song.videoId}"
            is SearchResult.Track -> it.song.videoId
            is SearchResult.Browse -> it.item.browseId
        }
    }

    @Test
    fun `a promoted video is kept aside rather than lost`() {
        val page = page(videoCard, eminemSong, otherSong)
        assertEquals("XbGs_qK2PQA", page.topVideo?.videoId)
        assertTrue(page.rows.none { it is SearchResult.TopTrack })
    }

    @Test
    fun `the video's song on the page becomes the top result, listed once`() {
        val page = page(videoCard, otherSong, eminemSong)
        val rows = assertNotNull(SearchTopResult.promoteFromPage(page.rows, page.topVideo!!))
        assertEquals(listOf("top:MBJFPq2Llps", "44E0yQ8U4Ug"), rows.ids())
    }

    @Test
    fun `a same-named song by someone else is not promoted`() {
        val page = page(videoCard, montanaSong, otherSong)
        assertNull(SearchTopResult.promoteFromPage(page.rows, page.topVideo!!))
    }

    @Test
    fun `a page without the song asks for a lookup`() {
        val page = page(videoCard, otherSong)
        assertNull(SearchTopResult.promoteFromPage(page.rows, page.topVideo!!))
        val looked = SearchTopResult.withTopResult(page.rows, page(songCard).rows.single().let {
            (it as SearchResult.TopTrack).song
        })
        assertEquals(listOf("top:MBJFPq2Llps", "44E0yQ8U4Ug"), looked.ids())
    }

    @Test
    fun `a page whose top result is already a song is left alone`() {
        val page = page(songCard, eminemSong, otherSong)
        assertNull(page.topVideo)
        assertEquals(listOf("top:MBJFPq2Llps", "44E0yQ8U4Ug"), page.rows.ids())
    }
}
