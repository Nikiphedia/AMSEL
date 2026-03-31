package ch.etasystems.amsel.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

// Farben fuer Splitter-Linie
private val SPLITTER_COLOR_NORMAL = Color(0xFF333333)
private val SPLITTER_COLOR_HOVER = Color(0xFF4CAF50)

/**
 * Vertikaler Splitter (teilt links/rechts).
 * Breite 4dp, aendert Cursor auf E_RESIZE bei Hover.
 * onDrag liefert das Delta in dp.
 */
@Composable
fun VerticalSplitter(
    modifier: Modifier = Modifier,
    onDrag: (deltaDp: Float) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .width(4.dp)
            .fillMaxHeight()
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press) {
                            // Drag-Loop: Verfolge Bewegung bis Release
                            var lastX = event.changes.first().position.x
                            event.changes.forEach { it.consume() }

                            while (true) {
                                val dragEvent = awaitPointerEvent()
                                if (dragEvent.type == PointerEventType.Release) {
                                    dragEvent.changes.forEach { it.consume() }
                                    break
                                }
                                if (dragEvent.type == PointerEventType.Move) {
                                    val currentX = dragEvent.changes.first().position.x
                                    val deltaPx = currentX - lastX
                                    // px -> dp umrechnen
                                    val deltaDp = with(density) { deltaPx / this.density }
                                    if (deltaDp != 0f) {
                                        onDrag(deltaDp)
                                    }
                                    lastX = currentX
                                    dragEvent.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
            }
            .background(if (isHovered) SPLITTER_COLOR_HOVER else SPLITTER_COLOR_NORMAL)
    )
}

/**
 * Horizontaler Splitter (teilt oben/unten).
 * Hoehe 4dp, aendert Cursor auf N_RESIZE bei Hover.
 * onDrag liefert das Delta in dp (positiv = nach unten).
 */
@Composable
fun HorizontalSplitter(
    modifier: Modifier = Modifier,
    onDrag: (deltaDp: Float) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .height(4.dp)
            .fillMaxWidth()
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press) {
                            var lastY = event.changes.first().position.y
                            event.changes.forEach { it.consume() }

                            while (true) {
                                val dragEvent = awaitPointerEvent()
                                if (dragEvent.type == PointerEventType.Release) {
                                    dragEvent.changes.forEach { it.consume() }
                                    break
                                }
                                if (dragEvent.type == PointerEventType.Move) {
                                    val currentY = dragEvent.changes.first().position.y
                                    val deltaPx = currentY - lastY
                                    val deltaDp = with(density) { deltaPx / this.density }
                                    if (deltaDp != 0f) {
                                        onDrag(deltaDp)
                                    }
                                    lastY = currentY
                                    dragEvent.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
            }
            .background(if (isHovered) SPLITTER_COLOR_HOVER else SPLITTER_COLOR_NORMAL)
    )
}
