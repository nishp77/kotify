package com.dzirbel.kotify.ui.components.grid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationConstants
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.dzirbel.kotify.ui.components.adapter.ListAdapter
import com.dzirbel.kotify.ui.theme.Dimens
import com.dzirbel.kotify.ui.theme.KotifyColors
import com.dzirbel.kotify.ui.util.instrumentation.instrument
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A [Shape] consisting of a rounded rectangle with no line along the bottom and the bottom rounding flares outwards
 * instead of rounding in. May also have an optional extra [bottomPadding].
 */
private class FlaredBottomRoundedRect(val cornerSize: Dp, val bottomPadding: Dp = 0.dp) : Shape {
    @Suppress("MagicNumber")
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cornerSizePx = with(density) { cornerSize.toPx() }
        val height = size.height - with(density) { bottomPadding.toPx() }

        val path = Path().apply {
            moveTo(x = 0f, y = height)

            val bottomLeftRect = Rect(
                center = Offset(x = 0f, y = height - cornerSizePx),
                radius = cornerSizePx,
            )
            arcTo(rect = bottomLeftRect, startAngleDegrees = 90f, sweepAngleDegrees = -90f, forceMoveTo = false)

            lineTo(x = cornerSizePx, y = cornerSizePx)

            val topLeftRect = Rect(center = Offset(x = 2 * cornerSizePx, y = cornerSizePx), radius = cornerSizePx)
            arcTo(rect = topLeftRect, startAngleDegrees = 180f, sweepAngleDegrees = 90f, forceMoveTo = false)

            lineTo(x = size.width - cornerSizePx * 2, y = 0f)

            val topRightRect = Rect(
                center = Offset(x = size.width - 2 * cornerSizePx, y = cornerSizePx),
                radius = cornerSizePx,
            )
            arcTo(rect = topRightRect, startAngleDegrees = 270f, sweepAngleDegrees = 90f, forceMoveTo = false)

            lineTo(x = size.width - cornerSizePx, y = height - cornerSizePx)

            val bottomRightRect = Rect(
                center = Offset(x = size.width, y = height - cornerSizePx),
                radius = cornerSizePx,
            )
            arcTo(rect = bottomRightRect, startAngleDegrees = 180f, sweepAngleDegrees = -90f, forceMoveTo = false)
        }

        return Outline.Generic(path)
    }
}

/**
 * A simple two-dimensional grid layout, which arranges [cellContent] for each [elements] as a table.
 *
 * The layout always expands vertically to fit all the [elements]. Each column has the width of the widest
 * [cellContent] in that column; each row the height of the tallest [cellContent] in that row. The number of columns
 * will equal [columns] if provided, otherwise it will be the maximum number of contents that can fit.
 *
 * [horizontalSpacing] and [verticalSpacing] will be added between columns and rows, respectively, including before the
 * first row/column and after the last row/column.
 *
 * A [selectedElementIndex] may be provided along with [detailInsertContent] to display an insert below the row of the
 * [selectedElementIndex] with some extra content for it. This corresponds to the index of the selected element in the
 * canonical order of [elements].
 */
@Composable
@Suppress("UnsafeCallOnNullableType")
fun <E> Grid(
    elements: ListAdapter<E>,
    modifier: Modifier = Modifier,
    selectedElementIndex: Int? = null,
    horizontalSpacing: Dp = Dimens.space2,
    verticalSpacing: Dp = Dimens.space2,
    edgePadding: PaddingValues = PaddingValues(horizontal = horizontalSpacing, vertical = verticalSpacing),
    cellAlignment: Alignment = Alignment.TopCenter,
    columns: Int? = null,
    detailInsertAnimationDurationMs: Int = AnimationConstants.DefaultDurationMillis,
    detailInsertCornerSize: Dp = Dimens.cornerSize * 2,
    detailInsertElevation: Dp = Dimens.componentElevation * 2,
    detailInsertContent: @Composable ((elementIndex: Int, element: E) -> Unit)? = null,
    cellContent: @Composable (elementIndex: Int, element: E) -> Unit,
) {
    require(columns == null || columns > 0) { "columns must be positive; got $columns" }

    val layoutDirection = LocalLayoutDirection.current

    val insertAnimationState = remember { MutableTransitionState(selectedElementIndex != null) }
    insertAnimationState.targetState = selectedElementIndex != null

    // keeps track of the last selected element (or null if an element has never been selected) - this is used to
    // continue displaying it as it is being animated out
    val lastSelectedElementIndexState = remember { mutableStateOf(selectedElementIndex) }
    if (selectedElementIndex != null) {
        lastSelectedElementIndexState.value = selectedElementIndex
    }
    val lastSelectedElementIndex: Int? = lastSelectedElementIndexState.value

    val divisions = elements.divisions

    Layout(
        content = {
            elements.forEachIndexed { index, element ->
                cellContent(index, element)
            }

            elements.divider?.let { divider ->
                divisions.forEach { division ->
                    Box {
                        divider.dividableProperty.DivisionHeader(division = division.key)
                    }
                }
            }

            if (detailInsertContent != null && lastSelectedElementIndex != null) {
                AnimatedVisibility(
                    visibleState = insertAnimationState,
                    enter = fadeIn(animationSpec = tween(durationMillis = detailInsertAnimationDurationMs)),
                    exit = fadeOut(animationSpec = tween(durationMillis = detailInsertAnimationDurationMs)),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = FlaredBottomRoundedRect(cornerSize = detailInsertCornerSize),
                        border = BorderStroke(width = Dimens.divider, color = KotifyColors.current.divider),
                        elevation = detailInsertElevation,
                        content = {},
                    )
                }

                // TODO small shadow on top of the selected item above
                AnimatedVisibility(
                    visibleState = insertAnimationState,
                    enter = expandVertically(
                        animationSpec = tween(durationMillis = detailInsertAnimationDurationMs),
                        expandFrom = Alignment.Top,
                    ) + fadeIn(animationSpec = tween(durationMillis = detailInsertAnimationDurationMs)),
                    exit = shrinkVertically(
                        animationSpec = tween(durationMillis = detailInsertAnimationDurationMs),
                        shrinkTowards = Alignment.Top,
                    ) + fadeOut(animationSpec = tween(durationMillis = detailInsertAnimationDurationMs)),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = detailInsertElevation,
                        border = BorderStroke(width = Dimens.divider, color = KotifyColors.current.divider),
                    ) {
                        detailInsertContent(lastSelectedElementIndex, elements[lastSelectedElementIndex]!!)
                    }
                }
            }
        },
        modifier = modifier.instrument(),
        measurePolicy = { measurables, constraints ->
            val cellMeasurables = measurables.subList(fromIndex = 0, toIndex = elements.size)

            val horizontalSpacingPx: Float = horizontalSpacing.toPx()
            val verticalSpacingPx: Float = verticalSpacing.toPx()

            val edgeSpacingLeftPx: Float = edgePadding.calculateLeftPadding(layoutDirection).toPx()
            val edgeSpacingRightPx: Float = edgePadding.calculateRightPadding(layoutDirection).toPx()
            val edgeSpacingTopPx: Float = edgePadding.calculateTopPadding().toPx()
            val edgeSpacingBottomPx: Float = edgePadding.calculateBottomPadding().toPx()

            // max width for each column is the total column space (total width minus one horizontal spacing for the
            // spacing after the last column) divided by the minimum number of columns, minus the spacing for the column
            val minColumns = columns ?: 1
            val cellConstraints = Constraints(
                maxWidth = (((constraints.maxWidth - horizontalSpacingPx) / minColumns) - horizontalSpacingPx).toInt()
                    .coerceAtLeast(0),
            )

            var maxCellWidth = 0 // find max cell width while measuring to avoid an extra loop
            val cellPlaceables = cellMeasurables.map { measurable ->
                measurable.measure(cellConstraints).also { placeable ->
                    maxCellWidth = max(maxCellWidth, placeable.width)
                }
            }

            // the total width of a column, including its spacing
            val columnWidthWithSpacing: Float = maxCellWidth + horizontalSpacingPx

            // the amount of space for the columns: the layout max width minus the left/right edge spacing
            val columnSpace = constraints.maxWidth - edgeSpacingLeftPx - edgeSpacingRightPx

            // number of columns is the total column space (minus one horizontal spacing for the spacing after the last
            // column) divided by the column width including its spacing; then taking the floor to truncate any
            // "fractional column"
            val cols: Int = columns
                ?: ((columnSpace - horizontalSpacingPx) / columnWidthWithSpacing).toInt().coerceAtLeast(1)

            // now we need to account for that "fractional column" by adding some "extra" to each column spacing,
            // distributed among the space between each column (note: we cannot add this extra to the columns rather
            // than the spacing because the placeables have already been measured)
            // first: the total width used without the extra is the number of columns times the column width with
            // spacing minus an extra horizontal spacing after the final column
            // next: extra is the max width minus the used width, divided by the number of columns minus one (to exclude
            // start/end padding)
            // finally: create adjusted width variables including the extra
            val usedWidth: Float = (cols * columnWidthWithSpacing) - horizontalSpacingPx
            val extra: Float = (columnSpace - usedWidth) / (cols - 1)
            val columnWidthWithSpacingAndExtra: Float = maxCellWidth + horizontalSpacingPx + extra

            val divisionElements = divisions.values.toList()

            var totalHeight = edgeSpacingBottomPx + edgeSpacingTopPx

            // division -> [heights of rows in that division]
            val rowHeights: Array<IntArray> = Array(divisionElements.size) { divisionIndex ->
                val division = divisionElements[divisionIndex]

                // number of rows is the number of cells in the division divided by number of columns, rounded up
                val divisionRows = ceil(division.size.toFloat() / cols).toInt()
                totalHeight += (verticalSpacingPx * (divisionRows - 1)).roundToInt()

                // height of each division row is the maximum height of placeables in that row
                IntArray(divisionRows) { row ->
                    division.subList(
                        fromIndex = row * cols,
                        toIndex = ((row + 1) * cols).coerceAtMost(division.size),
                    )
                        .maxOf { cellPlaceables[it.index].height }
                        .also { totalHeight += it }
                }
            }

            val dividerPlaceables = elements.divider?.dividableProperty
                ?.let { measurables.subList(fromIndex = elements.size, toIndex = elements.size + divisions.size) }
                ?.map { measurable ->
                    measurable.measure(constraints)
                        .also { totalHeight += it.height }
                }
                ?.also {
                    // add vertical spacing above and below each divider, except for above the first one
                    totalHeight += (it.size * 2 - 1) * verticalSpacingPx
                }

            val selectedElementDivisionIndex: Int?
            val selectedElementRowIndex: Int?
            val selectedElementColIndex: Int?
            val detailInsertPlaceable: Placeable?
            val selectedItemBackgroundPlaceable: Placeable?

            // ensure we have exactly 2 insert measurables; in rare cases while the animation being toggled on or off we
            // can have just 1
            if (measurables.size - cellMeasurables.size - (dividerPlaceables?.size ?: 0) == 2) {
                requireNotNull(lastSelectedElementIndex)
                val selectedElementDivision = elements.divisionOf(lastSelectedElementIndex)
                val division = requireNotNull(divisions[selectedElementDivision]) { "null selected element division" }
                val indexInDivision = division
                    .indexOfFirst { it.index == lastSelectedElementIndex }
                    .takeIf { it >= 0 }

                // if the element could not be found in its division, it must be filtered out and the insert should be
                // hidden
                if (indexInDivision == null) {
                    selectedElementDivisionIndex = null
                    selectedElementRowIndex = null
                    selectedElementColIndex = null
                    detailInsertPlaceable = null
                    selectedItemBackgroundPlaceable = null
                } else {
                    selectedElementDivisionIndex = divisions.keys.indexOf(selectedElementDivision)
                    require(indexInDivision >= 0) { "selected element not found in its division" }

                    selectedElementRowIndex = indexInDivision / cols
                    selectedElementColIndex = indexInDivision % cols

                    val insertMeasurables = measurables.subList(
                        fromIndex = measurables.size - 2,
                        toIndex = measurables.size,
                    )

                    // background highlight on the selected item:
                    // - has extra maxWidth since otherwise during the animation the flared base is clipped
                    // - maxHeight increased by verticalSpacingPx to cover space between the row and the insert
                    // - maxHeight increased by divider size to align better with insert top border
                    selectedItemBackgroundPlaceable = insertMeasurables[0].measure(
                        constraints.copy(
                            maxWidth = maxCellWidth + detailInsertCornerSize.roundToPx() * 2,
                            maxHeight = rowHeights[selectedElementDivisionIndex][selectedElementRowIndex] +
                                (verticalSpacingPx + Dimens.divider.toPx()).roundToInt(),
                        ),
                    )

                    detailInsertPlaceable = insertMeasurables[1].measure(constraints)

                    totalHeight += detailInsertPlaceable.height + verticalSpacingPx.roundToInt()
                }
            } else {
                selectedElementDivisionIndex = null
                selectedElementRowIndex = null
                selectedElementColIndex = null
                detailInsertPlaceable = null
                selectedItemBackgroundPlaceable = null
            }

            layout(constraints.maxWidth, totalHeight.roundToInt()) {
                var y = edgeSpacingTopPx

                divisionElements.forEachIndexed { divisionIndex, division ->
                    dividerPlaceables?.get(divisionIndex)?.let { placeable ->
                        placeable.place(x = 0, y = y.roundToInt())
                        y += placeable.height + verticalSpacingPx
                    }

                    rowHeights[divisionIndex].forEachIndexed { rowIndex, rowHeight ->
                        val roundedY = y.roundToInt()

                        // whether the selected item insert should be placed in this row
                        val insertInRow = divisionIndex == selectedElementDivisionIndex &&
                            rowIndex == selectedElementRowIndex

                        for (colIndex in 0..<cols) {
                            // getOrNull in case the column exceeds the number of elements in the last row
                            division.getOrNull(colIndex + rowIndex * cols)?.index?.let { elementIndex ->
                                val placeable = cellPlaceables[elementIndex]
                                val baseX = (edgeSpacingLeftPx + (colIndex * columnWidthWithSpacingAndExtra))
                                    .roundToInt()

                                if (insertInRow && colIndex == selectedElementColIndex) {
                                    // adjust x to account for flared base
                                    selectedItemBackgroundPlaceable!!.place(
                                        x = baseX - detailInsertCornerSize.roundToPx(),
                                        y = y.roundToInt(),
                                    )
                                }

                                // adjust the element based on its alignment and place it
                                val alignment = cellAlignment.align(
                                    size = IntSize(width = placeable.width, height = placeable.height),
                                    space = IntSize(width = maxCellWidth, height = rowHeight),
                                    layoutDirection = layoutDirection,
                                )

                                placeable.place(x = baseX + alignment.x, y = roundedY + alignment.y)
                            }
                        }

                        // place the insert after the row so that the selected item background is drawn below it
                        if (insertInRow) {
                            detailInsertPlaceable!!.place(
                                x = 0,
                                y = (y + rowHeight + verticalSpacingPx).roundToInt(),
                            )
                        }

                        y += rowHeight + verticalSpacingPx

                        if (insertInRow) {
                            y += detailInsertPlaceable!!.height + verticalSpacingPx
                        }
                    }
                }
            }
        },
    )
}
