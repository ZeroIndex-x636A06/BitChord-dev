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
    if (args.size > 1) { dumpMain(java.io.File(args[1]).readLines().filter { it.isNotBlank() }); return }
    var hits = 0; var total = 0
    for (line in File(args[0]).readLines().filter { it.isNotBlank() }) {
        val (title, artist) = line.split("|")
        val first = search("$title $artist", SONGS)
        val explicit = first.firstOrNull { it.isExplicit == true && TrackMatcher.sharesArtist(artist, it.artist) }
        if (explicit == null) { println("NO-E  $title — $artist"); continue }
        total++
        val name = TrackMatcher.queries(TrackMatcher.targetOf(explicit)).first()
        var how = "none"
        var pick: Song? = CleanVersionMatcher.pick(explicit, search(name, SONGS))
        if (pick != null) how = "songs" else {
            pick = CleanVersionMatcher.pick(explicit, search("$name clean", SONGS))
            if (pick != null) how = "songs+clean" else {
                pick = CleanVersionMatcher.pickVideo(explicit, search("$name clean", VIDEOS))
                if (pick != null) how = "video"
            }
        }
        if (pick != null) hits++
        println("${if (pick != null) "FOUND" else "none "} [$how] $title — $artist -> ${pick?.line() ?: "-"}")
        Thread.sleep(300)
    }
    println("found $hits of $total")
}
