package ch.etasystems.amsel.ui.sonogram

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.etasystems.amsel.core.model.VolumePoint
import ch.etasystems.amsel.core.model.gainAtTime

private const val DB_MIN = -60f
private const val DB_MAX = 6f
private const val DB_RANGE = DB_MAX - DB_MIN
private const val POINT_RADIUS = 6f
private const val HIT_RADIUS = 14f

private val CURVE_COLOR = Color(0xFF4FC3F7)
private val POINT_COLOR = Color(0xFFFFFFFF)
private val ZERO_LINE_COLOR = Color(0x80FFFFFF)
private val GRID_COLOR = Color(0x30FFFFFF)

/**
 * Lautstaerke-Automation: Canvas mit Breakpoints.
 * Klick = einfuegen, Drag = verschieben, Rechtsklick = loeschen.
 */
@Composable
fun VolumePanel(
    points: List<VolumePoint>,
    viewStartSec: Float,
    viewEndSec: Float,
    onAddPoint: (timeSec: Float, gainDb: Float) -> Unit,
    onMovePoint: (index: Int, timeSec: Float, gainDb: Float) -> Unit,
    onRemovePoint: (index: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragIndex by remember { mutableStateOf(-1) }

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().height(100.dp)) {
            // dB-Skala links (mit -∞ am unteren Rand)
            Column(
                modifier = Modifier.width(28.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                for (label in listOf("+6", "0", "-20", "-40", "-\u221E")) {
                    Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = Color(0x80FFFFFF))
                }
            }

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pos = event.changes.firstOrNull() ?: continue
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()

                                when (event.type) {
                                    PointerEventType.Press -> {
                                        val hitIdx = findNearest(points, pos.position.x, pos.position.y, w, h, viewStartSec, viewEndSec)
                                        if (hitIdx >= 0) {
                                            // Punkt getroffen → Drag starten
                                            dragIndex = hitIdx
                                            pos.consume()
                                        } else {
                                            // Freie Flaeche → neuen Punkt einfuegen
                                            val timeSec = xToTime(pos.position.x, w, viewStartSec, viewEndSec)
                                            val gainDb = yToDb(pos.position.y, h)
                                            onAddPoint(timeSec, gainDb)
                                            dragIndex = -1
                                            pos.consume()
                                        }
                                    }
                                    PointerEventType.Move -> {
                                        if (dragIndex >= 0) {
                                            val timeSec = xToTime(pos.position.x, w, viewStartSec, viewEndSec)
                                            val gainDb = yToDb(pos.position.y, h)
                                            onMovePoint(dragIndex, timeSec, gainDb)
                                            pos.consume()
                                        }
                                    }
                                    PointerEventType.Release -> {
                                        dragIndex = -1
                                    }
                                }
                            }
                        }
                    }
            ) {
                val w = size.width
                val h = size.height

                // Hintergrund
                drawRect(Color(0xFF1A1A2E))

                // Horizontale Rasterlinien alle 10 dB
                for (db in listOf(-60, -50, -40, -30, -20, -10, 0, 6)) {
                    val y = dbToY(db.toFloat(), h)
                    val color = if (db == 0) ZERO_LINE_COLOR else GRID_COLOR
                    val effect = if (db == 0) null else PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                    drawLine(color, Offset(0f, y), Offset(w, y), strokeWidth = if (db == 0) 1.5f else 0.5f, pathEffect = effect)
                }

                // Volume-Kurve + Breakpoints
                drawVolumeOverlay(points, 0f, w, h, viewStartSec, viewEndSec, drawPoints = true)
            }
        }
    }
}

/** Zeichnet die Volume-Kurve als Overlay (wiederverwendbar fuer Overview + Zoom). */
fun DrawScope.drawVolumeOverlay(
    points: List<VolumePoint>,
    xOffset: Float = 0f,
    width: Float = size.width,
    height: Float = size.height,
    viewStartSec: Float,
    viewEndSec: Float,
    drawPoints: Boolean = false,
    selectedIndex: Int = -1
) {
    if (points.isEmpty()) return
    val viewDur = viewEndSec - viewStartSec
    if (viewDur <= 0f) return

    // 0 dB Linie
    val zeroY = dbToY(0f, height)
    drawLine(ZERO_LINE_COLOR, Offset(xOffset, zeroY), Offset(xOffset + width, zeroY), strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))

    // Kurve
    val path = Path()
    val steps = width.toInt().coerceIn(50, 1000)
    for (step in 0..steps) {
        val x = xOffset + step.toFloat() / steps * width
        val timeSec = viewStartSec + (step.toFloat() / steps) * viewDur
        val gainDb = points.gainAtTime(timeSec)
        val y = dbToY(gainDb, height)
        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, CURVE_COLOR, style = Stroke(width = 2f))

    // Breakpoints
    if (drawPoints) {
        for ((i, point) in points.withIndex()) {
            val px = xOffset + ((point.timeSec - viewStartSec) / viewDur) * width
            if (px in (xOffset - POINT_RADIUS)..(xOffset + width + POINT_RADIUS)) {
                val py = dbToY(point.gainDb, height)
                val isSelected = i == selectedIndex
                val outerColor = if (isSelected) Color(0xFF2196F3) else CURVE_COLOR  // blau wenn selektiert
                val innerColor = if (isSelected) Color(0xFF2196F3) else POINT_COLOR
                val radius = if (isSelected) POINT_RADIUS + 2f else POINT_RADIUS
                drawCircle(outerColor, radius = radius + 1f, center = Offset(px, py))
                drawCircle(innerColor, radius = radius, center = Offset(px, py))
            }
        }
    }
}

private fun dbToY(db: Float, height: Float): Float =
    height * (1f - (db - DB_MIN) / DB_RANGE)

private fun yToDb(y: Float, height: Float): Float =
    DB_MAX - (y / height) * DB_RANGE

private fun xToTime(x: Float, width: Float, viewStart: Float, viewEnd: Float): Float {
    val viewDur = viewEnd - viewStart
    return viewStart + (x / width) * viewDur
}

private fun findNearest(
    points: List<VolumePoint>, x: Float, y: Float, width: Float, height: Float,
    viewStart: Float, viewEnd: Float
): Int {
    val viewDur = viewEnd - viewStart
    if (viewDur <= 0f) return -1
    var bestIdx = -1
    var bestDist = HIT_RADIUS * HIT_RADIUS
    for (i in points.indices) {
        val px = ((points[i].timeSec - viewStart) / viewDur) * width
        val py = dbToY(points[i].gainDb, height)
        val dist = (x - px) * (x - px) + (y - py) * (y - py)
        if (dist < bestDist) {
            bestDist = dist
            bestIdx = i
        }
    }
    return bestIdx
}
