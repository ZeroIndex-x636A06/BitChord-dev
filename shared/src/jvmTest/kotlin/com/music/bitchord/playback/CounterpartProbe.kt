package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.test.Test

/** TEMPORARY live probe — never upstream. Runs only with PROBE=1. */
class CounterpartProbe {
    private fun say(line: String) { println(line); File("probe-out.txt").appendText(line + "\n") }

    private fun JsonElement?.obj(k: String): JsonElement? = (this as? JsonObject)?.get(k)
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun runs(e: JsonElement?): String =
        ((e.obj("runs") as? JsonArray)?.joinToString("") { it.obj("text").str().orEmpty() }).orEmpty()

    private fun walk(e: JsonElement?, key: String, out: MutableList<JsonObject>) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> if (k == key && v is JsonObject) out += v else walk(v, key, out) }
            is JsonArray -> e.forEach { walk(it, key, out) }
            else -> {}
        }
    }

    private fun type(r: JsonObject): String =
        r.obj("navigationEndpoint").obj("watchEndpoint").obj("watchEndpointMusicSupportedConfigs")
            .obj("watchEndpointMusicConfig").obj("musicVideoType").str() ?: "?"

    private suspend fun probeList(label: String, playlistId: String) {
        val t0 = System.currentTimeMillis()
        val r = runCatching { Innertube.nextOfPlaylist(playlistId) }
        val ms = System.currentTimeMillis() - t0
        val json = r.getOrElse { say("$label $playlistId: FAILED ${it.message}"); return }
        val wrappers = mutableListOf<JsonObject>(); walk(json, "playlistPanelVideoWrapperRenderer", wrappers)
        val plain = mutableListOf<JsonObject>(); walk(json, "playlistPanelVideoRenderer", plain)
        val conts = mutableListOf<JsonObject>(); walk(json, "nextContinuationData", conts)
        val conts2 = mutableListOf<JsonObject>(); walk(json, "nextRadioContinuationData", conts2)
        say("$label $playlistId: ${ms}ms panelVideos=${plain.size} wrappers=${wrappers.size} cont=${conts.size} radioCont=${conts2.size}")
        wrappers.take(6).forEach { w ->
            val primary = w.obj("primaryRenderer").obj("playlistPanelVideoRenderer") as? JsonObject
            val cp = ((w.obj("counterpart") as? JsonArray)?.firstOrNull().obj("counterpartRenderer")
                .obj("playlistPanelVideoRenderer")) as? JsonObject
            say("  P ${primary?.obj("videoId").str()} ${primary?.let(::type)} '${runs(primary?.obj("title"))}' -> " +
                "C ${cp?.obj("videoId").str()} ${cp?.let(::type)} '${runs(cp?.obj("title"))}' len=${runs(cp?.obj("lengthText"))}")
        }
        say("  types=${plain.groupingBy { type(it) }.eachCount()}")
        (conts.firstOrNull() ?: conts2.firstOrNull())?.obj("continuation").str()?.let { token ->
            val t1 = System.currentTimeMillis()
            val more = runCatching { Innertube.nextOfPlaylist(playlistId, token) }
            more.exceptionOrNull()?.let { say("  page2 FAILED ${it.message}") }
            val p2 = mutableListOf<JsonObject>(); walk(more.getOrNull(), "playlistPanelVideoRenderer", p2)
            val w2 = mutableListOf<JsonObject>(); walk(more.getOrNull(), "playlistPanelVideoWrapperRenderer", w2)
            say("  page2 ${System.currentTimeMillis() - t1}ms panelVideos=${p2.size} wrappers=${w2.size}")
        }
    }

    private suspend fun variant(label: String, block: suspend () -> JsonObject) {
        val json = runCatching { block() }.getOrElse { say("$label FAILED ${it.message}"); return }
        val wrappers = mutableListOf<JsonObject>(); walk(json, "playlistPanelVideoWrapperRenderer", wrappers)
        val plain = mutableListOf<JsonObject>(); walk(json, "playlistPanelVideoRenderer", plain)
        val cps = mutableListOf<JsonObject>(); walk(json, "counterpart", cps)
        val text = json.toString()
        say("$label: panel=${plain.size} wrappers=${wrappers.size} counterpartKey=${text.contains("counterpart")} len=${text.length}")
        wrappers.take(3).forEach { w ->
            val cp = ((w.obj("counterpart") as? JsonArray)?.firstOrNull().obj("counterpartRenderer").obj("playlistPanelVideoRenderer")) as? JsonObject
            say("   -> ${cp?.obj("videoId").str()} '${runs(cp?.obj("title"))}'")
        }
    }

    @Test
    fun probe() = runBlocking {
        if (System.getenv("PROBE") == "1") {
            File("probe-out.txt").delete()
            val pl = "PLz8cxnYPx0tkfCi6drRRKALyIl2sJjEwq"
            val first = "ru0K8uYEZWw"
            variant("pl audio") { Innertube.nextProbe(null, pl, true, false) }
            variant("pl video") { Innertube.nextProbe(null, pl, false, false) }
            variant("pl anon audio") { Innertube.nextProbe(null, pl, true, true) }
            variant("pl anon video") { Innertube.nextProbe(null, pl, false, true) }
            variant("vid+pl video") { Innertube.nextProbe(first, pl, false, false) }
            variant("radio video") { Innertube.nextProbe(first, "RDAMVM$first", false, false) }
            variant("radio audio") { Innertube.nextProbe(first, "RDAMVM$first", true, false) }
            variant("vid only video") { Innertube.nextProbe(first, null, false, false) }
            return@runBlocking
        }

        if (System.getenv("PROBE") != "1") return@runBlocking
        File("probe-out.txt").delete()
        for (q in listOf("90s music videos", "best music videos 2010s", "eminem music videos")) {
            val rows = YtMusicRepository.search(q, SearchFilter.PLAYLISTS).getOrNull().orEmpty()
            val ids = rows.mapNotNull { (it as? SearchResult.Browse)?.item }.take(2)
            say("query '$q': ${ids.map { it.title + " " + it.browseId }}")
            ids.forEach { probeList(it.title, it.browseId.removePrefix("VL")) }
        }
    }
}
