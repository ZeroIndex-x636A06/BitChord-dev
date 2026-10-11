package probe

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.CleanVersionMatcher

fun dump(label: String, rows: List<Song>, explicit: Song?, video: Boolean) {
    println("== $label (${rows.size})")
    rows.take(12).forEach { s ->
        val ok = explicit?.let { if (video) CleanVersionMatcher.pickVideo(it, listOf(s)) else CleanVersionMatcher.pick(it, listOf(s)) } != null
        println("  ${if (ok) "OK " else "   "}${s.line()} album=${s.albumName}")
    }
}

fun dumpMain(queries: List<String>) {
    for (q in queries) {
        val (title, artist) = q.split("|")
        val first = search("$title $artist", SONGS)
        dump("SONGS '$title $artist'", first, null, false)
        val explicit = first.firstOrNull { it.isExplicit == true && TrackMatcher.sharesArtist(artist, it.artist) && it.title.startsWith(title, true) }
        println("explicit target: ${explicit?.line()}")
        if (explicit == null) continue
        val name = TrackMatcher.queries(TrackMatcher.targetOf(explicit)).first()
        println("name query: '$name'")
        dump("SONGS '$name'", search(name, SONGS), explicit, false)
        dump("SONGS '$name clean'", search("$name clean", SONGS), explicit, false)
        dump("VIDEOS '$name clean'", search("$name clean", VIDEOS), explicit, true)
        dump("VIDEOS '$title $artist clean version'", search("$title $artist clean version", VIDEOS), explicit, true)
        dump("SONGS '$title $artist edited'", search("$title $artist edited", SONGS), explicit, false)
        Thread.sleep(300)
    }
}
