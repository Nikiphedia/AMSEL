package ch.etasystems.amsel.ui.sonogram

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import ch.etasystems.amsel.core.annotation.ANNOTATION_COLORS
import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.spectrogram.MelFilterbank
import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log2
import kotlin.math.roundToInt

// ========================================================================
// ZoomedCanvas mit Annotation-Overlay
// ========================================================================

/**
 * Raender/Bereiche einer Annotation die per Drag angepasst werden koennen.
 * NONE = kein Hit, LEFT/RIGHT/TOP/BOTTOM = Rand verschieben, CENTER = ganze Annotation bewegen.
 */
private enum class EditDragEdge {
    NONE, LEFT, RIGHT, TOP, BOTTOM, CENTER
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ZoomedCanvas(
    spectrogramData: SpectrogramData?,
    selection: Rect?,
    onSelectionChanged: (Rect?) -> Unit,
    selectionMode: Boolean,
    viewStartSec: Float,
    viewEndSec: Float,
    annotations: List<Annotation> = emptyList(),
    activeAnnotationId: String? = null,
    onAnnotationClicked: (String) -> Unit = {},
    onAnnotationRightClicked: (annotationId: String, posX: Float, posY: Float) -> Unit = { _, _, _ -> },
    playbackPositionSec: Float? = null,
    isComputing: Boolean = false,
    paletteVersion: Int = 0,
    displayDbRange: Float = 0f,
    displayGamma: Float = 1f,
    volumeGainsLog10: FloatArray? = null,
    volumePoints: List<ch.etasystems.amsel.core.model.VolumePoint> = emptyList(),
    volumeEditMode: Boolean = false,
    onVolumeAddPoint: ((timeSec: Float, gainDb: Float) -> Unit)? = null,
    onVolumeMovePoint: ((index: Int, timeSec: Float, gainDb: Float) -> Unit)? = null,
    onVolumeRemovePoint: ((index: Int) -> Unit)? = null,
    selectedVolumeIndex: Int = -1,
    onVolumeSelectPoint: ((index: Int) -> Unit)? = null,
    freqZoom: Float = 1f,
    useLogFreqAxis: Boolean = false,
    // Edit-Modus: Annotations-Raender per Drag verschieben
    editMode: Boolean = false,
    onAnnotationBoundsChanged: ((id: String, startTimeSec: Float?, endTimeSec: Float?, lowFreqHz: Float?, highFreqHz: Float?) -> Unit)? = null,
    normReferenceMaxDb: Float = 0f,
    modifier: Modifier = Modifier
) {
    // Mel-Bitmap: gecacht, nur bei Daten-/Palette-Aenderung neu (NICHT bei Log/Lin Toggle)
    val melBitmapCached = remember(spectrogramData, paletteVersion, displayDbRange, displayGamma, volumeGainsLog10, normReferenceMaxDb) {
        spectrogramData?.let { data -> createSpectrogramBitmap(data, displayDbRange, displayGamma, volumeGainsLog10, normReferenceMaxDb) }
    }

    // Linear-Bitmap: gecacht, abgeleitet von melBitmap (nur 1x berechnet pro Daten-Aenderung)
    val linearBitmapCached = remember(melBitmapCached, spectrogramData) {
        melBitmapCached?.let { melBmp ->
            val data = spectrogramData ?: return@let melBmp
            val width = melBmp.width
            val height = melBmp.height
            if (width == 0 || height == 0) return@let melBmp

            val srcPixels = IntArray(width * height)
            melBmp.readPixels(srcPixels, 0, 0, width, height)
            val dstPixels = IntArray(width * height)
            val fMin = data.fMin; val fMax = data.fMax

            for (y in 0 until height) {
                val linearFraction = 1f - (y.toFloat() / (height - 1).toFloat())
                val hz = fMin + linearFraction * (fMax - fMin)
                val melNorm = hzToNormalizedMel(hz, fMin, fMax)
                val srcYExact = (1f - melNorm) * (height - 1).toFloat()
                val srcY0 = srcYExact.toInt().coerceIn(0, height - 1)
                val srcY1 = (srcY0 + 1).coerceIn(0, height - 1)
                val frac = srcYExact - srcY0
                for (x in 0 until width) {
                    if (srcY0 == srcY1) {
                        dstPixels[y * width + x] = srcPixels[srcY0 * width + x]
                    } else {
                        val c0 = srcPixels[srcY0 * width + x]; val c1 = srcPixels[srcY1 * width + x]
                        dstPixels[y * width + x] = (lerpChannel(c0, c1, frac, 24) shl 24) or
                            (lerpChannel(c0, c1, frac, 16) shl 16) or
                            (lerpChannel(c0, c1, frac, 8) shl 8) or
                            lerpChannel(c0, c1, frac, 0)
                    }
                }
            }

            val skiaBitmap = Bitmap()
            skiaBitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
            val buf = ByteBuffer.allocate(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (px in dstPixels) {
                buf.put((px and 0xFF).toByte()); buf.put(((px shr 8) and 0xFF).toByte())
                buf.put(((px shr 16) and 0xFF).toByte()); buf.put(((px shr 24) and 0xFF).toByte())
            }
            buf.rewind(); skiaBitmap.installPixels(buf.array())
            skiaBitmap.asComposeImageBitmap()
        }
    }

    // Toggle zwischen Log/Linear = nur Referenzwechsel, 0ms!
    val bitmap = if (useLogFreqAxis) melBitmapCached else linearBitmapCached

    val textMeasurer = rememberTextMeasurer()
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Hover-Position fuer Cursor-Info Overlay (Hz + kHz + Note + Zeit)
    var hoverPosition by remember { mutableStateOf<Offset?>(null) }

    val axisLeft = 50f
    val axisBottom = 30f

    // Edit-Modus State: welcher Rand wird gerade gedraggt
    var editDragEdge by remember { mutableStateOf(EditDragEdge.NONE) }
    var editDragAnnotationId by remember { mutableStateOf<String?>(null) }
    // Ursprungswerte beim Drag-Start (fuer CENTER-Move)
    var editDragStartOffset by remember { mutableStateOf(Offset.Zero) }
    var editDragOrigStartSec by remember { mutableStateOf(0f) }
    var editDragOrigEndSec by remember { mutableStateOf(0f) }
    var editDragOrigLowHz by remember { mutableStateOf(0f) }
    var editDragOrigHighHz by remember { mutableStateOf(0f) }

    // rememberUpdatedState fuer Edit-Modus Callbacks (gleicher Trick wie in OverviewStrip)
    val currentEditCallback by rememberUpdatedState(onAnnotationBoundsChanged)
    val currentAnnotations by rememberUpdatedState(annotations)
    val currentActiveId by rememberUpdatedState(activeAnnotationId)
    val currentViewStart by rememberUpdatedState(viewStartSec)
    val currentViewEnd by rememberUpdatedState(viewEndSec)
    // Volume-Editing: dynamische Werte fuer pointerInput-Lambdas
    val currentVolumePoints by rememberUpdatedState(volumePoints)
    val currentOnVolumeAdd by rememberUpdatedState(onVolumeAddPoint)
    val currentOnVolumeMove by rememberUpdatedState(onVolumeMovePoint)
    val currentOnVolumeSelect by rememberUpdatedState(onVolumeSelectPoint)
    val currentOnVolumeRemove by rememberUpdatedState(onVolumeRemovePoint)
    val currentSelectedVolIdx by rememberUpdatedState(selectedVolumeIndex)

    // Animierter Blur waehrend Berechnung
    val blurRadius by animateFloatAsState(
        targetValue = if (isComputing) 8f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "blur"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                if (blurRadius > 0.5f) {
                    renderEffect = BlurEffect(blurRadius, blurRadius, TileMode.Clamp)
                    alpha = 0.7f  // Leicht ausgeblendet
                } else {
                    renderEffect = null
                    alpha = 1f
                }
            }
            .pointerInput(Unit) {
                // Hover-Tracking: Mausposition im Canvas verfolgen fuer Cursor-Info Overlay
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.type == PointerEventType.Exit) {
                            hoverPosition = null
                        } else {
                            val pos = event.changes.firstOrNull()?.position
                            if (pos != null && pos.x >= axisLeft && pos.y <= size.height - axisBottom
                                && pos.x <= size.width && pos.y >= 0f) {
                                hoverPosition = pos
                            } else {
                                hoverPosition = null
                            }
                        }
                    }
                }
            }
            .pointerInput(editMode) {
                // Edit-Modus: Annotations-Raender per Drag anpassen
                if (!editMode) return@pointerInput
                val hitThreshold = 8f  // Pixel-Abstand fuer Rand-Erkennung

                detectDragGestures(
                    onDragStart = { offset ->
                        val data = spectrogramData ?: return@detectDragGestures
                        val activeId = currentActiveId ?: return@detectDragGestures
                        val ann = currentAnnotations.find { it.id == activeId } ?: return@detectDragGestures

                        val plotW = size.width - axisLeft
                        val plotH = size.height - axisBottom
                        val duration = currentViewEnd - currentViewStart
                        if (duration <= 0f || plotW <= 0f || plotH <= 0f) return@detectDragGestures

                        val fMin = data.fMin
                        val fMax = data.fMax

                        // Annotations-Raender in Pixel berechnen
                        val xLeft = axisLeft + ((ann.startTimeSec - currentViewStart) / duration) * plotW
                        val xRight = axisLeft + ((ann.endTimeSec - currentViewStart) / duration) * plotW
                        val yTop = ((1f - hzToNormalizedMel(ann.highFreqHz, fMin, fMax)) * plotH)
                        val yBottom = ((1f - hzToNormalizedMel(ann.lowFreqHz, fMin, fMax)) * plotH)

                        // Pruefen welcher Rand getroffen wurde
                        val inXRange = offset.x in (xLeft - hitThreshold)..(xRight + hitThreshold)
                        val inYRange = offset.y in (yTop - hitThreshold)..(yBottom + hitThreshold)

                        val edge = when {
                            inYRange && kotlin.math.abs(offset.x - xLeft) < hitThreshold -> EditDragEdge.LEFT
                            inYRange && kotlin.math.abs(offset.x - xRight) < hitThreshold -> EditDragEdge.RIGHT
                            inXRange && kotlin.math.abs(offset.y - yTop) < hitThreshold -> EditDragEdge.TOP
                            inXRange && kotlin.math.abs(offset.y - yBottom) < hitThreshold -> EditDragEdge.BOTTOM
                            offset.x in xLeft..xRight && offset.y in yTop..yBottom -> EditDragEdge.CENTER
                            else -> EditDragEdge.NONE
                        }

                        editDragEdge = edge
                        editDragAnnotationId = if (edge != EditDragEdge.NONE) activeId else null
                        editDragStartOffset = offset
                        editDragOrigStartSec = ann.startTimeSec
                        editDragOrigEndSec = ann.endTimeSec
                        editDragOrigLowHz = ann.lowFreqHz
                        editDragOrigHighHz = ann.highFreqHz
                    },
                    onDrag = { change, _ ->
                        val data = spectrogramData ?: return@detectDragGestures
                        val annId = editDragAnnotationId ?: return@detectDragGestures
                        val cb = currentEditCallback ?: return@detectDragGestures
                        if (editDragEdge == EditDragEdge.NONE) return@detectDragGestures

                        val plotW = size.width - axisLeft
                        val plotH = size.height - axisBottom
                        val duration = currentViewEnd - currentViewStart
                        val fMin = data.fMin
                        val fMax = data.fMax
                        val melMin = MelFilterbank.hzToMel(fMin)
                        val melMax = MelFilterbank.hzToMel(fMax)

                        // Aktuelle Maus-Position in Sekunden und Hz umrechnen
                        val pos = change.position
                        val timeSec = currentViewStart + ((pos.x - axisLeft) / plotW) * duration
                        val melVal = melMax - (pos.y / plotH) * (melMax - melMin)
                        val freqHz = MelFilterbank.melToHz(melVal)

                        // Delta fuer CENTER-Move
                        val deltaX = pos.x - editDragStartOffset.x
                        val deltaSec = (deltaX / plotW) * duration
                        val deltaY = pos.y - editDragStartOffset.y
                        // Delta-Hz ueber Mel-Mapping (nicht-linear, daher approximieren)
                        val origMelHigh = MelFilterbank.hzToMel(editDragOrigHighHz)
                        val origMelLow = MelFilterbank.hzToMel(editDragOrigLowHz)
                        val melDelta = -(deltaY / plotH) * (melMax - melMin)

                        when (editDragEdge) {
                            EditDragEdge.LEFT -> cb(annId, timeSec, null, null, null)
                            EditDragEdge.RIGHT -> cb(annId, null, timeSec, null, null)
                            EditDragEdge.TOP -> cb(annId, null, null, null, freqHz)
                            EditDragEdge.BOTTOM -> cb(annId, null, null, freqHz, null)
                            EditDragEdge.CENTER -> {
                                val newStartSec = editDragOrigStartSec + deltaSec
                                val newEndSec = editDragOrigEndSec + deltaSec
                                val newLowHz = MelFilterbank.melToHz(origMelLow + melDelta)
                                val newHighHz = MelFilterbank.melToHz(origMelHigh + melDelta)
                                cb(annId, newStartSec, newEndSec, newLowHz, newHighHz)
                            }
                            EditDragEdge.NONE -> {}
                        }
                    },
                    onDragEnd = {
                        editDragEdge = EditDragEdge.NONE
                        editDragAnnotationId = null
                    }
                )
            }
            .pointerInput(selectionMode, editMode, volumeEditMode) {
                if (!selectionMode || editMode || volumeEditMode) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        if (offset.x >= axisLeft && offset.y <= size.height - axisBottom) {
                            dragStart = offset
                            dragCurrent = offset
                        }
                    },
                    onDrag = { change, _ ->
                        dragCurrent = change.position
                    },
                    onDragEnd = {
                        val start = dragStart
                        val end = dragCurrent
                        if (start != null && end != null && spectrogramData != null) {
                            val rect = normalizeRect(
                                start, end, canvasSize,
                                axisLeft, axisBottom
                            )
                            onSelectionChanged(rect)
                        }
                        dragStart = null
                        dragCurrent = null
                    }
                )
            }
            .pointerInput(selectionMode, editMode, volumeEditMode, annotations) {
                if (editMode || volumeEditMode) return@pointerInput  // Im Edit/Volume-Modus kein Tap-Handling
                if (selectionMode) {
                    detectTapGestures {
                        onSelectionChanged(null)
                    }
                } else if (annotations.isNotEmpty() && spectrogramData != null) {
                    detectTapGestures { offset ->
                        val plotWidth = size.width - axisLeft
                        val plotHeight = size.height - axisBottom
                        val duration = viewEndSec - viewStartSec

                        val clickTimeSec = viewStartSec + ((offset.x - axisLeft) / plotWidth) * duration
                        // Inverse Mel-Mapping: Pixel-Y -> Hz
                        // Das Bitmap ist immer in Mel-Bins, daher gleiche Zuordnung in beiden Modi
                        val melMin = MelFilterbank.hzToMel(spectrogramData.fMin)
                        val melMax = MelFilterbank.hzToMel(spectrogramData.fMax)
                        val clickMel = melMax - (offset.y / plotHeight) * (melMax - melMin)
                        val clickFreqHz = MelFilterbank.melToHz(clickMel)

                        val clicked = annotations.find { ann ->
                            clickTimeSec in ann.startTimeSec..ann.endTimeSec &&
                            clickFreqHz in ann.lowFreqHz..ann.highFreqHz
                        }
                        if (clicked != null) {
                            onAnnotationClicked(clicked.id)
                        }
                    }
                }
            }
            // Rechtsklick auf Annotation: Zone-Analyse anbieten
            .pointerInput(annotations, spectrogramData) {
                if (annotations.isEmpty() || spectrogramData == null) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        // Nur Release-Events mit genau dem Secondary-Button
                        if (event.type != PointerEventType.Release) continue
                        val change = event.changes.firstOrNull() ?: continue
                        // Pruefen ob Rechtsklick: Secondary Button gerade losgelassen
                        if (!change.previousPressed || change.pressed) continue
                        // button ist nur bei Press/Release Events gesetzt
                        val btn = event.button ?: continue
                        if (btn != androidx.compose.ui.input.pointer.PointerButton.Secondary) continue

                        change.consume()
                        val offset = change.position
                        val plotWidth = size.width - axisLeft
                        val plotHeight = size.height - axisBottom
                        val duration = viewEndSec - viewStartSec
                        if (duration <= 0f || plotWidth <= 0f) continue
                        val clickTimeSec = viewStartSec + ((offset.x - axisLeft) / plotWidth) * duration
                        val melMin = MelFilterbank.hzToMel(spectrogramData.fMin)
                        val melMax = MelFilterbank.hzToMel(spectrogramData.fMax)
                        val clickMel = melMax - (offset.y / plotHeight) * (melMax - melMin)
                        val clickFreqHz = MelFilterbank.melToHz(clickMel)
                        val clicked = annotations.find { ann ->
                            clickTimeSec in ann.startTimeSec..ann.endTimeSec &&
                            clickFreqHz in ann.lowFreqHz..ann.highFreqHz
                        }
                        if (clicked != null) {
                            onAnnotationRightClicked(clicked.id, offset.x, offset.y)
                        }
                    }
                }
            }
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown &&
                    (keyEvent.key == Key.Enter || keyEvent.key == Key.Delete || keyEvent.key == Key.Backspace) &&
                    currentSelectedVolIdx >= 0) {
                    currentOnVolumeRemove?.invoke(currentSelectedVolIdx)
                    currentOnVolumeSelect?.invoke(-1)
                    true
                } else false
            }
            .pointerInput(volumeEditMode) {
                if (!volumeEditMode) return@pointerInput
                val DB_MIN = -60f; val DB_MAX = 6f; val DB_RANGE = DB_MAX - DB_MIN
                val HIT_R = 14f
                var volDragIdx = -1

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pos = event.changes.firstOrNull() ?: continue
                        val plotW = size.width - axisLeft
                        val plotH = size.height - axisBottom
                        if (plotW <= 0f || plotH <= 0f) continue
                        val duration = currentViewEnd - currentViewStart
                        if (duration <= 0f) continue

                        val px = pos.position.x - axisLeft
                        val py = pos.position.y
                        val timeSec = currentViewStart + (px / plotW) * duration
                        val gainDb = (DB_MAX - (py / plotH) * DB_RANGE).coerceIn(DB_MIN, DB_MAX)

                        fun findNearest(): Int {
                            var bestIdx = -1; var bestDist = HIT_R * HIT_R
                            for (i in currentVolumePoints.indices) {
                                val vx = ((currentVolumePoints[i].timeSec - currentViewStart) / duration) * plotW
                                val vy = plotH * (1f - (currentVolumePoints[i].gainDb - DB_MIN) / DB_RANGE)
                                val d = (px - vx) * (px - vx) + (py - vy) * (py - vy)
                                if (d < bestDist) { bestDist = d; bestIdx = i }
                            }
                            return bestIdx
                        }

                        when (event.type) {
                            PointerEventType.Press -> {
                                if (px < 0) continue
                                val hitIdx = findNearest()
                                if (hitIdx >= 0) {
                                    volDragIdx = hitIdx
                                    currentOnVolumeSelect?.invoke(hitIdx)
                                } else {
                                    currentOnVolumeAdd?.invoke(timeSec, gainDb)
                                    currentOnVolumeSelect?.invoke(-1)
                                    volDragIdx = -1
                                }
                                pos.consume()
                            }
                            PointerEventType.Move -> {
                                if (volDragIdx >= 0 && pos.pressed) {
                                    currentOnVolumeMove?.invoke(volDragIdx, timeSec, gainDb)
                                    pos.consume()
                                }
                            }
                            PointerEventType.Release -> { volDragIdx = -1 }
                        }
                    }
                }
            }
    ) {
        canvasSize = IntSize(size.width.toInt(), size.height.toInt())

        val plotWidth = size.width - axisLeft
        val plotHeight = size.height - axisBottom

        drawRect(Color(0xFF1A1A2E))

        if (bitmap != null && spectrogramData != null) {
            // Frequenz-Zoom: nur den unteren Teil des Bitmaps anzeigen
            // freqZoom=1 → volles Bild, freqZoom=2 → untere Haelfte (niedrige Freq vergroessert)
            val srcFraction = (1f / freqZoom).coerceIn(0.1f, 1f)
            val srcTop = ((1f - srcFraction) * bitmap.height).toInt()
            val srcHeight = (srcFraction * bitmap.height).toInt().coerceAtLeast(1)
            drawImage(
                image = bitmap,
                srcOffset = androidx.compose.ui.unit.IntOffset(0, srcTop),
                srcSize = IntSize(bitmap.width, srcHeight),
                dstOffset = androidx.compose.ui.unit.IntOffset(axisLeft.toInt(), 0),
                dstSize = IntSize(plotWidth.toInt(), plotHeight.toInt())
            )

            // Annotationen zeichnen (nur wenn nicht gerade Zoom-Berechnung laeuft,
            // damit Annotationen nicht relativ zum alten Bild verschoben erscheinen)
            val duration = viewEndSec - viewStartSec
            val fRange = spectrogramData.fMax - spectrogramData.fMin

            if (!isComputing) for (ann in annotations) {
                drawAnnotationOverlay(
                    annotation = ann,
                    isActive = ann.id == activeAnnotationId,
                    viewStartSec = viewStartSec,
                    duration = duration,
                    fMin = spectrogramData.fMin,
                    fRange = fRange,
                    axisLeft = axisLeft,
                    plotWidth = plotWidth,
                    plotHeight = plotHeight,
                    textMeasurer = textMeasurer,
                    useLogFreqAxis = useLogFreqAxis
                )
            }

            // Edit-Modus: Drag-Handles an aktiver Annotation zeichnen
            if (editMode && activeAnnotationId != null) {
                val activeAnn = annotations.find { it.id == activeAnnotationId }
                if (activeAnn != null) {
                    val fMin2 = spectrogramData.fMin
                    val fMax2 = spectrogramData.fMax
                    val xL = axisLeft + ((activeAnn.startTimeSec - viewStartSec) / duration) * plotWidth
                    val xR = axisLeft + ((activeAnn.endTimeSec - viewStartSec) / duration) * plotWidth
                    val yT = ((1f - hzToNormalizedMel(activeAnn.highFreqHz, fMin2, fMax2)) * plotHeight)
                    val yB = ((1f - hzToNormalizedMel(activeAnn.lowFreqHz, fMin2, fMax2)) * plotHeight)

                    val handleSize = 10f
                    val handleColor = Color(0xFF90CAF9)

                    // Handles an den 4 Rand-Mitten
                    val midX = (xL + xR) / 2f
                    val midY = (yT + yB) / 2f
                    // Links
                    drawRect(handleColor, Offset(xL - handleSize / 2, midY - handleSize / 2), Size(handleSize, handleSize))
                    // Rechts
                    drawRect(handleColor, Offset(xR - handleSize / 2, midY - handleSize / 2), Size(handleSize, handleSize))
                    // Oben
                    drawRect(handleColor, Offset(midX - handleSize / 2, yT - handleSize / 2), Size(handleSize, handleSize))
                    // Unten
                    drawRect(handleColor, Offset(midX - handleSize / 2, yB - handleSize / 2), Size(handleSize, handleSize))
                    // Ecken (kleiner)
                    val cs = 6f
                    drawRect(handleColor, Offset(xL - cs / 2, yT - cs / 2), Size(cs, cs))
                    drawRect(handleColor, Offset(xR - cs / 2, yT - cs / 2), Size(cs, cs))
                    drawRect(handleColor, Offset(xL - cs / 2, yB - cs / 2), Size(cs, cs))
                    drawRect(handleColor, Offset(xR - cs / 2, yB - cs / 2), Size(cs, cs))
                }
            }

            // Achsen (Frequenzbereich anpassen fuer Zoom)
            val visibleFMax = spectrogramData.fMin + (spectrogramData.fMax - spectrogramData.fMin) / freqZoom
            drawAxes(
                spectrogramData = spectrogramData,
                viewStartSec = viewStartSec,
                viewEndSec = viewEndSec,
                axisLeft = axisLeft,
                axisBottom = axisBottom,
                plotWidth = plotWidth,
                plotHeight = plotHeight,
                textMeasurer = textMeasurer,
                useLogFreqAxis = useLogFreqAxis
            )

            // Volume-Envelope Kurve (auf dem Zoom-Bereich) — mit Breakpoints wenn Edit-Modus
            if (volumePoints.isNotEmpty()) {
                drawVolumeOverlay(volumePoints, axisLeft, plotWidth, plotHeight, viewStartSec, viewEndSec,
                    drawPoints = volumeEditMode, selectedIndex = currentSelectedVolIdx)
            }

            // Playback-Position (rote vertikale Linie + Dreieck)
            if (playbackPositionSec != null && playbackPositionSec in viewStartSec..viewEndSec) {
                val posX = axisLeft + ((playbackPositionSec - viewStartSec) / duration) * plotWidth

                drawLine(
                    color = Color(0xFFFF4444),
                    start = Offset(posX, 0f),
                    end = Offset(posX, plotHeight),
                    strokeWidth = 2.5f
                )

                val pathTop = Path().apply {
                    moveTo(posX - 6f, 0f)
                    lineTo(posX + 6f, 0f)
                    lineTo(posX, 10f)
                    close()
                }
                drawPath(pathTop, Color(0xFFFF4444))

                val pathBottom = Path().apply {
                    moveTo(posX - 6f, plotHeight)
                    lineTo(posX + 6f, plotHeight)
                    lineTo(posX, plotHeight - 10f)
                    close()
                }
                drawPath(pathBottom, Color(0xFFFF4444))
            }
        }

        // Aktive Auswahl
        val sel = selection
        if (sel != null) {
            drawSelectionRect(sel, canvasSize, axisLeft, axisBottom)
        }

        // Drag-Vorschau
        val start = dragStart
        val end = dragCurrent
        if (start != null && end != null) {
            val previewRect = normalizeRect(start, end, canvasSize, axisLeft, axisBottom)
            drawSelectionRect(previewRect, canvasSize, axisLeft, axisBottom)
        }

        // ═══ Cursor-Info Overlay: Hz + kHz + musikalische Note + Zeit ═══
        val hover = hoverPosition
        if (hover != null && spectrogramData != null) {
            val duration = viewEndSec - viewStartSec
            if (duration > 0f && plotWidth > 0f && plotHeight > 0f) {
                // Cursor-Position in Zeit und Frequenz umrechnen
                val cursorTimeSec = viewStartSec + ((hover.x - axisLeft) / plotWidth) * duration
                val melMin = MelFilterbank.hzToMel(spectrogramData.fMin)
                val melMax = MelFilterbank.hzToMel(spectrogramData.fMax)
                val cursorMel = melMax - (hover.y / plotHeight) * (melMax - melMin)
                val cursorHz = MelFilterbank.melToHz(cursorMel)

                // Musikalische Note berechnen: note = 12 * log2(hz/440) + 69
                val noteInfo = if (cursorHz > 20f) {
                    val midiNote = 12.0 * log2(cursorHz.toDouble() / 440.0) + 69.0
                    val nearestMidi = midiNote.roundToInt()
                    val cents = ((midiNote - nearestMidi) * 100).roundToInt()
                    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
                    val noteIndex = ((nearestMidi % 12) + 12) % 12  // Sicherstellen: positiv
                    val octave = (nearestMidi / 12) - 1
                    val noteName = noteNames[noteIndex]
                    val centsStr = if (cents >= 0) "+${cents}ct" else "${cents}ct"
                    "$noteName$octave$centsStr"
                } else {
                    ""
                }

                // Overlay-Text zusammenbauen
                val hzStr = "${cursorHz.roundToInt()} Hz"
                val kHzStr = "${"%.2f".format(cursorHz / 1000f)} kHz"
                val timeStr = "${"%.3f".format(cursorTimeSec)}s"
                val overlayText = if (noteInfo.isNotEmpty()) {
                    "$hzStr ($kHzStr) ~ $noteInfo | $timeStr"
                } else {
                    "$hzStr ($kHzStr) | $timeStr"
                }

                val overlayStyle = TextStyle(
                    color = Color(0xFFE0E0E0),
                    fontSize = 11.sp
                )
                val measured = textMeasurer.measure(overlayText, overlayStyle)

                // Position: am unteren Rand des Plot-Bereichs, zentriert auf Cursor-X
                val overlayX = (hover.x - measured.size.width / 2f)
                    .coerceIn(axisLeft, axisLeft + plotWidth - measured.size.width)
                val overlayY = plotHeight - measured.size.height - 4f

                // Hintergrund-Box
                drawRect(
                    color = Color(0xCC000000),
                    topLeft = Offset(overlayX - 4f, overlayY - 2f),
                    size = Size(measured.size.width + 8f, measured.size.height + 4f)
                )
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(overlayX, overlayY)
                )
            }
        }
    }
}

// ========================================================================
// Annotation-Overlay zeichnen
// ========================================================================

private fun DrawScope.drawAnnotationOverlay(
    annotation: Annotation,
    isActive: Boolean,
    viewStartSec: Float,
    duration: Float,
    fMin: Float,
    fRange: Float,
    axisLeft: Float,
    plotWidth: Float,
    plotHeight: Float,
    textMeasurer: TextMeasurer,
    useLogFreqAxis: Boolean = false
) {
    if (duration <= 0f || fRange <= 0f) return

    val color = Color(ANNOTATION_COLORS[annotation.colorIndex % ANNOTATION_COLORS.size])

    val fMax = fMin + fRange
    val xLeft = axisLeft + ((annotation.startTimeSec - viewStartSec) / duration) * plotWidth
    val xRight = axisLeft + ((annotation.endTimeSec - viewStartSec) / duration) * plotWidth
    // Y-Position: Mel-Bins sind immer gleichmaessig im Bitmap verteilt,
    // daher hzToNormalizedMel fuer die korrekte Zuordnung Hz->Pixel
    val yTop = ((1f - hzToNormalizedMel(annotation.highFreqHz, fMin, fMax)) * plotHeight)
    val yBottom = ((1f - hzToNormalizedMel(annotation.lowFreqHz, fMin, fMax)) * plotHeight)

    val clippedLeft = xLeft.coerceAtLeast(axisLeft)
    val clippedRight = xRight.coerceAtMost(axisLeft + plotWidth)
    val clippedTop = yTop.coerceAtLeast(0f)
    val clippedBottom = yBottom.coerceAtMost(plotHeight)

    if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return

    drawRect(
        color = color.copy(alpha = if (isActive) 0.25f else 0.12f),
        topLeft = Offset(clippedLeft, clippedTop),
        size = Size(clippedRight - clippedLeft, clippedBottom - clippedTop)
    )

    drawRect(
        color = color.copy(alpha = if (isActive) 1f else 0.6f),
        topLeft = Offset(clippedLeft, clippedTop),
        size = Size(clippedRight - clippedLeft, clippedBottom - clippedTop),
        style = Stroke(width = if (isActive) 2.5f else 1.5f)
    )

    if (annotation.label.isNotBlank()) {
        val labelStyle = TextStyle(
            color = color,
            fontSize = 11.sp
        )
        val measured = textMeasurer.measure(annotation.label, labelStyle)
        val labelX = clippedLeft + 3f
        val labelY = clippedTop - measured.size.height - 2f

        if (labelY >= 0f) {
            drawRect(
                color = Color(0xCC1A1A2E),
                topLeft = Offset(labelX - 2f, labelY),
                size = Size(measured.size.width + 4f, measured.size.height.toFloat())
            )
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(labelX, labelY)
            )
        }
    }

    if (isActive && annotation.matchResults.isNotEmpty()) {
        val top = annotation.matchResults.first()
        val badgeText = "${top.species} ${(top.similarity * 100).toInt()}%"
        val badgeStyle = TextStyle(
            color = Color(0xFF4CAF50),
            fontSize = 10.sp
        )
        val badgeMeasured = textMeasurer.measure(badgeText, badgeStyle)
        val badgeX = clippedLeft + 3f
        val badgeY = clippedBottom + 3f

        if (badgeY + badgeMeasured.size.height < plotHeight) {
            drawRect(
                color = Color(0xCC1A1A2E),
                topLeft = Offset(badgeX - 2f, badgeY),
                size = Size(badgeMeasured.size.width + 4f, badgeMeasured.size.height.toFloat())
            )
            drawText(
                textLayoutResult = badgeMeasured,
                topLeft = Offset(badgeX, badgeY)
            )
        }
    }
}

// ========================================================================
// Achsen
// ========================================================================

private fun DrawScope.drawAxes(
    spectrogramData: SpectrogramData,
    viewStartSec: Float,
    viewEndSec: Float,
    axisLeft: Float,
    axisBottom: Float,
    plotWidth: Float,
    plotHeight: Float,
    textMeasurer: TextMeasurer,
    useLogFreqAxis: Boolean = false
) {
    val textStyle = TextStyle(
        color = Color(0xFFAAAAAA),
        fontSize = 10.sp
    )

    // Zeitachse (unveraendert in beiden Modi)
    val durationSec = viewEndSec - viewStartSec
    val timeSteps = calculateTimeSteps(durationSec)
    val firstTick = (viewStartSec / timeSteps).toInt() * timeSteps

    var t = firstTick
    while (t <= viewEndSec) {
        if (t >= viewStartSec) {
            val x = axisLeft + ((t - viewStartSec) / durationSec) * plotWidth
            drawLine(
                color = Color(0xFF555555),
                start = Offset(x, plotHeight),
                end = Offset(x, plotHeight + 5f),
                strokeWidth = 1f
            )
            val label = formatTime(t)
            val measured = textMeasurer.measure(label, textStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(x - measured.size.width / 2f, plotHeight + 8f)
            )
        }
        t += timeSteps
    }

    val fMin = spectrogramData.fMin
    val fMax = spectrogramData.fMax

    if (useLogFreqAxis) {
        // Logarithmischer Modus: Mel-Bins direkt proportional zur Canvas-Hoehe
        // Tick-Labels bei logarithmischen Frequenzen (Oktaven-basiert)
        val logTicks = floatArrayOf(100f, 200f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f, 32000f, 64000f)
        for (freq in logTicks) {
            if (freq < fMin || freq > fMax) continue
            // Y-Position: Mel-Bin proportional (gleiche Zuordnung wie Bitmap)
            val normalizedFreq = hzToNormalizedMel(freq, fMin, fMax)
            val y = plotHeight * (1f - normalizedFreq)

            drawLine(
                color = Color(0xFF555555),
                start = Offset(axisLeft - 5f, y),
                end = Offset(axisLeft, y),
                strokeWidth = 1f
            )
            // Gestrichelte Hilfslinie ueber das ganze Plot
            val dashLen = 4f
            var dx = axisLeft
            while (dx < axisLeft + plotWidth) {
                val endX = (dx + dashLen).coerceAtMost(axisLeft + plotWidth)
                drawLine(
                    color = Color(0xFF333333),
                    start = Offset(dx, y),
                    end = Offset(endX, y),
                    strokeWidth = 0.5f
                )
                dx += dashLen * 2f
            }
            val label = if (freq >= 1000) "${(freq / 1000).toInt()}k" else "${freq.toInt()}"
            val measured = textMeasurer.measure(label, textStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(axisLeft - measured.size.width - 8f, y - measured.size.height / 2f)
            )
        }
    } else {
        // Linearer Modus (bisheriges Verhalten): gleichmaessige Hz-Schritte, Mel-kalibriert
        val freqSteps = calculateFreqSteps(fMax - fMin)
        val firstFreq = ((fMin / freqSteps).toInt() + 1) * freqSteps

        var f = firstFreq
        while (f <= fMax) {
            // Mel-kalibrierte Y-Position (Mel-Bins sind logarithmisch verteilt)
            val normalizedFreq = hzToNormalizedMel(f, fMin, fMax)
            val y = plotHeight * (1f - normalizedFreq)

            drawLine(
                color = Color(0xFF555555),
                start = Offset(axisLeft - 5f, y),
                end = Offset(axisLeft, y),
                strokeWidth = 1f
            )
            val label = if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}"
            val measured = textMeasurer.measure(label, textStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(axisLeft - measured.size.width - 8f, y - measured.size.height / 2f)
            )

            f += freqSteps
        }
    }

    drawLine(Color(0xFF555555), Offset(axisLeft, 0f), Offset(axisLeft, plotHeight), 1f)
    drawLine(Color(0xFF555555), Offset(axisLeft, plotHeight), Offset(axisLeft + plotWidth, plotHeight), 1f)
}

private fun calculateTimeSteps(durationSec: Float): Float {
    return when {
        durationSec <= 2f -> 0.25f
        durationSec <= 5f -> 0.5f
        durationSec <= 15f -> 1f
        durationSec <= 30f -> 2f
        durationSec <= 60f -> 5f
        durationSec <= 300f -> 30f
        durationSec <= 600f -> 60f
        durationSec <= 1800f -> 300f
        else -> 600f
    }
}

private fun calculateFreqSteps(range: Float): Float {
    return when {
        range <= 1000f -> 100f
        range <= 5000f -> 500f
        range <= 10000f -> 1000f
        range <= 50000f -> 5000f
        else -> 10000f
    }
}

private fun formatTime(seconds: Float): String {
    val totalSec = seconds.toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    // Millisekunden-Praezision (3 Dezimalstellen) fuer wissenschaftliche Genauigkeit
    val millis = ((seconds - totalSec) * 1000).toInt().coerceIn(0, 999)

    return when {
        h > 0 -> "%d:%02d:%02d.%03d".format(h, m, s, millis)
        millis > 0 -> "%d:%02d.%03d".format(m, s, millis)
        else -> "%d:%02d".format(m, s)
    }
}

// ========================================================================
// Auswahl-Zeichnung
// ========================================================================

private fun DrawScope.drawSelectionRect(
    rect: Rect,
    canvasSize: IntSize,
    axisLeft: Float,
    axisBottom: Float
) {
    val plotWidth = canvasSize.width - axisLeft
    val plotHeight = canvasSize.height - axisBottom

    val pixelRect = Rect(
        left = axisLeft + rect.left * plotWidth,
        top = rect.top * plotHeight,
        right = axisLeft + rect.right * plotWidth,
        bottom = rect.bottom * plotHeight
    )

    drawRect(
        color = Color.Black.copy(alpha = 0.4f),
        topLeft = Offset(axisLeft, 0f),
        size = Size(plotWidth, pixelRect.top)
    )
    drawRect(
        color = Color.Black.copy(alpha = 0.4f),
        topLeft = Offset(axisLeft, pixelRect.bottom),
        size = Size(plotWidth, plotHeight - pixelRect.bottom)
    )
    drawRect(
        color = Color.Black.copy(alpha = 0.4f),
        topLeft = Offset(axisLeft, pixelRect.top),
        size = Size(pixelRect.left - axisLeft, pixelRect.height)
    )
    drawRect(
        color = Color.Black.copy(alpha = 0.4f),
        topLeft = Offset(pixelRect.right, pixelRect.top),
        size = Size(axisLeft + plotWidth - pixelRect.right, pixelRect.height)
    )

    drawRect(
        color = Color(0xFF90CAF9),
        topLeft = Offset(pixelRect.left, pixelRect.top),
        size = Size(pixelRect.width, pixelRect.height),
        style = Stroke(width = 2f)
    )

    val handleSize = 8f
    val corners = listOf(
        pixelRect.topLeft, pixelRect.topRight,
        pixelRect.bottomLeft, pixelRect.bottomRight
    )
    corners.forEach { corner ->
        drawRect(
            color = Color(0xFF90CAF9),
            topLeft = Offset(corner.x - handleSize / 2, corner.y - handleSize / 2),
            size = Size(handleSize, handleSize)
        )
    }
}

private fun normalizeRect(
    start: Offset,
    end: Offset,
    canvasSize: IntSize,
    axisLeft: Float,
    axisBottom: Float
): Rect {
    val plotWidth = canvasSize.width - axisLeft
    val plotHeight = canvasSize.height - axisBottom

    val left = (minOf(start.x, end.x) - axisLeft) / plotWidth
    val right = (maxOf(start.x, end.x) - axisLeft) / plotWidth
    val top = minOf(start.y, end.y) / plotHeight
    val bottom = maxOf(start.y, end.y) / plotHeight

    return Rect(
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        right = right.coerceIn(0f, 1f),
        bottom = bottom.coerceIn(0f, 1f)
    )
}
