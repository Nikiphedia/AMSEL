package ch.etasystems.amsel.ui.sonogram

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Timeline-Bar mit Zeitachsen-Labels und zwei ziehbaren Handles.
 *
 * Drag-Verhalten:
 * - Handle-Nahbereich (30px): Einzelnen Handle ziehen
 * - Im blauen Bereich: Gesamten Viewport verschieben (frei, ohne Ruckeln)
 * - Ausserhalb: Viewport dorthin zentrieren (Tap)
 *
 * Callbacks:
 * - onRangeChanged: Sofortige Aenderung (Tap, Handle-Ende)
 * - onRangeDrag: Waehrend Drag — nur Koordinaten, kein Recompute
 * - onRangeDragEnd: Nach Drag — Recompute triggern
 */
@Composable
fun TimelineBar(
    viewStartSec: Float,
    viewEndSec: Float,
    totalDurationSec: Float,
    onRangeChanged: (startSec: Float, endSec: Float) -> Unit,
    onRangeDrag: ((startSec: Float, endSec: Float) -> Unit)? = null,
    onRangeDragEnd: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    var barWidth by remember { mutableStateOf(0f) }
    var draggingHandle by remember { mutableStateOf<HandleType?>(null) }
    var dragStartOffset by remember { mutableStateOf(0f) }
    // Lokale Kopien fuer Drag-Beginn (einfrieren)
    var dragStartViewStart by remember { mutableStateOf(0f) }
    var dragStartViewEnd by remember { mutableStateOf(0f) }

    // rememberUpdatedState: aktuelle Werte ohne pointerInput neu zu starten
    val currentViewStart by rememberUpdatedState(viewStartSec)
    val currentViewEnd by rememberUpdatedState(viewEndSec)
    val currentTotal by rememberUpdatedState(totalDurationSec)
    val currentOnChanged by rememberUpdatedState(onRangeChanged)
    val currentOnDrag by rememberUpdatedState(onRangeDrag)
    val currentOnDragEnd by rememberUpdatedState(onRangeDragEnd)

    Column(modifier = modifier.fillMaxWidth()) {
        // Zeiteingabe-Zeile: ◀ Start | Bereich (Zoom ◀▶) | Ende ▶
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatTimeDetailed(viewStartSec),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF90CAF9)
            )

            // Zoom-Buttons: Bereich vergroessern/verkleinern (symmetrisch um Mitte)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Zoom Out (Bereich vergroessern)
                IconButton(
                    onClick = {
                        val center = (viewStartSec + viewEndSec) / 2f
                        val halfRange = (viewEndSec - viewStartSec) / 2f * 1.5f  // 50% groesser
                        val newStart = (center - halfRange).coerceAtLeast(0f)
                        val newEnd = (center + halfRange).coerceAtMost(totalDurationSec)
                        onRangeChanged(newStart, newEnd)
                    },
                    enabled = viewEndSec - viewStartSec < totalDurationSec * 0.95f,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(16.dp))
                }
                Text(
                    "Bereich: ${formatTimeDetailed(viewEndSec - viewStartSec)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                // Zoom In (Bereich verkleinern)
                IconButton(
                    onClick = {
                        val center = (viewStartSec + viewEndSec) / 2f
                        val halfRange = (viewEndSec - viewStartSec) / 2f * 0.667f  // 33% kleiner
                        val newStart = (center - halfRange).coerceAtLeast(0f)
                        val newEnd = (center + halfRange).coerceAtMost(totalDurationSec)
                        if (newEnd - newStart >= 0.5f) onRangeChanged(newStart, newEnd)
                    },
                    enabled = viewEndSec - viewStartSec > 0.5f,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                }
            }

            Text(
                formatTimeDetailed(viewEndSec),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF90CAF9)
            )
        }

        // Timeline-Canvas mit Handles
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = 12.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val total = currentTotal
                        if (barWidth <= 0f || total <= 0f) return@detectTapGestures
                        val startX = (currentViewStart / total) * barWidth
                        val endX = (currentViewEnd / total) * barWidth

                        if (offset.x < startX - 30f || offset.x > endX + 30f) {
                            val range = currentViewEnd - currentViewStart
                            val clickTime = (offset.x / barWidth) * total
                            val newStart = (clickTime - range / 2).coerceIn(0f, total - range)
                            currentOnChanged(newStart, newStart + range)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val total = currentTotal
                            if (total <= 0f || barWidth <= 0f) return@detectDragGestures

                            val startX = (currentViewStart / total) * barWidth
                            val endX = (currentViewEnd / total) * barWidth

                            // Lokale Kopie einfrieren
                            dragStartViewStart = currentViewStart
                            dragStartViewEnd = currentViewEnd

                            draggingHandle = when {
                                abs(offset.x - startX) < 30f -> HandleType.START
                                abs(offset.x - endX) < 30f -> HandleType.END
                                offset.x in startX..endX -> {
                                    dragStartOffset = offset.x - (startX + endX) / 2f
                                    HandleType.RANGE
                                }
                                else -> null
                            }
                        },
                        onDrag = { change, _ ->
                            val total = currentTotal
                            if (total <= 0f || barWidth <= 0f) return@detectDragGestures

                            val x = change.position.x.coerceIn(0f, barWidth)
                            val timeSec = (x / barWidth) * total
                            val minRange = 0.5f

                            val dragCb = currentOnDrag

                            when (draggingHandle) {
                                HandleType.START -> {
                                    val newStart = timeSec.coerceAtMost(currentViewEnd - minRange)
                                    if (dragCb != null) {
                                        dragCb(newStart, currentViewEnd)
                                    } else {
                                        currentOnChanged(newStart, currentViewEnd)
                                    }
                                }
                                HandleType.END -> {
                                    val newEnd = timeSec.coerceAtLeast(currentViewStart + minRange)
                                    if (dragCb != null) {
                                        dragCb(currentViewStart, newEnd)
                                    } else {
                                        currentOnChanged(currentViewStart, newEnd)
                                    }
                                }
                                HandleType.RANGE -> {
                                    val range = dragStartViewEnd - dragStartViewStart
                                    val center = ((x - dragStartOffset) / barWidth) * total
                                    val newStart = (center - range / 2).coerceIn(0f, total - range)
                                    if (dragCb != null) {
                                        dragCb(newStart, newStart + range)
                                    } else {
                                        currentOnChanged(newStart, newStart + range)
                                    }
                                }
                                null -> {}
                            }
                        },
                        onDragEnd = {
                            draggingHandle = null
                            currentOnDragEnd?.invoke()
                        }
                    )
                }
        ) {
            barWidth = size.width
            val barHeight = size.height

            // Hintergrund-Track
            drawRect(
                color = Color(0xFF333344),
                topLeft = Offset(0f, barHeight / 2 - 3f),
                size = Size(barWidth, 6f)
            )

            // Zeitmarkierungen
            val textStyle = TextStyle(color = Color(0xFF777777), fontSize = 8.sp)
            val timeStep = calculateAdaptiveTimeStep(totalDurationSec)
            var tick = 0f
            while (tick <= totalDurationSec) {
                val tickX = (tick / totalDurationSec) * barWidth
                drawLine(
                    color = Color(0xFF555555),
                    start = Offset(tickX, barHeight / 2 - 6f),
                    end = Offset(tickX, barHeight / 2 + 6f),
                    strokeWidth = 1f
                )
                tick += timeStep
            }

            // Ausgewaehlter Bereich
            val startX = (viewStartSec / totalDurationSec) * barWidth
            val endX = (viewEndSec / totalDurationSec) * barWidth

            drawRect(
                color = Color(0xFF90CAF9).copy(alpha = 0.35f),
                topLeft = Offset(startX, barHeight / 2 - 6f),
                size = Size(endX - startX, 12f)
            )

            // Start-Handle
            drawHandle(startX, barHeight, draggingHandle == HandleType.START)

            // End-Handle
            drawHandle(endX, barHeight, draggingHandle == HandleType.END)
        }
    }
}

/**
 * Zeichnet einen Handle (Schieber) auf der Timeline.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandle(
    x: Float,
    barHeight: Float,
    isActive: Boolean
) {
    val handleColor = if (isActive) Color(0xFFBBDEFB) else Color(0xFF90CAF9)
    val handleW = if (isActive) 10f else 8f
    val handleH = barHeight * 0.8f

    drawLine(
        color = handleColor,
        start = Offset(x, (barHeight - handleH) / 2),
        end = Offset(x, (barHeight + handleH) / 2),
        strokeWidth = 2f
    )

    drawRect(
        color = handleColor,
        topLeft = Offset(x - handleW / 2, (barHeight - handleH) / 2),
        size = Size(handleW, handleH),
        style = Stroke(width = 2f)
    )
    drawRect(
        color = handleColor.copy(alpha = 0.4f),
        topLeft = Offset(x - handleW / 2, (barHeight - handleH) / 2),
        size = Size(handleW, handleH)
    )
}

private enum class HandleType { START, END, RANGE }

private fun calculateAdaptiveTimeStep(totalDurationSec: Float): Float {
    return when {
        totalDurationSec <= 10f -> 1f
        totalDurationSec <= 30f -> 5f
        totalDurationSec <= 120f -> 10f
        totalDurationSec <= 600f -> 60f
        totalDurationSec <= 1800f -> 300f
        totalDurationSec <= 3600f -> 600f
        else -> 1800f
    }
}

private fun formatTimeDetailed(seconds: Float): String {
    val totalSec = seconds.toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    // Millisekunden-Praezision (3 Dezimalstellen) fuer wissenschaftliche Genauigkeit
    val millis = ((seconds - totalSec) * 1000).toInt().coerceIn(0, 999)

    return when {
        h > 0 -> "%d:%02d:%02d.%03d".format(h, m, s, millis)
        seconds < 60f -> "%d:%02d.%03d".format(m, s, millis)
        else -> "%d:%02d.%03d".format(m, s, millis)
    }
}
