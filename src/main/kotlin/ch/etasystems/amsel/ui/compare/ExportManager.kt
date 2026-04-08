package ch.etasystems.amsel.ui.compare

import ch.etasystems.amsel.core.audio.AudioDecoder
import ch.etasystems.amsel.core.audio.AudioSegment
import ch.etasystems.amsel.core.audio.FilteredAudio
import ch.etasystems.amsel.core.export.AudioExporter
import ch.etasystems.amsel.core.export.AudioExportFormat
import ch.etasystems.amsel.core.export.ImageExporter
import ch.etasystems.amsel.core.filter.FilterPipeline
import ch.etasystems.amsel.core.model.gainAtTime
import ch.etasystems.amsel.core.model.gainDbToLinear
import ch.etasystems.amsel.core.spectrogram.MelSpectrogram
import ch.etasystems.amsel.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Exportiert Sonogramm-Bilder (PNG) und Audio (WAV/MP3).
 * Extrahiert aus CompareViewModel (Task 13h).
 */
class ExportManager(
    private val scope: CoroutineScope,
    private val maxFreqHz: () -> Float,
    private val onAuditEntry: (action: String, details: String) -> Unit,
    private val onExportFileChanged: (File) -> Unit,
    private val onLocalStateUpdate: (isProcessing: Boolean?, statusText: String?, sidebarStatus: String?) -> Unit
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Exportiert den aktuellen Bereich (Annotation oder Zoom) als Sonogramm-PNG.
     * Zwei-Pass: Display-Mel fuer Gate-Maske, Hi-Res Mel fuer Ausgabe.
     */
    fun exportAnnotation(outputFile: File, state: CompareUiState) {
        val annotation = state.activeAnnotation
        val settings = SettingsStore.load()
        val minExportSec = settings.minExportDurationSec
        var exportStart: Float
        var exportEnd: Float
        if (annotation != null) {
            exportStart = annotation.startTimeSec
            exportEnd = annotation.endTimeSec
        } else {
            exportStart = state.viewStartSec
            exportEnd = state.viewEndSec
        }
        // Mindest-Exportdauer einhalten (zentriert erweitern)
        val exportDuration = exportEnd - exportStart
        if (exportDuration < minExportSec) {
            val expand = (minExportSec - exportDuration) / 2f
            exportStart = (exportStart - expand).coerceAtLeast(0f)
            exportEnd = (exportStart + minExportSec).coerceAtMost(state.totalDurationSec)
            if (exportEnd - exportStart < minExportSec) {
                exportStart = (exportEnd - minExportSec).coerceAtLeast(0f)
            }
        }

        scope.launch {
            onLocalStateUpdate(true, null, "Exportiere Region...")

            try {
                // Samples laden: aus audioSegment (kurze Dateien) oder PCM-Cache (grosse Dateien)
                var subSamples: FloatArray
                var sampleRate: Int
                if (state.audioSegment != null) {
                    val segment = state.audioSegment
                    sampleRate = segment.sampleRate
                    val startIdx = (exportStart * sampleRate).toInt().coerceIn(0, segment.samples.size)
                    val endIdx = (exportEnd * sampleRate).toInt().coerceIn(startIdx, segment.samples.size)
                    subSamples = segment.samples.copyOfRange(startIdx, endIdx)
                } else if (state.audioFile != null) {
                    // Grosse Datei: on-demand dekodieren
                    sampleRate = if (state.audioSampleRate > 0) state.audioSampleRate else 48000
                    logger.debug("[EXPORT] Dekodiere on-demand: {}s - {}s, SR={}", exportStart, exportEnd, sampleRate)
                    try {
                        val decoded = AudioDecoder.decodeRange(state.audioFile, exportStart, exportEnd)
                        subSamples = decoded.samples
                        sampleRate = decoded.sampleRate
                        logger.debug("[EXPORT] Dekodiert: {} samples", subSamples.size)
                    } catch (e: Exception) {
                        logger.debug("[EXPORT] decodeRange FEHLER", e)
                        val full = AudioDecoder.decode(state.audioFile)
                        sampleRate = full.sampleRate
                        val startIdx = (exportStart * sampleRate).toInt().coerceIn(0, full.samples.size)
                        val endIdx = (exportEnd * sampleRate).toInt().coerceIn(startIdx, full.samples.size)
                        subSamples = full.samples.copyOfRange(startIdx, endIdx)
                        logger.debug("[EXPORT] Fallback: {} samples aus vollem Decode", subSamples.size)
                    }
                } else {
                    onLocalStateUpdate(false, null, "Export: Kein Audio verfuegbar")
                    return@launch
                }

                // Volume Envelope auf Export-Samples anwenden
                if (state.volumeEnvelopeActive && state.volumeEnvelope.isNotEmpty()) {
                    for (i in subSamples.indices) {
                        val timeSec = exportStart + i.toFloat() / sampleRate
                        val gainDb = state.volumeEnvelope.gainAtTime(timeSec)
                        subSamples[i] *= gainDbToLinear(gainDb)
                    }
                }

                System.err.println("[EXPORT] Starte Export: ${subSamples.size} Samples, SR=$sampleRate")

                val exportFMax = maxFreqHz().coerceAtMost(sampleRate / 2f)
                val currentFilter = state.filterConfig
                val filterDesc = describeFilter(currentFilter)

                // Hi-Res MelSpectrogram Export (2048 Mels, FFT 8192, Hop 64)
                val hiResSpec = MelSpectrogram(
                    fftSize = 8192,
                    hopSize = 64,
                    nMels = 2048,
                    fMin = 0f,
                    fMax = exportFMax.coerceAtMost(sampleRate / 2f),
                    sampleRate = sampleRate
                )
                val hiResData = hiResSpec.compute(subSamples)
                val hiResFiltered = FilterPipeline.apply(hiResData, currentFilter)

                val exportSettings = SettingsStore.load()

                ImageExporter.export(
                    spectrogramData = hiResFiltered,
                    linearFreqData = false,
                    annotation = annotation,
                    outputFile = outputFile,
                    viewStartSec = exportStart,
                    viewEndSec = exportEnd,
                    label = annotation?.label ?: "",
                    speciesName = annotation?.matchResults?.firstOrNull()?.scientificName ?: "",
                    filterDescription = filterDesc,
                    blackAndWhite = state.exportBlackAndWhite,
                    exportFreqMinHz = exportSettings.exportFreqMinHz.toFloat(),
                    exportFreqMaxHz = exportSettings.exportFreqMaxHz.toFloat(),
                    exportFreqStepHz = exportSettings.exportFreqStepHz.toFloat(),
                    secPerCm = exportSettings.exportSecPerCm,
                    rowLengthCm = exportSettings.exportRowLengthCm
                )

                // Audit-Eintrag
                val imgW = hiResData.nFrames
                val imgH = hiResData.nMels
                onAuditEntry(
                    "Export",
                    "Export: ${outputFile.name} (${imgW}x${imgH}, ${exportSettings.exportSecPerCm}s/cm, " +
                        "${if (state.exportBlackAndWhite) "S/W" else "Farbe"})" +
                        if (filterDesc.isNotEmpty()) " Filter: $filterDesc" else ""
                )
                onExportFileChanged(outputFile)

                onLocalStateUpdate(false,
                    "Exportiert: ${outputFile.name} (${hiResData.nMels}bins x ${hiResData.nFrames}frames)" +
                        if (filterDesc.isNotEmpty()) " (Filter: $filterDesc)" else "",
                    null)
            } catch (e: Exception) {
                logger.debug("[EXPORT] EXCEPTION", e)
                onLocalStateUpdate(false, null, "Export-Fehler: ${e.message}")
            }
        }
    }

    /**
     * Exportiert den aktuellen Bereich als WAV- oder MP3-Datei.
     * Wendet Filter + Volume Envelope an.
     *
     * @param format "wav", "flac", "m4a" oder "mp3" (FLAC/M4A/MP3 via ffmpeg)
     * @param timeStretch Zeitdehnung (1 = original, 10 = 10x langsamer fuer Fledermaus)
     */
    fun exportAudio(outputFile: File, state: CompareUiState, format: String = "wav", timeStretch: Int = 1) {
        val annotation = state.activeAnnotation
        val settings = SettingsStore.load()
        val minExportSec = settings.minExportDurationSec
        var exportStart: Float
        var exportEnd: Float
        if (annotation != null) {
            exportStart = annotation.startTimeSec
            exportEnd = annotation.endTimeSec
        } else {
            exportStart = state.viewStartSec
            exportEnd = state.viewEndSec
        }
        val exportDuration = exportEnd - exportStart
        if (exportDuration < minExportSec) {
            val expand = (minExportSec - exportDuration) / 2f
            exportStart = (exportStart - expand).coerceAtLeast(0f)
            exportEnd = (exportStart + minExportSec).coerceAtMost(state.totalDurationSec)
            if (exportEnd - exportStart < minExportSec) {
                exportStart = (exportEnd - minExportSec).coerceAtLeast(0f)
            }
        }

        scope.launch {
            onLocalStateUpdate(true, null, "Exportiere Audio...")
            try {
                var subSamples: FloatArray
                var sampleRate: Int
                if (state.audioSegment != null) {
                    sampleRate = state.audioSegment.sampleRate
                    val startIdx = (exportStart * sampleRate).toInt().coerceIn(0, state.audioSegment.samples.size)
                    val endIdx = (exportEnd * sampleRate).toInt().coerceIn(startIdx, state.audioSegment.samples.size)
                    subSamples = state.audioSegment.samples.copyOfRange(startIdx, endIdx)
                } else if (state.audioFile != null) {
                    val decoded = AudioDecoder.decodeRange(state.audioFile, exportStart, exportEnd)
                    sampleRate = decoded.sampleRate
                    subSamples = decoded.samples
                } else {
                    onLocalStateUpdate(false, null, "Export: Kein Audio")
                    return@launch
                }

                // Volume Envelope anwenden
                if (state.volumeEnvelopeActive && state.volumeEnvelope.isNotEmpty()) {
                    for (i in subSamples.indices) {
                        val timeSec = exportStart + i.toFloat() / sampleRate
                        subSamples[i] *= gainDbToLinear(state.volumeEnvelope.gainAtTime(timeSec))
                    }
                }

                // Filter anwenden (STFT-basiert)
                if (state.filterConfig.isActive) {
                    val segment = AudioSegment(subSamples, sampleRate)
                    subSamples = FilteredAudio.apply(segment, state.filterConfig, maxFreqHz())
                }

                // Zeitdehnung (Fledermaus: 10x langsamer → Ultraschall wird hoerbar)
                val exportSampleRate: Int
                val exportSamples: FloatArray
                if (timeStretch > 1) {
                    exportSampleRate = sampleRate / timeStretch
                    exportSamples = subSamples
                } else {
                    exportSampleRate = sampleRate
                    exportSamples = subSamples
                }

                val audioFormat = when (format) {
                    "mp3"  -> AudioExportFormat.MP3
                    "flac" -> AudioExportFormat.FLAC
                    "m4a"  -> AudioExportFormat.M4A
                    else   -> AudioExportFormat.WAV
                }
                AudioExporter.export(outputFile, exportSamples, exportSampleRate, audioFormat)

                val durationSec = exportSamples.size.toFloat() / exportSampleRate
                val stretchInfo = if (timeStretch > 1) " (${timeStretch}x Zeitdehnung)" else ""
                onLocalStateUpdate(false,
                    "Audio exportiert: ${outputFile.name} (${"%.1f".format(durationSec)}s, ${exportSampleRate} Hz)$stretchInfo",
                    "")
            } catch (e: Exception) {
                onLocalStateUpdate(false, null, "Audio-Export-Fehler: ${e.message}")
            }
        }
    }

}
