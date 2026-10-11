package com.music.bitchord.data.settings

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.ExplicitFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One song the listener let past the explicit filter.
 *
 * Kept readable — title, credit, artwork — so the list can be shown and pruned
 * a song at a time, not only cleared wholesale. [keys] are what the filter
 * actually matches on (see [ExplicitFilter.allowKeys]), stored as they were
 * when the song was allowed so a later change to how keys are built can't
 * quietly un-allow anything.
 */
@Serializable
data class AllowedExplicitSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val keys: List<String> = emptyList(),
    val allowedAtMillis: Long = 0,
)

/**
 * The explicit filter's per-song exceptions, newest first, as state.
 *
 * Shared by the phone and desktop apps; each supplies only where the list is
 * kept. [persist] is handed the list encoded as JSON after every change (or
 * null when it becomes empty), and [load] takes back what was stored.
 *
 * Nothing expires. An allowed song stays allowed until the listener removes it.
 */
class ExplicitAllowListStore(private val persist: (String?) -> Unit) {

    private val _songs = MutableStateFlow<List<AllowedExplicitSong>>(emptyList())
    val songs: StateFlow<List<AllowedExplicitSong>> = _songs.asStateFlow()

    private val _keys = MutableStateFlow<Set<String>>(emptySet())

    /** Every key in [songs], flattened — the set [ExplicitFilter] looks songs up in. */
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    /** Restores a stored list. Unreadable data loads as empty rather than failing startup. */
    fun load(json: String?) = publish(decode(json), save = false)

    fun allow(song: Song, now: Long = System.currentTimeMillis()) =
        publish(ExplicitAllowListOps.withSong(_songs.value, song, now))

    /** Un-allows [song], wherever it was allowed from — see [ExplicitAllowListOps.withoutSong]. */
    fun remove(song: Song) = publish(ExplicitAllowListOps.withoutSong(_songs.value, song))

    fun remove(entry: AllowedExplicitSong) = publish(_songs.value.filterNot { it.videoId == entry.videoId })

    fun clear() = publish(emptyList())

    /** For a backup restore, which replaces rather than merges, like the rest of the settings. */
    fun replaceAll(entries: List<AllowedExplicitSong>) = publish(entries)

    private fun publish(next: List<AllowedExplicitSong>, save: Boolean = true) {
        _songs.value = next
        _keys.value = ExplicitAllowListOps.keysOf(next)
        if (save) persist(if (next.isEmpty()) null else encode(next))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(AllowedExplicitSong.serializer())

        fun encode(list: List<AllowedExplicitSong>): String = json.encodeToString(serializer, list)

        fun decode(stored: String?): List<AllowedExplicitSong> =
            stored?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    }
}

/**
 * The list operations behind [ExplicitAllowListStore], kept apart from it so
 * they can be tested on their own.
 */
object ExplicitAllowListOps {
    /** [list] with [song] at the front. A song already covered is left where it is. */
    fun withSong(list: List<AllowedExplicitSong>, song: Song, now: Long): List<AllowedExplicitSong> {
        val keys = ExplicitFilter.allowKeys(song)
        if (list.any { entry -> entry.keys.any { it in keys } }) return list
        val entry = AllowedExplicitSong(
            videoId = song.videoId,
            title = song.title,
            artist = song.artist,
            thumbnailUrl = song.thumbnailUrl,
            keys = keys.toList(),
            allowedAtMillis = now,
        )
        return listOf(entry) + list
    }

    /**
     * [list] without anything that lets [song] through. Matched on keys rather
     * than id, so un-allowing a song from its album page also removes the entry
     * made when it was allowed from search under another id.
     */
    fun withoutSong(list: List<AllowedExplicitSong>, song: Song): List<AllowedExplicitSong> {
        val keys = ExplicitFilter.allowKeys(song)
        return list.filterNot { entry -> entry.keys.any { it in keys } }
    }

    fun keysOf(list: List<AllowedExplicitSong>): Set<String> = list.flatMapTo(HashSet()) { it.keys }
}
