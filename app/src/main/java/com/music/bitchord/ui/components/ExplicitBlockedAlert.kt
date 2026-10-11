package com.music.bitchord.ui.components

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

private const val ENTER_MS = 220
private const val EXIT_MS = 160

/** What the listener chose when an explicit song was blocked. */
enum class ExplicitBlockedChoice { ALLOW_SONG, TURN_OFF_BLOCKING, CANCEL }

/**
 * Asks what to do about a song the explicit filter is blocking: allow this song
 * from now on, turn blocking off, or leave it.
 *
 * The same frosted card as the app's other alerts, but animated in and out —
 * the scrim fades and the card settles from slightly larger, the way a system
 * alert does — so it arrives the way the app's sheets do instead of popping.
 * The choice is only reported once the exit animation has finished, so the
 * song doesn't start under a card that is still on screen.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ExplicitBlockedAlert(
    song: Song,
    hazeState: HazeState,
    onChoice: (ExplicitBlockedChoice) -> Unit,
) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    var pending by remember { mutableStateOf<ExplicitBlockedChoice?>(null) }
    val choose: (ExplicitBlockedChoice) -> Unit = { choice ->
        if (pending == null) {
            pending = choice
            visible.targetState = false
        }
    }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        val choice = pending
        if (choice != null && visible.isIdle && !visible.currentState) onChoice(choice)
    }

    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(ALERT_CORNER)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(ENTER_MS)),
            exit = fadeOut(tween(EXIT_MS)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SCRIM_COLOR)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { choose(ExplicitBlockedChoice.CANCEL) },
                    ),
            )
        }
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(ENTER_MS)) + scaleIn(tween(ENTER_MS), initialScale = 1.08f),
            exit = fadeOut(tween(EXIT_MS)) + scaleOut(tween(EXIT_MS), targetScale = 0.96f),
        ) {
            Column(
                modifier = Modifier
                    .width(ALERT_WIDTH)
                    .clip(shape)
                    .then(
                        if (reduceDynamicBlur) {
                            Modifier.background(MaterialTheme.colorScheme.surface)
                        } else {
                            Modifier.optimizedHazeEffect(
                                state = hazeState,
                                style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                            )
                        },
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 19.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.explicit_song_blocked),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(
                            // Says which check stopped it: a song with no E in the
                            // catalogue was caught by its lyrics.
                            if (song.isExplicit == true) {
                                R.string.explicit_song_blocked_message
                            } else {
                                R.string.explicit_lyrics_blocked_message
                            },
                            song.title,
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
                AlertRule()
                AlertAction(
                    label = stringResource(R.string.allow_this_song),
                    emphasised = true,
                    onClick = { choose(ExplicitBlockedChoice.ALLOW_SONG) },
                )
                AlertRule()
                AlertAction(
                    label = stringResource(R.string.turn_off_blocking),
                    emphasised = false,
                    onClick = { choose(ExplicitBlockedChoice.TURN_OFF_BLOCKING) },
                )
                AlertRule()
                AlertAction(
                    label = stringResource(R.string.cancel),
                    emphasised = false,
                    onClick = { choose(ExplicitBlockedChoice.CANCEL) },
                )
            }
        }
    }
}
