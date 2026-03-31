package ch.etasystems.amsel.core.export

import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.detection.EventDetector
import ch.etasystems.amsel.core.spectrogram.Colormap
import ch.etasystems.amsel.core.spectrogram.MelFilterbank
import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Exportiert den markierten/bearbeiteten Sonogramm-Ausschnitt
 * im wissenschaftlichen Feldfuehrer-Stil (Glutz/Svensson).
 *
 * Physikalische Massstab-Konstanten:
 * - 300 DPI Druckqualitaet
 * - Frequenz: 2 kHz pro cm (Y-Achse), Bereich Standard 9-12 kHz
 * - Zeit: 0.5 sec Markierungen, 25 mm pro Sekunde (X-Achse)
 * - Neue Zeile alle 4 Sekunden
 * - 1 cm Rand um das gesamte Bild
 * - Metadaten unsichtbar in PNG tEXt-Chunks (inkl. Dynamik-Zuordnung)
 */
object ImageExporter {

    // ═══ Physikalische Konstanten ═══
    private const val DPI = 600
    private const val MM_PER_INCH = 25.4f

    // Zeitachse: 5.5 cm = 1 sec (Glutz-Referenz)
    private const val MM_PER_SEC = 55f
    // Frequenzachse: 2 kHz pro 2 cm = 1 kHz pro cm (Glutz-Referenz)
    private const val HZ_PER_CM = 1000f
    // Neue Zeile alle 3.5 Sekunden (Glutz-Referenz)
    private const val SECONDS_PER_ROW = 3.5f

    // Frequenzbereich: 0 Hz Grundlinie, Oberkante aus Daten/maxFreqHz
    private const val DEFAULT_FREQ_MIN_HZ = 0f
    private const val DEFAULT_FREQ_MAX_HZ = 16000f

    // Abgeleitete Pixel-Konstanten
    private val PX_PER_SEC = (MM_PER_SEC / MM_PER_INCH * DPI).roundToInt()  // ~1181 px/sec
    private val PX_PER_KHZ = (10f / MM_PER_INCH * DPI / (HZ_PER_CM / 1000f)).roundToInt()  // ~236 px/kHz

    // 1 cm Rand um das gesamte Bild
    private val OUTER_MARGIN = (10f / MM_PER_INCH * DPI).roundToInt()  // ~236 px

    // Innere Raender (Platz fuer Achsenbeschriftung innerhalb des Rands)
    private val AXIS_LABEL_WIDTH = (10f / MM_PER_INCH * DPI).roundToInt()  // kHz-Zahlen links
    private val AXIS_LABEL_HEIGHT = (8f / MM_PER_INCH * DPI).roundToInt()  // sec-Zahlen unten
    private val LABEL_HEIGHT = (6f / MM_PER_INCH * DPI).roundToInt()       // Markierungsname oben
    private val ROW_GAP = (5f / MM_PER_INCH * DPI).roundToInt()            // Abstand zwischen Zeilen

    /**
     * Exportiert den aktuellen Zoom-Ausschnitt.
     *
     * @param blackAndWhite true = Schwarz-Weiss (hohe Energie = schwarz), false = aktive Farbpalette
     */
    fun export(
        spectrogramData: SpectrogramData,
        annotation: Annotation? = null,
        outputFile: File,
        viewStartSec: Float,
        viewEndSec: Float,
        label: String = "",
        speciesName: String = "",
        filterDescription: String = "",
        blackAndWhite: Boolean = false,
        /** true wenn spectrogramData lineare Frequenz-Bins hat (STFT), false fuer Mel */
        linearFreqData: Boolean = false,
        /** Gefilterte Display-Daten (Mel) als Maske: wo Wert ≈ minValue → Hintergrund */
        filterMask: SpectrogramData? = null,
        /** Ungefilterte Display-Daten fuer Schwellenwert-Berechnung */
        filterMaskOriginal: SpectrogramData? = null,
        /** Export-Frequenzbereich aus Settings (Hz) — ueberschreibt Defaults */
        exportFreqMinHz: Float = DEFAULT_FREQ_MIN_HZ,
        exportFreqMaxHz: Float = DEFAULT_FREQ_MAX_HZ,
        /** Schrittweite fuer Frequenz-Achse in Hz (z.B. 2000 = 2kHz pro cm) */
        exportFreqStepHz: Float = HZ_PER_CM,
        /** Sekunden pro cm auf Zeitachse (Standard ~1.818 = 55mm/sec) */
        secPerCm: Float = MM_PER_SEC / 10f,
        /** Zeilenlaenge in cm (Standard 19.25) */
        rowLengthCm: Float = SECONDS_PER_ROW * MM_PER_SEC / 10f
    ) {
        // Dynamische Berechnungen basierend auf Settings
        val mmPerSec = 10f / secPerCm  // cm/sec → mm/sec
        val pxPerSec = (mmPerSec / MM_PER_INCH * DPI).roundToInt()
        val secondsPerRow = rowLengthCm * secPerCm
        val pxPerKhz = (10f / MM_PER_INCH * DPI / (exportFreqStepHz / 1000f)).roundToInt()

        // Angeforderte Dauer (für Achsenbeschriftung und Pixel-Breite)
        val duration = viewEndSec - viewStartSec
        // Tatsächliche Dauer der Spektrogramm-Daten (kann kürzer sein wegen FFT-Fenstergrösse)
        val spectrogramDuration = spectrogramData.durationSec
        if (duration <= 0f || spectrogramData.nFrames == 0) return

        // Frequenzbereich aus Settings
        val dataFMin = spectrogramData.fMin
        val dataFMax = spectrogramData.fMax

        val displayFMin = exportFreqMinHz
        val displayFMax = exportFreqMaxHz.coerceAtMost(dataFMax)

        val freqRange = displayFMax - displayFMin
        // Pixel pro kHz: 1cm pro exportFreqStepHz → (10mm / 25.4) * DPI pro (stepHz/1000) kHz
        val pxPerKHz = (10f / MM_PER_INCH * DPI / (exportFreqStepHz / 1000f)).roundToInt()

        // Bildgroessen berechnen
        val rowPlotH = ((freqRange / 1000f) * pxPerKHz).roundToInt()
        val rowPlotW = (secondsPerRow * pxPerSec).roundToInt()
        val numRows = ceil(duration / secondsPerRow).toInt().coerceAtLeast(1)

        // Innerer Bereich (Achsen + Plots)
        val innerW = AXIS_LABEL_WIDTH + rowPlotW + 10  // 10px Puffer rechts
        val innerH = LABEL_HEIGHT + numRows * rowPlotH + (numRows - 1) * ROW_GAP + AXIS_LABEL_HEIGHT

        // Gesamtbild mit 1cm Rand
        val totalW = 2 * OUTER_MARGIN + innerW
        val totalH = 2 * OUTER_MARGIN + innerH

        val image = BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        // Hintergrund weiss
        g.color = Color.WHITE
        g.fillRect(0, 0, totalW, totalH)

        // Schriften
        // Schriftgroessen in Punkt (pt) → Pixel: pt * DPI / 72
        val axisFont = Font("SansSerif", Font.PLAIN, (8f * DPI / 72f).roundToInt())   // 8 pt Achsenzahlen
        val unitFont = Font("SansSerif", Font.ITALIC, (10f * DPI / 72f).roundToInt())  // 10 pt Einheiten
        val labelFont = Font("SansSerif", Font.BOLD, (12f * DPI / 72f).roundToInt())   // 12 pt Artname

        // Annotation-Frequenzbereich fuer farbige Darstellung
        val annLowHz = annotation?.lowFreqHz ?: displayFMin
        val annHighFreq = annotation?.highFreqHz ?: displayFMax

        // Offset: Beginn des inneren Bereichs
        val originX = OUTER_MARGIN
        val originY = OUTER_MARGIN

        // Markierungsname ZENTRIERT am oberen Rand
        if (label.isNotEmpty()) {
            g.font = labelFont
            g.color = Color.BLACK
            val fm = g.fontMetrics
            val labelW = fm.stringWidth(label)
            val plotCenterX = originX + AXIS_LABEL_WIDTH + rowPlotW / 2
            g.drawString(label, plotCenterX - labelW / 2, originY + fm.ascent)
        }

        // ═══ Jede Zeile rendern ═══
        for (row in 0 until numRows) {
            val rowStartSec = row * secondsPerRow
            val rowEndSec = minOf((row + 1) * secondsPerRow, duration)
            val rowDuration = rowEndSec - rowStartSec

            val plotX = originX + AXIS_LABEL_WIDTH
            val plotY = originY + LABEL_HEIGHT + row * (rowPlotH + ROW_GAP)
            val actualPlotW = (rowDuration * pxPerSec).roundToInt()

            // Frame-Bereich: mappen über tatsächliche Spektrogramm-Dauer
            // (kann kürzer sein als duration wegen FFT-Fenstergrösse)
            val dataDur = if (spectrogramDuration > 0f) spectrogramDuration else duration
            val startFrame = ((rowStartSec / dataDur) * spectrogramData.nFrames).toInt()
                .coerceIn(0, spectrogramData.nFrames)
            val endFrame = ((rowEndSec.coerceAtMost(dataDur) / dataDur) * spectrogramData.nFrames).toInt()
                .coerceIn(startFrame, spectrogramData.nFrames)
            val frameCount = endFrame - startFrame

            // ═══ Direkte Pixel-Berechnung mit Area-Averaging ═══
            // Kein Java2D Skalierung — jeder Pixel wird direkt aus den Daten berechnet.
            // Wenn mehrere Bins/Frames in einen Pixel fallen → Mittelwert.
            // Normalisierung: Top-30dB + Gamma → weiche Glutz-Graustufen.
            val gamma = 0.65f
            val dataMax = spectrogramData.maxValue
            val dataMin = spectrogramData.minValue
            // Dynamikbereich = Differenz zwischen lautestem und leisestem Wert
            // Gefilterte Daten: minValue ist der "Stille"-Wert (NoiseFilter setzt darauf)
            // Floor = knapp über minValue → alles was der Filter auf min gesetzt hat = weiss
            val actualRange = dataMax - dataMin
            val dataFloor = dataMin + actualRange * 0.02f  // 2% über Minimum = weiss-Schwelle
            val displayRange = dataMax - dataFloor

            if (frameCount > 0) {
                val nBins = spectrogramData.nMels
                val dataFRange = dataFMax - dataFMin

                for (py in 0 until rowPlotH) {
                    val linFrac = 1f - py.toFloat() / rowPlotH
                    val freqHz = displayFMin + linFrac * freqRange
                    val inAnn = freqHz in annLowHz..annHighFreq
                    val inData = freqHz in dataFMin..dataFMax

                    if (!inAnn || !inData) {
                        val bgRgb = Color.WHITE.rgb
                        for (px in 0 until actualPlotW) {
                            image.setRGB(plotX + px, plotY + py, bgRgb)
                        }
                        continue
                    }

                    // Frequenz-Bereich dieses Pixels (in Bins)
                    val freqLo = displayFMin + (1f - (py + 1f) / rowPlotH) * freqRange
                    val freqHi = displayFMin + (1f - py.toFloat() / rowPlotH) * freqRange
                    val binLoF = if (linearFreqData) {
                        ((freqLo - dataFMin) / dataFRange * nBins)
                    } else {
                        val mel = ch.etasystems.amsel.core.spectrogram.MelFilterbank.hzToMel(freqLo)
                        val melMin = ch.etasystems.amsel.core.spectrogram.MelFilterbank.hzToMel(dataFMin)
                        val melMax = ch.etasystems.amsel.core.spectrogram.MelFilterbank.hzToMel(dataFMax)
                        ((mel - melMin) / (melMax - melMin) * nBins)
                    }
                    val binHiF = if (linearFreqData) {
                        ((freqHi - dataFMin) / dataFRange * nBins)
                    } else {
                        val mel = ch.etasystems.amsel.core.spectrogram.MelFilterbank.hzToMel(freqHi)
                        val melMin = ch.etasystems.amsel.core.spectrogram.MelFilterbank.hzToMel(dataFMin)
                        val melMax = ch.etasystems.amsel.core.spectrogram.MelFilterbank.hzToMel(dataFMax)
                        ((mel - melMin) / (melMax - melMin) * nBins)
                    }
                    val b0 = binLoF.toInt().coerceIn(0, nBins - 1)
                    val b1 = binHiF.toInt().coerceIn(b0, nBins - 1)

                    for (px in 0 until actualPlotW) {
                        // Zeit-Bereich dieses Pixels (in Frames)
                        val frameFrac0 = px.toFloat() / actualPlotW
                        val frameFrac1 = (px + 1f) / actualPlotW
                        val f0 = (startFrame + frameFrac0 * frameCount).toInt().coerceIn(startFrame, startFrame + frameCount - 1)
                        val f1 = (startFrame + frameFrac1 * frameCount).toInt().coerceIn(f0, startFrame + frameCount - 1)

                        // Area-Average: alle Bins und Frames die in diesen Pixel fallen
                        var sum = 0f
                        var count = 0
                        for (b in b0..b1) {
                            for (f in f0..f1) {
                                sum += spectrogramData.valueAt(b, f)
                                count++
                            }
                        }
                        val avgVal = if (count > 0) sum / count else dataFloor

                        // Normalisierung: tatsächlicher Dynamikbereich + Gamma
                        val norm = ((avgVal - dataFloor) / displayRange).coerceIn(0f, 1f).pow(gamma)

                        val color: Int = if (blackAndWhite) {
                            val gray = (255 * (1f - norm)).roundToInt().coerceIn(0, 255)
                            (gray shl 16) or (gray shl 8) or gray
                        } else if (norm < 0.05f) {
                            0xFFFFFF  // Hintergrund weiss fuer Druck
                        } else {
                            val (cr, cg, cb) = Colormap.mapValueRgb(norm)
                            (cr shl 16) or (cg shl 8) or cb
                        }
                        image.setRGB(plotX + px, plotY + py, 0xFF000000.toInt() or color)
                    }
                }
            }

            // Rahmen
            g.color = Color.BLACK
            g.stroke = BasicStroke(1.5f)
            g.drawRect(plotX, plotY, actualPlotW, rowPlotH)

            // ═══ kHz-Achse (links, LINEAR, Haupt=stepHz, Zwischen=stepHz/2) ═══
            g.font = axisFont
            val kHzMajor = exportFreqStepHz
            val kHzMinor = exportFreqStepHz / 2f
            val majorTickLen = (1.5f / MM_PER_INCH * DPI).roundToInt()
            val minorTickLen = (0.8f / MM_PER_INCH * DPI).roundToInt()

            // Frequenz-Striche ab 1 kHz (0 wird nur einmal am Ursprung geschrieben)
            var freq = kHzMinor
            while (freq <= displayFMax) {
                val yFrac = ((freq - displayFMin) / freqRange).coerceIn(0f, 1f)
                val y = plotY + rowPlotH - (yFrac * rowPlotH).toInt()

                if (y in plotY..(plotY + rowPlotH - 3)) {
                    val isMajor = (freq % kHzMajor) < 0.1f

                    if (isMajor) {
                        // Hilfslinie
                        g.color = Color(200, 200, 200)
                        g.stroke = BasicStroke(0.5f)
                        g.drawLine(plotX + 1, y, plotX + actualPlotW - 1, y)
                        // Hauptstrich + Beschriftung
                        g.color = Color.BLACK
                        g.stroke = BasicStroke(1.5f)
                        g.drawLine(plotX - majorTickLen, y, plotX, y)
                        val freqLabel = "${(freq / 1000f).roundToInt()}"
                        val fm = g.fontMetrics
                        g.drawString(freqLabel, plotX - majorTickLen - fm.stringWidth(freqLabel) - 4,
                            y + fm.ascent / 2 - 1)
                    } else {
                        // Zwischenstrich
                        g.color = Color.BLACK
                        g.stroke = BasicStroke(1f)
                        g.drawLine(plotX - minorTickLen, y, plotX, y)
                    }
                }
                freq += kHzMinor
            }

            // "kHz" Einheit: linksbündig, vertikal auf Höhe Achsen-Mitte (nur erste Zeile)
            if (row == 0) {
                g.font = unitFont
                g.color = Color.BLACK
                val fm = g.fontMetrics
                // In Flucht mit der Achse, mit Abstand oberhalb des Plotbereichs
                g.drawString("kHz", plotX - majorTickLen - fm.stringWidth("kHz") - 4,
                    plotY - (3f / MM_PER_INCH * DPI).roundToInt())
            }

            // ═══ Zeitachse (unten, RELATIV bei 0, Haupt=0.5s, Zwischen=0.25s) ═══
            g.font = axisFont
            g.color = Color.BLACK
            val timeMajor = 0.5f   // Hauptstriche alle 0.5 sec (mit Zahl)
            val timeMinor = 0.25f  // Zwischenstriche alle 0.25 sec
            val timeMajorTick = (1.5f / MM_PER_INCH * DPI).roundToInt()
            val timeMinorTick = (0.8f / MM_PER_INCH * DPI).roundToInt()

            // "0" nur einmal am Ursprung (linke untere Ecke, erste Zeile)
            if (row == 0) {
                g.font = axisFont
                g.color = Color.BLACK
                val fm = g.fontMetrics
                g.drawString("0", plotX - fm.stringWidth("0") - 4,
                    plotY + rowPlotH + fm.ascent + 3)
            }

            var t = timeMinor  // Start bei 0.25, nicht bei 0 (0 ist schon am Ursprung)
            if (row > 0) t = 0f  // Folgezeilen starten normal
            while (t <= rowDuration + 0.01f) {
                val x = plotX + (t * pxPerSec).roundToInt()
                if (x <= plotX + actualPlotW) {
                    val relTime = rowStartSec + t
                    val isMajor = (relTime % timeMajor) < 0.01f

                    // Vertikale Hilfslinie bei ganzen Sekunden
                    if ((relTime % 1f) < 0.01f && relTime > 0.01f) {
                        g.color = Color(210, 210, 210)
                        g.stroke = BasicStroke(0.5f)
                        g.drawLine(x, plotY + 1, x, plotY + rowPlotH - 1)
                    }

                    g.color = Color.BLACK
                    if (isMajor) {
                        g.stroke = BasicStroke(1.5f)
                        g.drawLine(x, plotY + rowPlotH, x, plotY + rowPlotH + timeMajorTick)
                        val timeLabel = if ((relTime % 1f) < 0.01f) "${"%.0f".format(relTime)}"
                            else "${"%.1f".format(relTime)}"
                        val fm = g.fontMetrics
                        g.drawString(timeLabel, x - fm.stringWidth(timeLabel) / 2,
                            plotY + rowPlotH + timeMajorTick + fm.ascent + 3)
                    } else {
                        g.stroke = BasicStroke(1f)
                        g.drawLine(x, plotY + rowPlotH, x, plotY + rowPlotH + timeMinorTick)
                    }
                }
                t += timeMinor
            }

            // "sec" Einheit (nur letzte Zeile)
            if (row == numRows - 1) {
                g.font = unitFont
                val fm = g.fontMetrics
                g.color = Color.BLACK
                g.drawString("sec", plotX + actualPlotW + 4,
                    plotY + rowPlotH + timeMajorTick + fm.ascent)
            }
        }

        g.dispose()

        // ═══ Metadaten in PNG-Chunks speichern ═══
        val events = EventDetector.detect(
            spectrogramData, viewStartSec, viewEndSec,
            thresholdFactor = 1.5f, minDurationSec = 0.05f, maxDurationSec = 8f
        )
        val metadata = buildMetadataMap(
            spectrogramData, events, viewStartSec, viewEndSec,
            speciesName, filterDescription,
            displayFMin, displayFMax, blackAndWhite
        )

        outputFile.parentFile?.mkdirs()
        writePngWithMetadata(image, outputFile, metadata)
    }

    /**
     * Formatiert absolute Zeit: "0.0", "0.5", "1.0" etc.
     * Ab 60s: "1:00", "1:30" etc.
     */
    private fun formatAbsoluteTime(sec: Float): String {
        val totalTenths = (sec * 10).roundToInt()
        val isWhole = totalTenths % 10 == 0

        return if (sec >= 60f) {
            val m = (sec / 60).toInt()
            val s = (sec % 60)
            if (isWhole) "$m:${"%02d".format(s.toInt())}"
            else "$m:${"%04.1f".format(s)}"
        } else {
            if (isWhole) "${"%.0f".format(sec)}"
            else "${"%.1f".format(sec)}"
        }
    }

    /**
     * Schreibt ein PNG mit unsichtbaren tEXt-Chunks fuer Metadaten + DPI.
     */
    private fun writePngWithMetadata(
        image: BufferedImage,
        file: File,
        metadata: Map<String, String>
    ) {
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        val writeParam = writer.defaultWriteParam
        val typeSpec = ImageTypeSpecifier.createFromBufferedImageType(image.type)
        val imgMetadata = writer.getDefaultImageMetadata(typeSpec, writeParam)

        val root = imgMetadata.getAsTree("javax_imageio_png_1.0") as IIOMetadataNode

        for ((key, value) in metadata) {
            val textEntry = IIOMetadataNode("tEXtEntry")
            textEntry.setAttribute("keyword", key)
            textEntry.setAttribute("value", value)

            var textNode = root.getElementsByTagName("tEXt").let {
                if (it.length > 0) it.item(0) as IIOMetadataNode else null
            }
            if (textNode == null) {
                textNode = IIOMetadataNode("tEXt")
                root.appendChild(textNode)
            }
            textNode.appendChild(textEntry)
        }

        // pHYs-Chunk: DPI korrekt in PNG einbetten
        // pixelsPerUnit = DPI / 25.4 * 1000 (Meter statt Inch, unitSpecifier=1 = Meter)
        val pxPerMeter = (DPI / MM_PER_INCH * 1000f).roundToInt()
        val phys = IIOMetadataNode("pHYs")
        phys.setAttribute("pixelsPerUnitXAxis", "$pxPerMeter")
        phys.setAttribute("pixelsPerUnitYAxis", "$pxPerMeter")
        phys.setAttribute("unitSpecifier", "meter")  // 1 = Meter
        root.appendChild(phys)

        imgMetadata.mergeTree("javax_imageio_png_1.0", root)

        // Standard-Metadaten (javax_imageio_1.0) auch setzen (fuer Programme die pHYs ignorieren)
        try {
            val stdRoot = imgMetadata.getAsTree("javax_imageio_1.0") as IIOMetadataNode
            var dimNode = stdRoot.getElementsByTagName("Dimension").let {
                if (it.length > 0) it.item(0) as IIOMetadataNode else null
            }
            if (dimNode == null) {
                dimNode = IIOMetadataNode("Dimension")
                stdRoot.appendChild(dimNode)
            }
            val hps = IIOMetadataNode("HorizontalPixelSize")
            hps.setAttribute("value", "${MM_PER_INCH / DPI}")
            dimNode.appendChild(hps)
            val vps = IIOMetadataNode("VerticalPixelSize")
            vps.setAttribute("value", "${MM_PER_INCH / DPI}")
            dimNode.appendChild(vps)
            imgMetadata.mergeTree("javax_imageio_1.0", stdRoot)
        } catch (_: Exception) { }

        file.outputStream().use { out ->
            writer.output = ImageIO.createImageOutputStream(out)
            writer.write(null, IIOImage(image, null, imgMetadata), writeParam)
            writer.dispose()
        }
    }

    /**
     * Baut die Metadaten-Map fuer PNG tEXt-Chunks.
     */
    private fun buildMetadataMap(
        data: SpectrogramData,
        events: List<EventDetector.DetectedEvent>,
        viewStartSec: Float,
        viewEndSec: Float,
        speciesName: String,
        filterDescription: String,
        displayFMin: Float,
        displayFMax: Float,
        blackAndWhite: Boolean
    ): Map<String, String> {
        val duration = viewEndSec - viewStartSec
        val dynamicRange = data.maxValue - data.minValue
        val signalMinF = events.minOfOrNull { it.lowFreqHz }?.roundToInt() ?: data.fMin.roundToInt()
        val signalMaxF = events.maxOfOrNull { it.highFreqHz }?.roundToInt() ?: data.fMax.roundToInt()
        val signalDuration = events.sumOf { (it.endSec - it.startSec).toDouble() }.toFloat()

        val meta = mutableMapOf<String, String>()

        meta["Software"] = "AMSEL"
        meta["AMSEL:Version"] = "0.0.4"

        // Wissenschaftliche Metadaten aus Settings
        try {
            val settings = ch.etasystems.amsel.data.SettingsStore.load()
            if (settings.operatorName.isNotBlank()) meta["AMSEL:Operator"] = settings.operatorName
            if (settings.deviceName.isNotBlank()) meta["AMSEL:Device"] = settings.deviceName
            if (settings.locationName.isNotBlank()) meta["AMSEL:Location"] = settings.locationName
            meta["AMSEL:Coordinates"] = "%.4f, %.4f".format(settings.locationLat, settings.locationLon)
        } catch (_: Exception) { }

        if (speciesName.isNotBlank()) {
            meta["AMSEL:Species"] = speciesName
        }

        // Frequenz
        meta["AMSEL:FreqMin_Hz"] = "${data.fMin.roundToInt()}"
        meta["AMSEL:FreqMax_Hz"] = "${data.fMax.roundToInt()}"
        meta["AMSEL:DisplayFreqMin_Hz"] = "${displayFMin.roundToInt()}"
        meta["AMSEL:DisplayFreqMax_Hz"] = "${displayFMax.roundToInt()}"
        meta["AMSEL:SignalFreqMin_Hz"] = "$signalMinF"
        meta["AMSEL:SignalFreqMax_Hz"] = "$signalMaxF"

        // Dynamik — Zuordnung zur Darstellung
        meta["AMSEL:DynamicRange_dB"] = "${"%.1f".format(dynamicRange)}"
        meta["AMSEL:MinValue_log"] = "${"%.2f".format(data.minValue)}"
        meta["AMSEL:MaxValue_log"] = "${"%.2f".format(data.maxValue)}"
        meta["AMSEL:RenderMode"] = if (blackAndWhite) "grayscale_inverted" else Colormap.getActivePalette().name
        meta["AMSEL:DynamicMapping"] = if (blackAndWhite) {
            "inverted_linear: log10(energy) [${
                "%.2f".format(data.minValue)}..${
                "%.2f".format(data.maxValue)}] -> grayscale [255..0] (hohe Energie=schwarz)"
        } else {
            "linear: log10(energy) [${
                "%.2f".format(data.minValue)}..${
                "%.2f".format(data.maxValue)}] -> ${Colormap.getActivePalette().name} [0..255]"
        }

        // Zeit
        meta["AMSEL:ViewStartSec"] = "${"%.3f".format(viewStartSec)}"
        meta["AMSEL:ViewEndSec"] = "${"%.3f".format(viewEndSec)}"
        meta["AMSEL:Duration_sec"] = "${"%.2f".format(duration)}"
        meta["AMSEL:SignalDuration_sec"] = "${"%.2f".format(signalDuration)}"
        meta["AMSEL:EventCount"] = "${events.size}"

        if (events.isNotEmpty()) {
            val lengths = events.map { "${"%.3f".format(it.endSec - it.startSec)}" }
            meta["AMSEL:SignalLengths_sec"] = lengths.joinToString(",")
        }

        // Darstellung / Massstab
        meta["AMSEL:DPI"] = "$DPI"
        meta["AMSEL:Scale_mm_per_sec"] = "${MM_PER_SEC.roundToInt()}"
        meta["AMSEL:Scale_Hz_per_cm"] = "${HZ_PER_CM.roundToInt()}"
        meta["AMSEL:SecondsPerRow"] = "${"%.1f".format(SECONDS_PER_ROW)}"
        meta["AMSEL:SpectrogramFFT"] = "${data.nMels} Mels, hop ${data.hopSize}, SR ${data.sampleRate}"

        if (filterDescription.isNotBlank()) {
            meta["AMSEL:Filter"] = filterDescription
        }

        return meta
    }
}
