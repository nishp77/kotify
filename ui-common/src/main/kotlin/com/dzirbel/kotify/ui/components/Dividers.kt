package com.dzirbel.kotify.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import com.dzirbel.kotify.ui.theme.Dimens
import com.dzirbel.kotify.ui.theme.KotifyColors

/**
 * A divider which has [Dimens.divider] height, fills the maximum width, and uses [color] background color.
 */
@Composable
fun HorizontalDivider(modifier: Modifier = Modifier, color: Color = KotifyColors.current.divider) {
    Layout(
        modifier = modifier.background(color),
        content = {},
        measurePolicy = { _, constraints ->
            layout(width = constraints.maxWidth, height = Dimens.divider.roundToPx()) {}
        },
    )
}

/**
 * A divider which has [Dimens.divider] width, fills the maximum height, and uses [color] background color.
 */
@Composable
fun VerticalDivider(modifier: Modifier = Modifier, color: Color = KotifyColors.current.divider) {
    Layout(
        modifier = modifier.background(color),
        content = {},
        measurePolicy = { _, constraints ->
            layout(width = Dimens.divider.roundToPx(), height = constraints.maxHeight) {}
        },
    )
}
