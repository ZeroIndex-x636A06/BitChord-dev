package com.music.bitchord.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Explicit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AllowedExplicitSong
import com.music.bitchord.data.settings.ExplicitAllowList
import kotlinx.coroutines.delay

/** How long "Clear all" waits for its confirming second tap before standing down. */
private const val CLEAR_CONFIRM_WINDOW_MS = 4_000L

/**
 * Settings → Allowed explicit songs: every song let past the explicit filter,
 * removable one at a time. A Settings page rather than an alert, like the
 * Equalizer, because the list only grows and an alert card has no room for it.
 *
 * Drawn in Settings' own grouped-card style but laid out lazily: the card is
 * rebuilt row by row (rounded ends on the first and last), so a list of
 * hundreds costs only what is on screen.
 *
 * Removing a song blocks it again straight away, including the playing track —
 * see `PlaybackService.dropBlockedExplicitFromQueue`.
 */
@Composable
fun AllowedExplicitSongsScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val songs by ExplicitAllowList.songs.collectAsStateWithLifecycle()
    var confirmingClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmingClear) {
        if (confirmingClear) {
            delay(CLEAR_CONFIRM_WINDOW_MS)
            confirmingClear = false
        }
    }

    if (songs.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.Explicit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.allowed_explicit_songs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "intro") {
            Text(
                text = stringResource(R.string.allowed_explicit_songs_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = GROUP_INSET + 4.dp,
                    end = GROUP_INSET + 4.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
            )
        }
        itemsIndexed(songs, key = { _, entry -> entry.videoId }) { index, entry ->
            val shape = when {
                songs.size == 1 -> GroupShape
                index == 0 -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                index == songs.lastIndex -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                else -> RoundedCornerShape(0.dp)
            }
            Column(
                modifier = Modifier
                    .animateItem()
                    .padding(horizontal = GROUP_INSET)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AllowedSongRow(entry = entry, onRemove = { ExplicitAllowList.remove(entry) })
                if (index != songs.lastIndex) RowDivider()
            }
        }
        item(key = "clear") {
            Spacer(Modifier.height(26.dp))
            Box(
                modifier = Modifier
                    .padding(horizontal = GROUP_INSET)
                    .clip(GroupShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .animateContentSize(),
            ) {
                // Two taps, the second within a few seconds: it's the one action
                // here that can't be undone a song at a time.
                SettingsRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = if (confirmingClear) {
                        pluralStringResource(R.plurals.clear_all_allowed_explicit, songs.size, songs.size)
                    } else {
                        stringResource(R.string.clear_all)
                    },
                    subtitle = if (confirmingClear) stringResource(R.string.tap_again_to_confirm) else null,
                    onClick = {
                        if (confirmingClear) {
                            ExplicitAllowList.clear()
                            confirmingClear = false
                        } else {
                            confirmingClear = true
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AllowedSongRow(entry: AllowedExplicitSong, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(start = ROW_INSET, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        AsyncImage(
            model = entry.thumbnailUrl.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surface),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.block_explicit_song),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
