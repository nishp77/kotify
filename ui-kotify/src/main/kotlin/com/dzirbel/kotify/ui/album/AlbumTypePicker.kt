package com.dzirbel.kotify.ui.album

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import com.dzirbel.kotify.db.model.AlbumType
import com.dzirbel.kotify.ui.CachedIcon
import com.dzirbel.kotify.ui.components.HorizontalSpacer
import com.dzirbel.kotify.ui.components.ToggleButtonGroup
import com.dzirbel.kotify.ui.theme.Dimens
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.toImmutableList

@Composable
fun AlbumTypePicker(
    albumTypeCounts: ImmutableMap<AlbumType?, Int>?,
    albumTypes: PersistentSet<AlbumType>,
    onSelectAlbumTypes: (PersistentSet<AlbumType>) -> Unit,
) {
    ToggleButtonGroup(
        elements = AlbumType.entries.toImmutableList(),
        selectedElements = albumTypes,
        onSelectElements = onSelectAlbumTypes,
        content = { albumType ->
            CachedIcon(name = albumType.iconName, size = Dimens.iconSmall)

            HorizontalSpacer(width = Dimens.space2)

            val count = albumTypeCounts?.let { it[albumType] ?: 0 }
            if (count == null) {
                Text(albumType.displayName)
            } else {
                Text("${albumType.displayName} ($count)")
            }
        },
    )
}
