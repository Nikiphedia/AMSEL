package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.etasystems.amsel.core.classifier.BirdNetBridge
import ch.etasystems.amsel.core.classifier.EmbeddingExtractor
import ch.etasystems.amsel.core.classifier.OnnxBirdNetV3
import ch.etasystems.amsel.core.similarity.OnnxSimilarityMetric
import ch.etasystems.amsel.core.spectrogram.FftSize
import ch.etasystems.amsel.core.spectrogram.HopFraction
import ch.etasystems.amsel.core.spectrogram.WindowFunction
import ch.etasystems.amsel.data.*
import ch.etasystems.amsel.data.ModelRegistry
import ch.etasystems.amsel.ui.util.formatKHz
import ch.etasystems.amsel.ui.util.parseKHzToHz

/**
 * Einheitlicher Einstellungen-Dialog mit 4 Tabs:
 * 1. Allgemein — Standort, Sprache, API-Key
 * 2. Analyse — Vergleichs-Algorithmus, BirdNET, Event Vor-/Nachlauf
 * 3. Export — Frequenzbereich, Zeitachse, Presets
 * 4. Datenbank — Cache-Status, Download, Artenliste
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedSettingsDialog(
    // Aktuelle Werte fuer Datenbank-Tab
    referenceSpeciesCount: Int = 0,
    referenceRecordingCount: Int = 0,
    downloadProgress: ch.etasystems.amsel.data.reference.ReferenceDownloader.DownloadProgress = ch.etasystems.amsel.data.reference.ReferenceDownloader.DownloadProgress(),
    // Callbacks
    onStartDownload: (List<String>, Int) -> Unit = { _, _ -> },
    onCancelDownload: () -> Unit = {},
    onApiKeySaved: (String) -> Unit = {},
    onSettingsChanged: () -> Unit = {},
    onRescanReferences: suspend () -> Unit = {},
    onStartAudioBatchDownload: (
        onProgress: (current: Int, total: Int, species: String) -> Unit,
        onComplete: (String) -> Unit,
        onCancel: () -> Unit
    ) -> Unit = { _, _, _ -> },
    onCancelAudioBatchDownload: () -> Unit = {},
    onGetAudioStats: (regionSetId: String) -> Pair<Int, Int> = { Pair(0, 0) },
    onDismiss: () -> Unit
) {
    val settings = remember { SettingsStore.load() }
    var selectedTab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // ── Rescan-State ──
    var isRescanning by remember { mutableStateOf(false) }
    var rescanResult by remember { mutableStateOf("") }

    // ── Qualitaetsfilter-State ──
    var refMinQualityDownload by remember { mutableStateOf(settings.referenceMinQualityDownload) }
    var refMinQualityDisplay by remember { mutableStateOf(settings.referenceMinQualityDisplay) }

    // ── Artenset-State ──
    var activeRegionSet by remember { mutableStateOf(settings.activeRegionSet) }

    // ── Audio Batch-Download State ──
    var isDownloadingAudio by remember { mutableStateOf(false) }
    var audioDownloadCurrent by remember { mutableIntStateOf(0) }
    var audioDownloadTotal by remember { mutableIntStateOf(0) }
    var audioDownloadSpecies by remember { mutableStateOf("") }
    var audioDownloadResult by remember { mutableStateOf("") }
    var audioExistingCount by remember { mutableIntStateOf(0) }
    var audioTotalCount by remember { mutableIntStateOf(0) }

    // ── Modell-Dialog-State ──
    var showModelDialog by remember { mutableStateOf(false) }
    val modelRegistry = remember { ModelRegistry() }
    var modelsConfig by remember { mutableStateOf(modelRegistry.load()) }

    // ── Tab 1: Allgemein ──
    var locationName by remember { mutableStateOf(settings.locationName) }
    var latStr by remember { mutableStateOf("%.4f".format(settings.locationLat)) }
    var lonStr by remember { mutableStateOf("%.4f".format(settings.locationLon)) }
    var speciesLanguage by remember { mutableStateOf(settings.speciesLanguage) }
    var showScientificNames by remember { mutableStateOf(settings.showScientificNames) }
    var apiKey by remember { mutableStateOf(settings.xenoCantoApiKey) }
    var operatorName by remember { mutableStateOf(settings.operatorName) }
    var deviceName by remember { mutableStateOf(settings.deviceName) }

    // ── Tab 2: Analyse ──
    var specWindowType by remember { mutableStateOf(settings.specWindowType) }
    var specFftSize by remember { mutableStateOf(settings.specFftSize) }
    var specHopFraction by remember { mutableStateOf(settings.specHopFraction) }
    var selectedAlgorithm by remember { mutableStateOf(settings.comparisonAlgorithm) }
    var confText by remember { mutableStateOf("%.2f".format(settings.birdnetMinConf)) }
    var birdnetUseFiltered by remember { mutableStateOf(settings.birdnetUseFiltered) }
    var prerollStr by remember { mutableStateOf("%.0f".format(settings.eventPrerollSec)) }
    var postrollStr by remember { mutableStateOf("%.0f".format(settings.eventPostrollSec)) }
    var soloPrerollStr by remember { mutableStateOf("%.0f".format(settings.soloPrerollSec)) }
    var soloPostrollStr by remember { mutableStateOf("%.0f".format(settings.soloPostrollSec)) }
    var minDisplayStr by remember { mutableStateOf("%.0f".format(settings.minDisplayDurationSec)) }
    var minExportStr by remember { mutableStateOf("%.0f".format(settings.minExportDurationSec)) }
    var shortFileStartPct by remember { mutableFloatStateOf(settings.shortFileStartPct) }
    var sliceLengthMin by remember { mutableFloatStateOf(settings.sliceLengthMin) }
    var sliceOverlapSec by remember { mutableFloatStateOf(settings.sliceOverlapSec) }
    var onnxAvailable by remember { mutableStateOf(false) }
    var embeddingModelAvailable by remember { mutableStateOf(false) }
    var birdnetAvailable by remember { mutableStateOf(false) }
    var birdnetV3Available by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            onnxAvailable = OnnxSimilarityMetric.isModelAvailable()
            embeddingModelAvailable = EmbeddingExtractor().isModelAvailable()
            birdnetAvailable = BirdNetBridge.isAvailable()
            birdnetV3Available = OnnxBirdNetV3().isAvailable()
        }
    }

    // Audio-Stats laden (initial + bei Artenset-Wechsel)
    LaunchedEffect(activeRegionSet) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val stats = onGetAudioStats(activeRegionSet)
            audioExistingCount = stats.first
            audioTotalCount = stats.second
        }
    }

    // ── Tab 3: Export ──
    var reportSortOrder by remember { mutableStateOf(settings.reportSortOrder) }
    var freqMinKHz by remember { mutableStateOf(formatKHz(settings.exportFreqMinHz)) }
    var freqMaxKHz by remember { mutableStateOf(formatKHz(settings.exportFreqMaxHz)) }
    var freqStepKHz by remember { mutableStateOf(formatKHz(settings.exportFreqStepHz)) }
    var maxFreqKHz by remember { mutableStateOf(formatKHz(settings.maxFrequencyHz)) }
    var secPerCm by remember { mutableStateOf("%.2f".format(settings.exportSecPerCm)) }
    var cmPerHalfSec by remember { mutableStateOf("%.2f".format(0.5f / settings.exportSecPerCm)) }
    var rowLengthCm by remember { mutableStateOf("%.1f".format(settings.exportRowLengthCm)) }

    if (showModelDialog) {
        ModelManagerDialog(
            models = modelsConfig.models,
            activeModel = modelsConfig.activeModel,
            onSelectModel = { filename ->
                modelsConfig = modelsConfig.copy(activeModel = filename)
                modelRegistry.save(modelsConfig)
            },
            onAddModel = { onnxFile, labelsFile ->
                modelRegistry.addModel(onnxFile, labelsFile)
                modelsConfig = modelRegistry.load()
            },
            onRemoveModel = { filename ->
                modelRegistry.removeModel(filename)
                modelsConfig = modelRegistry.load()
            },
            onDismiss = { showModelDialog = false }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.width(700.dp).heightIn(min = 400.dp, max = 750.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Titel
                Text(
                    "Einstellungen",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp)
                )

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                        text = { Text("Allgemein") },
                        icon = { Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                        text = { Text("Analyse") },
                        icon = { Icon(Icons.Default.Tune, null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                        text = { Text("Export") },
                        icon = { Icon(Icons.Default.SaveAlt, null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 },
                        text = { Text("Datenbank") },
                        icon = { Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp)) }
                    )
                }

                // Tab-Inhalt (scrollbar)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (selectedTab) {
                        0 -> TabAllgemein(
                            locationName = locationName,
                            onLocationNameChanged = { locationName = it },
                            latStr = latStr,
                            onLatChanged = { latStr = it },
                            lonStr = lonStr,
                            onLonChanged = { lonStr = it },
                            speciesLanguage = speciesLanguage,
                            onLanguageChanged = { speciesLanguage = it },
                            showScientificNames = showScientificNames,
                            onShowScientificChanged = { showScientificNames = it },
                            apiKey = apiKey,
                            onApiKeyChanged = { apiKey = it },
                            operatorName = operatorName,
                            onOperatorNameChanged = { operatorName = it },
                            deviceName = deviceName,
                            onDeviceNameChanged = { deviceName = it }
                        )
                        1 -> TabAnalyse(
                            selectedAlgorithm = selectedAlgorithm,
                            onAlgorithmChanged = { selectedAlgorithm = it },
                            confText = confText,
                            onConfChanged = { confText = it },
                            prerollStr = prerollStr,
                            onPrerollChanged = { prerollStr = it },
                            postrollStr = postrollStr,
                            onPostrollChanged = { postrollStr = it },
                            minDisplayStr = minDisplayStr,
                            onMinDisplayChanged = { minDisplayStr = it },
                            minExportStr = minExportStr,
                            onMinExportChanged = { minExportStr = it },
                            shortFileStartPct = shortFileStartPct,
                            onShortFileStartChanged = { shortFileStartPct = it },
                            birdnetUseFiltered = birdnetUseFiltered,
                            onBirdnetUseFilteredChanged = { birdnetUseFiltered = it },
                            sliceLengthMin = sliceLengthMin,
                            onSliceLengthChanged = { sliceLengthMin = it },
                            sliceOverlapSec = sliceOverlapSec,
                            onSliceOverlapChanged = { sliceOverlapSec = it },
                            soloPrerollStr = soloPrerollStr,
                            onSoloPrerollChanged = { soloPrerollStr = it },
                            soloPostrollStr = soloPostrollStr,
                            onSoloPostrollChanged = { soloPostrollStr = it },
                            onOpenModelManager = { showModelDialog = true },
                            specWindowType = specWindowType,
                            onWindowTypeChanged = { specWindowType = it },
                            specFftSize = specFftSize,
                            onFftSizeChanged = { specFftSize = it },
                            specHopFraction = specHopFraction,
                            onHopFractionChanged = { specHopFraction = it }
                        )
                        2 -> TabExport(
                            freqMinKHz = freqMinKHz,
                            onFreqMinChanged = { freqMinKHz = it },
                            freqMaxKHz = freqMaxKHz,
                            onFreqMaxChanged = { freqMaxKHz = it },
                            freqStepKHz = freqStepKHz,
                            onFreqStepChanged = { freqStepKHz = it },
                            maxFreqKHz = maxFreqKHz,
                            onMaxFreqChanged = { maxFreqKHz = it },
                            secPerCm = secPerCm,
                            onSecPerCmChanged = { secPerCm = it },
                            cmPerHalfSec = cmPerHalfSec,
                            onCmPerHalfSecChanged = { cmPerHalfSec = it },
                            rowLengthCm = rowLengthCm,
                            onRowLengthChanged = { rowLengthCm = it },
                            reportSortOrder = reportSortOrder,
                            onReportSortOrderChanged = { reportSortOrder = it }
                        )
                        3 -> TabDatenbank(
                            referenceSpeciesCount = referenceSpeciesCount,
                            referenceRecordingCount = referenceRecordingCount,
                            downloadProgress = downloadProgress,
                            onStartDownload = onStartDownload,
                            onCancelDownload = onCancelDownload,
                            isRescanning = isRescanning,
                            rescanResult = rescanResult,
                            onRescan = {
                                isRescanning = true
                                rescanResult = ""
                                scope.launch {
                                    onRescanReferences()
                                    isRescanning = false
                                    rescanResult = "$referenceSpeciesCount Arten, $referenceRecordingCount Referenzen"
                                }
                            },
                            referenceMinQualityDownload = refMinQualityDownload,
                            referenceMinQualityDisplay = refMinQualityDisplay,
                            onMinQualityDownloadChanged = { refMinQualityDownload = it },
                            onMinQualityDisplayChanged = { refMinQualityDisplay = it },
                            activeRegionSet = activeRegionSet,
                            onRegionSetChanged = { activeRegionSet = it },
                            isDownloadingAudio = isDownloadingAudio,
                            audioDownloadCurrent = audioDownloadCurrent,
                            audioDownloadTotal = audioDownloadTotal,
                            audioDownloadSpecies = audioDownloadSpecies,
                            audioDownloadResult = audioDownloadResult,
                            audioExistingCount = audioExistingCount,
                            audioTotalCount = audioTotalCount,
                            onStartAudioBatchDownload = {
                                isDownloadingAudio = true
                                audioDownloadResult = ""
                                onStartAudioBatchDownload(
                                    { current, total, species ->
                                        audioDownloadCurrent = current
                                        audioDownloadTotal = total
                                        audioDownloadSpecies = species
                                    },
                                    { result ->
                                        isDownloadingAudio = false
                                        audioDownloadResult = result
                                        // Audio-Stats aktualisieren
                                        val stats = onGetAudioStats(activeRegionSet)
                                        audioExistingCount = stats.first
                                        audioTotalCount = stats.second
                                    },
                                    {
                                        isDownloadingAudio = false
                                        audioDownloadResult = "Abgebrochen"
                                    }
                                )
                            },
                            onCancelAudioBatchDownload = onCancelAudioBatchDownload
                        )
                    }
                }

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Abbrechen")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(onClick = {
                        // Alle Tabs zusammen speichern
                        val lat = latStr.toFloatOrNull() ?: settings.locationLat
                        val lon = lonStr.toFloatOrNull() ?: settings.locationLon
                        val preroll = prerollStr.toFloatOrNull() ?: settings.eventPrerollSec
                        val postroll = postrollStr.toFloatOrNull() ?: settings.eventPostrollSec
                        val soloPreroll = soloPrerollStr.toFloatOrNull() ?: settings.soloPrerollSec
                        val soloPostroll = soloPostrollStr.toFloatOrNull() ?: settings.soloPostrollSec
                        val conf = confText.toFloatOrNull()?.coerceIn(0f, 1f) ?: settings.birdnetMinConf

                        val fMin = parseKHzToHz(freqMinKHz, settings.exportFreqMinHz)
                        val fMax = parseKHzToHz(freqMaxKHz, settings.exportFreqMaxHz)
                        val fStep = parseKHzToHz(freqStepKHz, settings.exportFreqStepHz)
                        val mfHz = parseKHzToHz(maxFreqKHz, settings.maxFrequencyHz)
                        val spc = secPerCm.toFloatOrNull() ?: settings.exportSecPerCm
                        val rl = rowLengthCm.toFloatOrNull() ?: settings.exportRowLengthCm

                        val minDisp = minDisplayStr.toFloatOrNull() ?: settings.minDisplayDurationSec
                        val minExp = minExportStr.toFloatOrNull() ?: settings.minExportDurationSec

                        val updated = settings.copy(
                            // Artenset
                            activeRegionSet = activeRegionSet,
                            // Allgemein
                            locationName = locationName.trim(),
                            locationLat = lat.coerceIn(-90f, 90f),
                            locationLon = lon.coerceIn(-180f, 180f),
                            speciesLanguage = speciesLanguage,
                            showScientificNames = showScientificNames,
                            xenoCantoApiKey = apiKey.trim(),
                            operatorName = operatorName.trim(),
                            deviceName = deviceName.trim(),
                            // Analyse
                            comparisonAlgorithm = selectedAlgorithm,
                            birdnetMinConf = conf,
                            birdnetUseFiltered = birdnetUseFiltered,
                            eventPrerollSec = preroll.coerceIn(0f, 60f),
                            eventPostrollSec = postroll.coerceIn(0f, 120f),
                            soloPrerollSec = soloPreroll.coerceIn(0f, 60f),
                            soloPostrollSec = soloPostroll.coerceIn(0f, 60f),
                            minDisplayDurationSec = minDisp.coerceIn(1f, 60f),
                            minExportDurationSec = minExp.coerceIn(1f, 60f),
                            shortFileStartPct = shortFileStartPct.coerceIn(0f, 1f),
                            // Slices
                            sliceLengthMin = sliceLengthMin.coerceIn(1f, 30f),
                            sliceOverlapSec = sliceOverlapSec.coerceIn(0f, 30f),
                            // Export
                            exportFreqMinHz = fMin.coerceIn(0, 200000),
                            exportFreqMaxHz = fMax.coerceIn(1000, 200000),
                            exportFreqStepHz = fStep.coerceIn(500, 50000),
                            maxFrequencyHz = mfHz.coerceIn(1000, 200000),
                            exportSecPerCm = spc.coerceIn(0.1f, 20f),
                            exportRowLengthCm = rl.coerceIn(1f, 50f),
                            // Modell
                            activeModelFilename = modelsConfig.activeModel,
                            // Qualitaetsfilter
                            referenceMinQualityDownload = refMinQualityDownload,
                            referenceMinQualityDisplay = refMinQualityDisplay,
                            // Report-Sortierung
                            reportSortOrder = reportSortOrder,
                            // Spektrogramm-Parameter
                            specWindowType = specWindowType,
                            specFftSize = specFftSize,
                            specHopFraction = specHopFraction
                        )
                        SettingsStore.save(updated)

                        // API-Key separat melden wenn geaendert
                        if (updated.xenoCantoApiKey != settings.xenoCantoApiKey) {
                            onApiKeySaved(updated.xenoCantoApiKey)
                        }
                        onSettingsChanged()
                        onDismiss()
                    }) {
                        Text("Speichern")
                    }
                }
            }
        }
    }
}
