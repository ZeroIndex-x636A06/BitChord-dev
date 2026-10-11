package com.music.bitchord.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song

fun Modifier.thumbnailBorder(shape: Shape): Modifier = composed {
    this.border(
        width = 1.dp,
        color = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.15f),
        shape = shape
    )
}

/** The catalogue-standard outlined E for explicit audio, drawn in [color]. */
@Composable
fun ExplicitBadge(color: Color, modifier: Modifier = Modifier) {
    Text(
        text = "E",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.72f), RoundedCornerShape(2.dp))
            .padding(horizontal = 3.dp),
    )
}

/**
 * The outlined "C" for an explicit track that will play as its clean release —
 * see [LocalExplicitSwapped]. Fainter than the "E": it's a note, not a warning.
 */
@Composable
fun CleanSwapBadge(color: Color, modifier: Modifier = Modifier) {
    val faded = color.copy(alpha = color.alpha * CLEAN_BADGE_ALPHA)
    Text(
        text = "C",
        style = MaterialTheme.typography.labelSmall,
        color = faded,
        modifier = modifier
            .border(1.dp, faded.copy(alpha = faded.alpha * 0.72f), RoundedCornerShape(2.dp))
            .padding(horizontal = 3.dp),
    )
}

/** The "E", or the "C" when the track is going to be swapped for its clean release. */
@Composable
fun ExplicitMark(swapped: Boolean, color: Color, modifier: Modifier = Modifier) {
    if (swapped) CleanSwapBadge(color, modifier) else ExplicitBadge(color, modifier)
}

private const val CLEAN_BADGE_ALPHA = 0.6f

/** A song title with the catalogue-standard outlined E for explicit audio. */
@Composable
fun ExplicitSongTitle(
    song: Song,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (song.showsExplicit()) {
            ExplicitMark(LocalExplicitSwapped.current(song), color)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = song.title,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
