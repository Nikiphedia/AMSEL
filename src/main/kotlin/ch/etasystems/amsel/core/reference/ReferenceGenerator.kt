package ch.etasystems.amsel.core.reference

import ch.etasystems.amsel.core.audio.AudioDecoder
import ch.etasystems.amsel.core.audio.AudioSegment
import ch.etasystems.amsel.core.detection.EventDetector
import ch.etasystems.amsel.core.spectrogram.MelSpectrogram
import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Generiert wissenschaftliche Referenz-Sonogramme im Feldfuehrer-Stil (Glutz, Svensson).
 *
 * Renderung nach ornithologischem Standard:
 * - Schwarz auf Weiss (Energie = Schwaerze)
 * - kHz-Achse ABSOLUT links (2 kHz pro cm bei 300 DPI)
 * - Zeitachse RELATIV unten (0.5 sec Schritte, 25mm pro sec bei 300 DPI)
 * - Neue Zeile alle 4 Sekunden
 * - Mehrzeilen-Layout fuer laengere Aufnahmen (wie Glutz-Tafeln)
 * - 300 DPI Druckqualitaet
 *
 * Metadaten werden als unsichtbare PNG-Chunks oder Begleit-JSON gespeichert.
 */
object ReferenceGenerator {

    // ═════════════════════════════════════════════════════════════
    // Physikalische Konstanten (Druckmassstab)
    // ═════════════════════════════════════════════════════════════

    const val DPI = 300                        // Druckaufloesung
    const val MM_PER_INCH = 25.4f

    // Zeitachse: 25 mm pro Sekunde → 1 sec = 25mm * (300/25.4) px = ~295 px
    const val MM_PER_SEC = 25f
    val PX_PER_SEC = (MM_PER_SEC / MM_PER_INCH * DPI).roundToInt()  // ~295 px/sec

    // Frequenzachse: 2 kHz pro cm = 2000 Hz pro 10mm
    const val HZ_PER_CM = 2000f
    const val MM_PER_CM = 10f
    val PX_PER_KHZ = ((MM_PER_CM / MM_PER_INCH * DPI) / (HZ_PER_CM / 1000f)).roundToInt()  // ~59 px/kHz

    // Zeile: 4 Sekunden
    const val SECONDS_PER_ROW = 4f

    // Raender (in px bei 300 DPI)
    val MARGIN_LEFT = (12f / MM_PER_INCH * DPI).roundToInt()     // 12mm fuer kHz-Achse
    val MARGIN_RIGHT = (3f / MM_PER_INCH * DPI).roundToInt()     // 3mm rechts
    val MARGIN_TOP = (3f / MM_PER_INCH * DPI).roundToInt()       // 3mm oben
    val MARGIN_BOTTOM = (8f / MM_PER_INCH * DPI).roundToInt()    // 8mm fuer sec-Achse
    val ROW_GAP = (4f / MM_PER_INCH * DPI).roundToInt()          // 4mm zwischen Zeilen

    /** Parameter fuer die Referenz-Erzeugung */
    data class RefConfig(
        val maxDurationSec: Float = 30f,      // Max. Ausschnittlaenge (mehrere Zeilen moeglich)
        val minDurationSec: Float = 1.5f,     // Min. Ausschnittlaenge
        val paddingSec: Float = 0.3f,         // Rand um erkannten Event
        val fMin: Float = 1000f,              // Untere Frequenzgrenze Hz
        val fMax: Float = 11000f,             // Obere Frequenzgrenze Hz
        val label: String = "",               // z.B. "A" oder "B" (Zeilen-Label)
        val secondsPerRow: Float = SECONDS_PER_ROW,
        val fftSize: Int = 4096,              // FFT-Groesse (4096 fuer hohe Frequenzaufloesung)
        val hopSize: Int = 64,                // Hop-Size (64 fuer hohe Zeitaufloesung)
        val nMels: Int = 256                  // Mel-Bins (256 fuer hohe Frequenzaufloesung)
    )

    /** Metadaten die mit dem Sonogramm gespeichert werden */
    data class SonogramMetadata(
        val fMinHz: Float,
        val fMaxHz: Float,
        val durationSec: Float,
        val dynamicRangeDb: Float,          // Dynamikumfang
        val peakFreqHz: Float,              // Dominante Frequenz
        val minSignalFreqHz: Float,         // Untere Signalgrenze
        val maxSignalFreqHz: Float,         // Obere Signalgrenze
        val signalDurationSec: Float,       // Gesamte Signal-Dauer (ohne Pausen)
        val eventCount: Int,                // Anzahl erkannter Einzelsignale
        val dpiX: Int = DPI,
        val dpiY: Int = DPI,
        val scaleXmmPerSec: Float = MM_PER_SEC,
        val scaleYhzPerCm: Float = HZ_PER_CM
    )

    // ═════════════════════════════════════════════════════════════
    // Haupt-API
    // ═════════════════════════════════════════════════════════════

    /**
     * Generiert ein Referenz-Sonogramm aus einer Audio-Datei.
     * @return Metadaten oder null bei Fehler
     */
    fun generate(
        audioFile: File,
        outputFile: File,
        config: RefConfig = RefConfig()
    ): Boolean {
        return try {
            val segment = AudioDecoder.decode(audioFile)
            val result = generateFromSegment(segment, outputFile, config)
            result
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Generiert Referenz direkt aus AudioSegment.
     * @return true bei Erfolg
     */
    fun generateFromSegment(
        segment: AudioSegment,
        outputFile: File,
        config: RefConfig = RefConfig()
    ): Boolean {
        try {
            // 1. Hochaufloesend Spektrogramm berechnen
            val hiResSpec = MelSpectrogram(
                fftSize = config.fftSize,
                hopSize = config.hopSize,
                nMels = config.nMels,
                fMin = config.fMin,
                fMax = config.fMax,
                sampleRate = segment.sampleRate
            )
            val spectrogramData = hiResSpec.compute(segment.samples)

            // 2. Events erkennen (fuer Metadaten)
            val events = EventDetector.detect(
                spectrogramData, 0f, segment.durationSec,
                thresholdFactor = 1.5f, minDurationSec = 0.05f, maxDurationSec = 8f
            )

            // 3. Metadaten berechnen
            val metadata = computeMetadata(spectrogramData, events, segment.durationSec, config)

            // 4. Bild rendern
            val image = renderMultiRow(spectrogramData, segment.durationSec, config)

            // 5. Speichern
            outputFile.parentFile?.mkdirs()
            ImageIO.write(image, "png", outputFile)

            // 6. Metadaten als JSON neben das Bild
            val metaFile = File(outputFile.parentFile, outputFile.nameWithoutExtension + "_meta.json")
            metaFile.writeText(metadataToJson(metadata))

            return true
        } catch (_: Exception) {
            return false
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Mehrzeilen-Renderer (Glutz-Stil)
    // ═════════════════════════════════════════════════════════════

    private fun renderMultiRow(
        data: SpectrogramData,
        totalDurationSec: Float,
        config: RefConfig
    ): BufferedImage {
        val freqRange = config.fMax - config.fMin
        val freqRangeKHz = freqRange / 1000f

        // Plothoehe einer Zeile basierend auf Frequenzbereich (2kHz/cm)
        val rowPlotH = (freqRangeKHz * PX_PER_KHZ).roundToInt()

        // Plotbreite einer Zeile basierend auf Zeitdauer (25mm/sec, max 4s/Zeile)
        val secPerRow = config.secondsPerRow
        val rowPlotW = (secPerRow * PX_PER_SEC).roundToInt()

        // Anzahl Zeilen
        val numRows = ceil(totalDurationSec / secPerRow).toInt().coerceAtLeast(1)

        // Gesamtbildgroesse
        val totalW = MARGIN_LEFT + rowPlotW + MARGIN_RIGHT
        val totalH = MARGIN_TOP + numRows * rowPlotH + (numRows - 1) * ROW_GAP + MARGIN_BOTTOM

        val image = BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        // Weisser Hintergrund
        g.color = Color.WHITE
        g.fillRect(0, 0, totalW, totalH)

        val axisFont = Font("SansSerif", Font.PLAIN, (DPI * 0.028f).roundToInt()) // ~8pt bei 300 DPI
        val labelFont = Font("SansSerif", Font.BOLD, (DPI * 0.04f).roundToInt())  // ~12pt Label
        g.stroke = BasicStroke(1.5f)

        // ── Jede Zeile rendern ──
        for (row in 0 until numRows) {
            val rowStartSec = row * secPerRow
            val rowEndSec = minOf((row + 1) * secPerRow, totalDurationSec)
            val rowDuration = rowEndSec - rowStartSec

            val plotX = MARGIN_LEFT
            val plotY = MARGIN_TOP + row * (rowPlotH + ROW_GAP)
            val actualPlotW = (rowDuration * PX_PER_SEC).roundToInt()

            // Frames fuer diese Zeile
            val startFrame = ((rowStartSec / totalDurationSec) * data.nFrames).toInt().coerceIn(0, data.nFrames)
            val endFrame = ((rowEndSec / totalDurationSec) * data.nFrames).toInt().coerceIn(startFrame, data.nFrames)
            val frameCount = endFrame - startFrame

            // Sonogramm rendern (schwarz auf weiss)
            if (frameCount > 0) {
                for (py in 0 until rowPlotH) {
                    val melFrac = 1f - py.toFloat() / rowPlotH
                    val mel = (melFrac * data.nMels).toInt().coerceIn(0, data.nMels - 1)

                    for (px in 0 until actualPlotW) {
                        val frameFrac = px.toFloat() / actualPlotW
                        val frame = (startFrame + frameFrac * frameCount).toInt().coerceIn(startFrame, endFrame - 1)

                        val norm = data.normalizedValueAt(mel, frame)
                        // Invertiert: hohe Energie = schwarz
                        val gray = (255 * (1f - norm)).toInt().coerceIn(0, 255)
                        image.setRGB(plotX + px, plotY + py, Color(gray, gray, gray).rgb)
                    }
                }
            }

            // ── Rahmen ──
            g.color = Color.BLACK
            g.drawRect(plotX, plotY, actualPlotW, rowPlotH)

            // ── kHz-Achse (links, absolut, 2kHz Schritte) ──
            g.font = axisFont
            val kHzStep = 2000f  // 2 kHz pro cm
            var freq = (ceil(config.fMin / kHzStep) * kHzStep).toFloat()
            while (freq <= config.fMax) {
                val yFrac = (freq - config.fMin) / freqRange
                val y = plotY + rowPlotH - (yFrac * rowPlotH).toInt()

                if (y in (plotY + 5)..(plotY + rowPlotH - 5)) {
                    // Horizontale Hilfslinie (fein, grau)
                    g.color = Color(180, 180, 180)
                    g.stroke = BasicStroke(0.5f)
                    g.drawLine(plotX + 1, y, plotX + actualPlotW - 1, y)

                    // Tick
                    g.color = Color.BLACK
                    g.stroke = BasicStroke(1.5f)
                    g.drawLine(plotX - 4, y, plotX, y)

                    // Label
                    val label = "${(freq / 1000f).roundToInt()}"
                    val fm = g.fontMetrics
                    g.drawString(label, plotX - fm.stringWidth(label) - 6, y + fm.ascent / 2 - 1)
                }
                freq += kHzStep
            }

            // "kHz" Label (nur erste Zeile)
            if (row == 0) {
                g.font = Font("SansSerif", Font.PLAIN, (DPI * 0.025f).roundToInt())
                g.color = Color.BLACK
                g.drawString("kHz", 2, plotY + (DPI * 0.025f).roundToInt())
            }

            // Zeilen-Label (A, B, C... wenn gesetzt)
            if (config.label.isNotEmpty() && row == 0) {
                g.font = labelFont
                g.color = Color.BLACK
                g.drawString(config.label, plotX + 6, plotY + g.fontMetrics.ascent + 4)
            }

            // ── Zeitachse (unten, relativ, 0.5 sec Schritte) ──
            g.font = axisFont
            g.color = Color.BLACK
            val secStep = 0.5f  // 0.5 sec Schritte (25mm pro sec → 12.5mm pro 0.5sec)

            var t = 0f
            while (t <= rowDuration + 0.01f) {
                val x = plotX + (t * PX_PER_SEC).roundToInt()
                if (x <= plotX + actualPlotW) {
                    // Vertikale Hilfslinie (nur bei ganzen Sekunden)
                    if (t > 0f && t % 1f < 0.01f) {
                        g.color = Color(200, 200, 200)
                        g.stroke = BasicStroke(0.5f)
                        g.drawLine(x, plotY + 1, x, plotY + rowPlotH - 1)
                    }

                    // Tick
                    g.color = Color.BLACK
                    g.stroke = BasicStroke(1.5f)
                    val tickLen = if (t % 1f < 0.01f) 5 else 3  // Lange Ticks bei ganzen Sekunden
                    g.drawLine(x, plotY + rowPlotH, x, plotY + rowPlotH + tickLen)

                    // Label (nur ganze und halbe Sekunden)
                    val absTime = rowStartSec + t
                    val label = if (absTime % 1f < 0.01f) "${"%.0f".format(absTime)}"
                    else "${"%.1f".format(absTime)}"

                    val fm = g.fontMetrics
                    g.drawString(label, x - fm.stringWidth(label) / 2, plotY + rowPlotH + tickLen + fm.ascent + 2)
                }
                t += secStep
            }

            // "sec" Label (letzte Zeile)
            if (row == numRows - 1) {
                g.font = Font("SansSerif", Font.PLAIN, (DPI * 0.025f).roundToInt())
                val fm = g.fontMetrics
                g.drawString("sec", plotX + actualPlotW - fm.stringWidth("sec"), plotY + rowPlotH + 5 + fm.ascent + 2)
            }
        }

        g.dispose()
        return image
    }

    // ═════════════════════════════════════════════════════════════
    // Metadaten
    // ═════════════════════════════════════════════════════════════

    private fun computeMetadata(
        data: SpectrogramData,
        events: List<EventDetector.DetectedEvent>,
        durationSec: Float,
        config: RefConfig
    ): SonogramMetadata {
        // Dynamikumfang
        val dynamicRange = data.maxValue - data.minValue

        // Dominante Frequenz (Mel-Bin mit hoechster Gesamtenergie)
        var maxEnergy = 0f
        var maxMel = 0
        for (mel in 0 until data.nMels) {
            var energy = 0f
            for (frame in 0 until data.nFrames) {
                energy += data.valueAt(mel, frame)
            }
            if (energy > maxEnergy) {
                maxEnergy = energy
                maxMel = mel
            }
        }
        val peakFreq = config.fMin + (maxMel.toFloat() / data.nMels) * (config.fMax - config.fMin)

        // Signal-Frequenzbereich aus Events
        val minSigFreq = events.minOfOrNull { it.lowFreqHz } ?: config.fMin
        val maxSigFreq = events.maxOfOrNull { it.highFreqHz } ?: config.fMax

        // Signal-Gesamtdauer
        val signalDuration = events.sumOf { (it.endSec - it.startSec).toDouble() }.toFloat()

        return SonogramMetadata(
            fMinHz = config.fMin,
            fMaxHz = config.fMax,
            durationSec = durationSec,
            dynamicRangeDb = dynamicRange,
            peakFreqHz = peakFreq,
            minSignalFreqHz = minSigFreq,
            maxSignalFreqHz = maxSigFreq,
            signalDurationSec = signalDuration,
            eventCount = events.size
        )
    }

    private fun metadataToJson(meta: SonogramMetadata): String {
        return """
{
  "frequenzbereich": {
    "min_hz": ${meta.fMinHz.roundToInt()},
    "max_hz": ${meta.fMaxHz.roundToInt()},
    "signal_min_hz": ${meta.minSignalFreqHz.roundToInt()},
    "signal_max_hz": ${meta.maxSignalFreqHz.roundToInt()},
    "dominant_hz": ${meta.peakFreqHz.roundToInt()}
  },
  "zeit": {
    "dauer_sec": ${"%.2f".format(meta.durationSec)},
    "signal_dauer_sec": ${"%.2f".format(meta.signalDurationSec)},
    "ereignisse": ${meta.eventCount}
  },
  "dynamik": {
    "bereich_db": ${"%.1f".format(meta.dynamicRangeDb)}
  },
  "darstellung": {
    "dpi": ${meta.dpiX},
    "massstab_x_mm_per_sec": ${"%.1f".format(meta.scaleXmmPerSec)},
    "massstab_y_hz_per_cm": ${"%.0f".format(meta.scaleYhzPerCm)},
    "zeile_sec": ${SECONDS_PER_ROW}
  }
}
""".trim()
    }

    // ═════════════════════════════════════════════════════════════
    // Event-basierter Clip-Selector (fuer automatische Auswahl)
    // ═════════════════════════════════════════════════════════════

    fun selectBestClip(
        audioFile: File,
        config: RefConfig = RefConfig()
    ): Pair<Float, Float> {
        val segment = AudioDecoder.decode(audioFile)
        return selectBestClipFromSegment(segment, config)
    }

    fun selectBestClipFromSegment(
        segment: AudioSegment,
        config: RefConfig = RefConfig()
    ): Pair<Float, Float> {
        val spec = MelSpectrogram(
            fftSize = 2048, hopSize = 512, nMels = 80,
            fMin = config.fMin, fMax = config.fMax, sampleRate = segment.sampleRate
        )
        val data = spec.compute(segment.samples)

        val events = EventDetector.detect(
            data, 0f, segment.durationSec,
            thresholdFactor = 1.5f, minDurationSec = 0.05f, maxDurationSec = 8f
        )

        if (events.isEmpty()) {
            return 0f to minOf(config.maxDurationSec, segment.durationSec)
        }

        if (events.size == 1) {
            val e = events[0]
            val center = (e.startSec + e.endSec) / 2f
            val halfDur = maxOf(config.minDurationSec / 2f, (e.endSec - e.startSec) / 2f + config.paddingSec)
            val start = (center - halfDur).coerceAtLeast(0f)
            val end = (center + halfDur).coerceAtMost(segment.durationSec)
            return start to end
        }

        // Dichtestes Cluster
        var bestStart = 0f
        var bestEnd = minOf(config.maxDurationSec, segment.durationSec)
        var bestScore = 0

        for (anchor in events) {
            val wStart = (anchor.startSec - config.paddingSec).coerceAtLeast(0f)
            val wEnd = (wStart + config.maxDurationSec).coerceAtMost(segment.durationSec)

            val inWindow = events.filter { it.startSec >= wStart && it.endSec <= wEnd }
            val score = inWindow.size * 100 + (inWindow.sumOf { it.peakEnergy.toDouble() } * 10).toInt()

            if (score > bestScore) {
                bestScore = score
                bestStart = wStart
                bestEnd = (inWindow.maxOf { it.endSec } + config.paddingSec).coerceAtMost(wEnd)
            }
        }

        if (bestEnd - bestStart < config.minDurationSec) {
            val center = (bestStart + bestEnd) / 2f
            bestStart = (center - config.minDurationSec / 2f).coerceAtLeast(0f)
            bestEnd = (bestStart + config.minDurationSec).coerceAtMost(segment.durationSec)
        }

        return bestStart to bestEnd
    }
}
