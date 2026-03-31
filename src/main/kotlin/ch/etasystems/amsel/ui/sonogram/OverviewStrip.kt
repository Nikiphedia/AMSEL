package ch.etasystems.amsel.ui.sonogram

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.core.annotation.ANNOTATION_COLORS
import ch.etasystems.amsel.core.annotation.Annotation

// ========================================================================
// OverviewStrip — mit Gummiband-Auswahl + Viewport-Drag + Playback
// ========================================================================

/**
 * Uebersichtsleiste mit drei Interaktionsmodi:
 *
 * 1. **Drag INNERHALB des Viewports**: Verschiebt frei (Bild eingefroren, nur Indikator bewegt sich)
 * 2. **Drag AUSSERHALB des Viewports**: Gummiband → waehlt Zeitbereich
 * 3. **Tap**: Zentriert Viewport auf Klickposition
 *
 * WICHTIG: pointerInput-Keys sind NICHT an viewStart/End gebunden,
 * damit der Drag nicht bei jeder State-Aenderung abbricht.
 * Stattdessen wird rememberUpdatedState verwendet.
 */
@Composable
fun OverviewStrip(
    spectrogramData: ch.etasystems.amsel.core.spectrogram.SpectrogramData?,
    viewStartSec: Float,
    viewEndSec: Float,
    totalDurationSec: Float,
    annotations: List<Annotation> = emptyList(),
    playbackPositionSec: Float? = null,
    paletteVersion: Int = 0,
    onViewRangeChanged: (startSec: Float, endSec: Float) -> Unit,
    onViewRangeDrag: ((startSec: Float, endSec: Float) -> Unit)? = null,
    onViewRangeDragEnd: (() -> Unit)? = null,
    onRubberBandSelect: ((startSec: Float, endSec: Float) -> Unit)? = null,
    displayDbRange: Float = 0f,
    displayGamma: Float = 1f,
    volumeGainsLog10: FloatArray? = null,
    volumePoints: List<ch.etasystems.amsel.core.model.VolumePoint> = emptyList(),
    volumeEditMode: Boolean = false,
    onVolumeAddPoint: ((timeSec: Float, gainDb: Float) -> Unit)? = null,
    onVolumeMovePoint: ((index: Int, timeSec: Float, gainDb: Float) -> Unit)? = null,
    onVolumeRemovePoint: ((index: Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(spectrogramData, paletteVersion, displayDbRange, displayGamma, volumeGainsLog10) {
        spectrogramData?.let { createSpectrogramBitmap(it, displayDbRange, displayGamma, volumeGainsLog10) }
    }

    var stripWidth by remember { mutableStateOf(0f) }
    var volumeDragIndex by remember { mutableStateOf(-1) }

    // Drag-State
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    var dragStartX by remember { mutableStateOf(0f) }
    var dragOriginalStart by remember { mutableStateOf(0f) }
    var dragViewRange by remember { mutableStateOf(0f) }
    // Gummiband
    var rubberStartX by remember { mutableStateOf(0f) }
    var rubberEndX by remember { mutableStateOf(0f) }
    var showRubberBand by remember { mutableStateOf(false) }

    // rememberUpdatedState damit aktuelle Werte im pointerInput verfuegbar sind
    // OHNE den pointerInput neu zu starten (kein Key-Wechsel!)
    val currentViewStart by rememberUpdatedState(viewStartSec)
    val currentViewEnd by rememberUpdatedState(viewEndSec)
    val currentTotal by rememberUpdatedState(totalDurationSec)
    val currentOnChanged by rememberUpdatedState(onViewRangeChanged)
    val currentOnDrag by rememberUpdatedState(onViewRangeDrag)
    val currentOnDragEnd by rememberUpdatedState(onViewRangeDragEnd)
    val currentOnRubberBand by rememberUpdatedState(onRubberBandSelect)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            // KEIN viewStartSec/viewEndSec als Key! Nur totalDurationSec (aendert sich selten)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val total = currentTotal
                        if (total <= 0f || stripWidth <= 0f) return@detectDragGestures

                        val vpLeft = (currentViewStart / total) * stripWidth
                        val vpRight = (currentViewEnd / total) * stripWidth

                        if (offset.x in vpLeft..vpRight) {
                            dragMode = DragMode.MOVE_VIEWPORT
                            dragStartX = offset.x
                            dragOriginalStart = currentViewStart
                            dragViewRange = currentViewEnd - currentViewStart
                            showRubberBand = false
                        } else {
                            dragMode = DragMode.RUBBER_BAND
                            rubberStartX = offset.x
                            rubberEndX = offset.x
                            showRubberBand = true
                        }
                    },
                    onDrag = { change, _ ->
                        val total = currentTotal
                        when (dragMode) {
                            DragMode.MOVE_VIEWPORT -> {
                                if (total > 0f && stripWidth > 0f) {
                                    val deltaX = change.position.x - dragStartX
                                    val deltaSec = deltaX / stripWidth * total
                                    val newStart = (dragOriginalStart + deltaSec)
                                        .coerceIn(0f, total - dragViewRange)
                                    // Live-Drag: nur Koordinaten, kein Recompute
                                    val dragCb = currentOnDrag
                                    if (dragCb != null) {
                                        dragCb(newStart, newStart + dragViewRange)
                                    } else {
                                        currentOnChanged(newStart, newStart + dragViewRange)
                                    }
                                }
                            }
                            DragMode.RUBBER_BAND -> {
                                rubberEndX = change.position.x.coerceIn(0f, stripWidth)
                            }
                            DragMode.NONE -> {}
                        }
                    },
                    onDragEnd = {
                        when (dragMode) {
                            DragMode.MOVE_VIEWPORT -> {
                                // Drag beendet → Bild neu berechnen
                                currentOnDragEnd?.invoke()
                            }
                            DragMode.RUBBER_BAND -> {
                                val total = currentTotal
                                if (showRubberBand && stripWidth > 0f && total > 0f) {
                                    val startX = minOf(rubberStartX, rubberEndX)
                                    val endX = maxOf(rubberStartX, rubberEndX)
                                    val startSec = (startX / stripWidth * total)
                                        .coerceIn(0f, total)
                                    val endSec = (endX / stripWidth * total)
                                        .coerceIn(0f, total)

                                    if (endSec - startSec >= 0.1f) {
                                        val rubberCb = currentOnRubberBand
                                        if (rubberCb != null) {
                                            rubberCb(startSec, endSec)
                                        } else {
                                            currentOnChanged(startSec, endSec)
                                        }
                                    }
                                }
                                showRubberBand = false
                            }
                            else -> {}
                        }
                        dragMode = DragMode.NONE
                    }
                )
            }
            .pointerInput(Unit) {
                // Tap → Viewport zentrieren
                detectTapGestures { offset ->
                    val total = currentTotal
                    if (total <= 0f || stripWidth <= 0f) return@detectTapGestures
                    val clickSec = (offset.x / stripWidth) * total
                    val viewRange = currentViewEnd - currentViewStart
                    val newStart = (clickSec - viewRange / 2f).coerceIn(0f, total - viewRange)
                    currentOnChanged(newStart, newStart + viewRange)
                }
            }
    ) {
        stripWidth = size.width

        drawRect(Color(0xFF1A1A2E))

        if (bitmap != null) {
            drawImage(
                image = bitmap,
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )
        }

        // Annotation-Marker im Overview
        if (totalDurationSec > 0f) {
            for (ann in annotations) {
                val xLeft = (ann.startTimeSec / totalDurationSec) * size.width
                val xRight = (ann.endTimeSec / totalDurationSec) * size.width
                val color = Color(ANNOTATION_COLORS[ann.colorIndex % ANNOTATION_COLORS.size])
                drawRect(
                    color = color.copy(alpha = 0.4f),
                    topLeft = Offset(xLeft, 0f),
                    size = Size(xRight - xLeft, size.height)
                )
            }
        }

        // Viewport-Indikator
        if (totalDurationSec > 0f) {
            val vpLeft = (viewStartSec / totalDurationSec) * size.width
            val vpRight = (viewEndSec / totalDurationSec) * size.width

            // Abgedunkelte Bereiche ausserhalb
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset.Zero,
                size = Size(vpLeft, size.height)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(vpRight, 0f),
                size = Size(size.width - vpRight, size.height)
            )
            // Viewport-Rahmen
            drawRect(
                color = Color(0xFF90CAF9),
                topLeft = Offset(vpLeft, 0f),
                size = Size(vpRight - vpLeft, size.height),
                style = Stroke(width = 2f)
            )
            // Kleine Handles an den Raendern
            val handleH = size.height * 0.4f
            val handleY = (size.height - handleH) / 2f
            drawRect(
                color = Color(0xFF90CAF9).copy(alpha = 0.8f),
                topLeft = Offset(vpLeft - 2f, handleY),
                size = Size(4f, handleH)
            )
            drawRect(
                color = Color(0xFF90CAF9).copy(alpha = 0.8f),
                topLeft = Offset(vpRight - 2f, handleY),
                size = Size(4f, handleH)
            )
        }

        // Volume-Envelope Kurve
        if (volumePoints.isNotEmpty()) {
            drawVolumeOverlay(volumePoints, 0f, size.width, size.height, 0f, totalDurationSec)
        }

        // Gummiband zeichnen
        if (showRubberBand) {
            val left = minOf(rubberStartX, rubberEndX)
            val right = maxOf(rubberStartX, rubberEndX)
            // Helle Markierung
            drawRect(
                color = Color(0xFFFFEB3B).copy(alpha = 0.25f),
                topLeft = Offset(left, 0f),
                size = Size(right - left, size.height)
            )
            // Rahmen
            drawRect(
                color = Color(0xFFFFEB3B),
                topLeft = Offset(left, 0f),
                size = Size(right - left, size.height),
                style = Stroke(width = 2f)
            )
            // Start-/End-Linien
            drawLine(
                color = Color(0xFFFFEB3B),
                start = Offset(left, 0f),
                end = Offset(left, size.height),
                strokeWidth = 2f
            )
            drawLine(
                color = Color(0xFFFFEB3B),
                start = Offset(right, 0f),
                end = Offset(right, size.height),
                strokeWidth = 2f
            )
        }

        // Playback-Position (rote Linie im Overview)
        if (playbackPositionSec != null && totalDurationSec > 0f) {
            val posX = (playbackPositionSec / totalDurationSec) * size.width
            drawLine(
                color = Color(0xFFFF4444),
                start = Offset(posX, 0f),
                end = Offset(posX, size.height),
                strokeWidth = 2.5f
            )
            // Dreieck-Marker oben
            val path = Path().apply {
                moveTo(posX - 6f, 0f)
                lineTo(posX + 6f, 0f)
                lineTo(posX, 10f)
                close()
            }
            drawPath(path, Color(0xFFFF4444))
        }
    }
}

private enum class DragMode {
    NONE,
    MOVE_VIEWPORT,
    RUBBER_BAND
}
