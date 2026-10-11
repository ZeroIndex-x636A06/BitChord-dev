package com.music.bitchord.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.delay

private const val ENTER_MS = 200
private const val EXIT_MS = 150

/** What the listener chose when an explicit song was blocked. */
internal enum class DesktopExplicitChoice { ALLOW_SONG, TURN_OFF_BLOCKING, CANCEL }

/**
 * Asks what to do about a song the explicit filter is blocking: allow it from
 * now on, turn blocking off, or leave it. The desktop counterpart of the
 * phone's prompt, in the desktop's own glass card; the scrim fades and the card
 * settles in rather than appearing at once, and the choice is reported only
 * after the card has gone, so playback doesn't start under it.
 */
@Composable
internal fun DesktopExplicitBlockedDialog(
    song: Song,
    onChoice: (DesktopExplicitChoice) -> Unit,
) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    var pending by remember { mutableStateOf<DesktopExplicitChoice?>(null) }
    val choose: (DesktopExplicitChoice) -> Unit = { choice ->
        if (pending == null) {
            pending = choice
            visible.targetState = false
        }
    }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        val choice = pending
        if (choice != null && visible.isIdle && !visible.currentState) onChoice(choice)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(ENTER_MS)), exit = fadeOut(tween(EXIT_MS))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(DesktopScrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { choose(DesktopExplicitChoice.CANCEL) },
                    ),
            )
        }
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(ENTER_MS)) + scaleIn(tween(ENTER_MS), initialScale = 1.05f),
            exit = fadeOut(tween(EXIT_MS)) + scaleOut(tween(EXIT_MS), targetScale = 0.97f),
        ) {
            val shape = RoundedCornerShape(20.dp)
            Column(
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .desktopCard(shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Text(
                    DesktopStrings["d_explicit_song", "Explicit song"],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp),
                )
                Text(
                    DesktopStrings[
                        "d_explicit_song_blocked_message",
                        "“%1\$s” is marked explicit and your filter is blocking it.",
                    ].replace("%1\$s", song.title),
                    style = MaterialTheme.typography.bodySmall,
                    color = DesktopSecondary,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { choose(DesktopExplicitChoice.CANCEL) }) {
                        Text(DesktopStrings["cancel", "Cancel"], color = DesktopSecondary)
                    }
                    TextButton(onClick = { choose(DesktopExplicitChoice.TURN_OFF_BLOCKING) }) {
                        Text(DesktopStrings["d_turn_off_blocking", "Turn off blocking"], color = DesktopSecondary)
                    }
                    TextButton(onClick = { choose(DesktopExplicitChoice.ALLOW_SONG) }) {
                        Text(DesktopStrings["d_allow_this_song", "Allow this song"], color = DesktopAccent)
                    }
                }
            }
        }
    }
}

/** How long "Clear all" waits for its confirming second click. */
private const val CLEAR_CONFIRM_WINDOW_MS = 4_000L

/**
 * Settings → Allowed explicit songs: every song let past the filter, removable
 * one at a time. A settings page like the others, so it opens in place with
 * the same back behaviour.
 */
@Composable
internal fun DesktopAllowedExplicitDialog(onDismiss: () -> Unit) {
    val songs by DesktopExplicit.allowList.songs.collectAsState()
    var confirmingClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmingClear) {
        if (confirmingClear) {
            delay(CLEAR_CONFIRM_WINDOW_MS)
            confirmingClear = false
        }
    }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 520) {
        Column(Modifier.fillMaxWidth().padding(horizontal = panelInset(20.dp), vertical = 18.dp)) {
            Text(
                DesktopStrings["d_allowed_explicit_songs", "Allowed explicit songs"],
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Text(
                if (songs.isEmpty()) {
                    DesktopStrings[
                        "d_allowed_explicit_songs_empty",
                        "No songs allowed yet. Click a greyed-out song and choose Allow, or use “Allow this explicit song” in its menu.",
                    ]
                } else {
                    DesktopStrings[
                        "d_allowed_explicit_songs_description",
                        "These play even when the filter blocks explicit songs. Remove one to block it again.",
                    ]
                },
                style = MaterialTheme.typography.bodyMedium,
                color = DesktopSecondary,
            )
        }
        if (songs.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                items(songs, key = { it.videoId }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = panelInset(14.dp), vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = entry.thumbnailUrl.artworkAt(ROW_ART_PX),
                            contentDescription = null,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.08f)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                entry.artist,
                                color = DesktopSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { DesktopExplicit.allowList.remove(entry) }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = DesktopStrings["d_block_this_explicit_song_again", "Block this explicit song again"],
                                tint = DesktopSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = panelInset(16.dp), vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        if (confirmingClear) {
                            DesktopExplicit.allowList.clear()
                            confirmingClear = false
                        } else {
                            confirmingClear = true
                        }
                    },
                ) {
                    Text(
                        if (confirmingClear) {
                            DesktopStrings["d_click_again_to_remove_all", "Click again to remove all"]
                        } else {
                            DesktopStrings["d_clear_all", "Clear all"]
                        },
                        color = if (confirmingClear) Color(0xFFFF6B6B) else DesktopSecondary,
                    )
                }
            }
        }
    }
}
