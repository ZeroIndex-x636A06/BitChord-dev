package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.ExplicitFilter
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.AppUi
import org.jetbrains.compose.resources.stringResource

/** How dim a blocked explicit row is drawn, the same as an unavailable one. */
const val BLOCKED_ROW_ALPHA = 0.45f

/**
 * Whether "Block explicit songs" keeps [song] from playing. Song rows take this
 * as their `explicitBlocked` parameter and are drawn greyed out when it is
 * true, which is only ever the case while blocking is on.
 */
@Composable
fun rememberExplicitBlocked(song: Song): Boolean {
    val enabled by AppUi.host.blockExplicit.collectAsStateWithLifecycle()
    val allowed by AppUi.host.explicitAllowed.collectAsStateWithLifecycle()
    return ExplicitFilter.blocks(song, enabled, allowed)
}

/**
 * A song row's tap. Plays as usual, unless [blocked]: then it asks first, with
 * Allow this song or Turn off blocking, and plays after either.
 */
@Composable
fun explicitGatedClick(song: Song, blocked: Boolean, onClick: () -> Unit): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        val play = { choice: () -> Unit ->
            asking = false
            choice()
            onClick()
        }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(Res.string.explicit_song_blocked)) },
            text = { Text(stringResource(Res.string.explicit_song_blocked_message, song.title)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { play { AppUi.host.setBlockExplicit(false) } }) {
                        Text(stringResource(Res.string.turn_off_blocking))
                    }
                    TextButton(onClick = { play { AppUi.host.setExplicitAllowed(song.videoId, true) } }) {
                        Text(stringResource(Res.string.allow_this_song))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
    return if (blocked) { { asking = true } } else onClick
}
