package com.dzirbel.kotify.ui.page.tracks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dzirbel.kotify.ui.components.InvalidateButton
import com.dzirbel.kotify.ui.components.PageLoadingSpinner
import com.dzirbel.kotify.ui.components.table.Table
import com.dzirbel.kotify.ui.theme.Dimens

@Composable
fun TracksPageHeader(presenter: TracksPresenter, state: TracksPresenter.ViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Dimens.space4),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Tracks", style = MaterialTheme.typography.h5)

        Column {
            InvalidateButton(
                refreshing = state.refreshing,
                updated = state.tracksUpdated,
                onClick = { presenter.emitAsync(TracksPresenter.Event.Load(invalidate = true)) }
            )
        }
    }
}

@Composable
fun TracksPageContent(state: TracksPresenter.ViewModel) {
    if (state.tracks.hasElements) {
        // TODO find the context to play tracks from the list of all saved tracks
        Table(
            columns = state.trackProperties,
            items = state.tracks,
            onSetSort = {
                // TODO allow sorting saved tracks list
            }
        )
    } else {
        PageLoadingSpinner()
    }
}
