package probe

import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.CleanVersionMatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

const val SONGS = "EgWKAQIIAWoKEAkQChAFEAMQBA=="
const val VIDEOS = "EgWKAQIQAWoKEAkQChAFEAMQBA=="
val http: HttpClient = HttpClient.newHttpClient()

fun search(query: String, params: String?): List<Song> {
    val q = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(query))
    val p = params?.let { ""","params":"$it"""" } ?: ""
    val body = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20251001.01.00","hl":"en","gl":"US"}},"query":$q$p}"""
    val req = HttpRequest.newBuilder(URI("https://music.youtube.com/youtubei/v1/search?prettyPrint=false"))
        .header("Content-Type", "application/json").header("Origin", "https://music.youtube.com")
        .header("X-YouTube-Client-Name", "67").header("X-YouTube-Client-Version", "1.20251001.01.00")
        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build()
    val text = http.send(req, HttpResponse.BodyHandlers.ofString()).body()
    val page = InnertubeParser.parseSearchPage(Json.parseToJsonElement(text).jsonObject, includeVideos = params == VIDEOS)
    return page.rows.mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
}

fun Song.line() = "$title — $artist — ${if (isExplicit == true) "E" else "-"}${if (isVideo) "V" else "-"} ${durationText ?: "?"} [$videoId]"

fun main(args: Array<String>) {
    for (line in File(args[0]).readLines().filter { it.isNotBlank() }) {
        val (title, artist) = line.split("|")
        val out = StringBuilder("### $title — $artist\n")
        val first = search("$title $artist", SONGS)
        val explicit = first.firstOrNull { it.isExplicit == true && TrackMatcher.sharesArtist(artist, it.artist) }
        if (explicit == null) {
            out.append("no explicit row; top: ").append(first.take(3).joinToString(" | ") { it.line() }).append("\n")
        } else {
            out.append("explicit: ${explicit.line()}\n")
            val queries = TrackMatcher.queries(TrackMatcher.targetOf(explicit)).take(2).map { it to SONGS } +
                listOf("${explicit.title} ${artist} clean" to SONGS, "${explicit.title} ${artist} clean" to VIDEOS)
            for ((q, f) in queries) {
                val got = if (q == "$title $artist" && f == SONGS) first else search(q, f)
                val pick = CleanVersionMatcher.pick(explicit, got)
                out.append("  q=\"$q\" ${if (f == SONGS) "SONGS" else "VIDEOS"}: ${got.size} rows; pick=${pick?.line() ?: "none"}\n")
                got.filter { TrackMatcher.sharesArtist(artist, it.artist) || it.title.contains(explicit.title.take(6), ignoreCase = true) }
                    .take(8).forEach { out.append("     · ${it.line()}  core=${CleanVersionMatcher.coreTitle(it.title)}\n") }
            }
        }
        println(out)
        Thread.sleep(400)
    }
}
