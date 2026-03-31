package ch.etasystems.amsel.ui.reference

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.core.audio.AudioDecoder
import ch.etasystems.amsel.core.audio.AudioSegment
import ch.etasystems.amsel.core.filter.FilterConfig
import ch.etasystems.amsel.core.filter.FilterPipeline
import ch.etasystems.amsel.core.reference.ReferenceGenerator
import ch.etasystems.amsel.core.spectrogram.ChunkedSpectrogram
import ch.etasystems.amsel.core.spectrogram.MelSpectrogram
import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import ch.etasystems.amsel.data.CollectionInfo
import ch.etasystems.amsel.data.ReferenceEntry
import ch.etasystems.amsel.data.ReferenceStore
import ch.etasystems.amsel.ui.sonogram.FilterPanel
import ch.etasystems.amsel.ui.sonogram.OverviewStrip
import ch.etasystems.amsel.ui.sonogram.ZoomedCanvas
import ch.etasystems.amsel.ui.sonogram.createSpectrogramBitmap
import ch.etasystems.amsel.core.audio.AudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Referenz-Editor-Modus.
 *
 * Layout:
 *   Links: Batch-Queue (Dateiliste mit Status-Icons)
 *   Mitte: Sonogramm-Ansicht (Overview + Zoom + Filter)
 *   Rechts: Metadaten-Panel (Art, Typ, Sammlung, Notizen, Verify/Delete)
 *
 * Workflow:
 * 1. Dateien importieren (Batch) oder aus XC-Download laden
 * 2. Pro Datei: Ausschnitt waehlen, Filter anwenden
 * 3. Metadaten eingeben: Art, Ruftyp, Qualitaet
 * 4. "Referenz speichern" → generiert Feldfuehrer-Sonogramm + speichert in Sammlung
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceEditorScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val refStore = remember { ReferenceStore() }
    val scope = rememberCoroutineScope()

    // Audio-Player fuer Wiedergabe in der Batch-Queue und Ausschnitt
    val audioPlayer = remember { AudioPlayer() }
    val playbackState by audioPlayer.state.collectAsState()
    // Welche Datei wird gerade abgespielt? null = nichts, SEGMENT_MARKER = Ausschnitt
    var playingFile by remember { mutableStateOf<File?>(null) }
    val segmentMarker = remember { File("__segment_playback__") }

    // Wiedergabe-Status zuruecksetzen wenn Audio zu Ende
    LaunchedEffect(playbackState) {
        if (playbackState == AudioPlayer.PlaybackState.STOPPED) {
            playingFile = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { audioPlayer.dispose() }
    }

    // Batch-Queue
    var batchFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(-1) }
    var processedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Aktuell geladenes Audio
    var audioSegment by remember { mutableStateOf<AudioSegment?>(null) }
    var overviewData by remember { mutableStateOf<SpectrogramData?>(null) }
    var zoomedData by remember { mutableStateOf<SpectrogramData?>(null) }
    var viewStartSec by remember { mutableStateOf(0f) }
    var viewEndSec by remember { mutableStateOf(5f) }
    var totalDurationSec by remember { mutableStateOf(0f) }
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Dateien importieren oder XC-Sammlung laden") }

    // Metadaten
    var scientificName by remember { mutableStateOf("") }
    var commonName by remember { mutableStateOf("") }
    var callType by remember { mutableStateOf("Gesang") }
    var quality by remember { mutableStateOf("B") }
    var notes by remember { mutableStateOf("") }
    var selectedCollection by remember { mutableStateOf("xeno") }

    // Filter
    var filterConfig by remember { mutableStateOf(FilterConfig()) }
    var showFilter by remember { mutableStateOf(false) }

    // Sammlungen
    val collections = remember { refStore.getCollections() }

    // Unverified XC-Eintraege laden
    var unverifiedEntries by remember {
        mutableStateOf(refStore.getEntries("xeno").filter { !it.verified })
    }

    // Datei laden
    fun loadFile(file: File) {
        scope.launch {
            isLoading = true
            statusText = "Lade ${file.name}..."
            try {
                val segment = withContext(Dispatchers.IO) { AudioDecoder.decode(file) }
                audioSegment = segment
                totalDurationSec = segment.durationSec

                val overview = withContext(Dispatchers.Default) {
                    val spec = MelSpectrogram(
                        fftSize = 2048, hopSize = 512, nMels = 80,
                        fMin = 500f, fMax = 10000f, sampleRate = segment.sampleRate
                    )
                    spec.compute(segment.samples)
                }
                overviewData = overview

                // Initialer Zoom: erste 5 Sekunden
                val end = minOf(5f, segment.durationSec)
                viewStartSec = 0f
                viewEndSec = end

                val zoomed = withContext(Dispatchers.Default) {
                    ChunkedSpectrogram.computeRegion(segment, 0f, end)
                }
                zoomedData = zoomed

                statusText = "${file.name} — ${segment.durationSec.toInt()}s — Ausschnitt waehlen"
                isLoading = false

                // Versuche Art aus Dateiname zu extrahieren (xc_12345 → XC-Metadaten)
                val xcMatch = Regex("xc_(\\d+)").find(file.nameWithoutExtension)
                if (xcMatch != null) {
                    val xcId = xcMatch.groupValues[1]
                    val entry = refStore.getEntries("xeno").find { it.xcId == xcId }
                    if (entry != null) {
                        scientificName = entry.scientificName
                        commonName = entry.commonName
                        callType = entry.callType
                        quality = entry.quality
                    }
                }
            } catch (e: Exception) {
                statusText = "Fehler: ${e.message}"
                isLoading = false
            }
        }
    }

    // Zoom aktualisieren
    fun recomputeZoom() {
        val seg = audioSegment ?: return
        scope.launch {
            val zoomed = withContext(Dispatchers.Default) {
                var data = ChunkedSpectrogram.computeRegion(seg, viewStartSec, viewEndSec)
                if (filterConfig.isActive) {
                    data = FilterPipeline.apply(data, filterConfig)
                }
                data
            }
            zoomedData = zoomed
        }
    }

    // Referenz speichern
    fun saveReference() {
        val seg = audioSegment ?: return
        if (scientificName.isBlank()) {
            statusText = "Artname eingeben!"
            return
        }

        scope.launch {
            isLoading = true
            statusText = "Generiere Referenz-Sonogramm..."

            try {
                val collDir = refStore.getCollectionDir(selectedCollection)
                val sonoDir = File(collDir, "sono").also { it.mkdirs() }
                val id = "${selectedCollection}_${System.currentTimeMillis()}"
                val sonoPng = File(sonoDir, "$id.png")

                // Ausschnitt extrahieren und Referenz generieren
                val subSegment = AudioSegment(
                    samples = seg.samples.copyOfRange(
                        (viewStartSec * seg.sampleRate).toInt().coerceIn(0, seg.samples.size),
                        (viewEndSec * seg.sampleRate).toInt().coerceIn(0, seg.samples.size)
                    ),
                    sampleRate = seg.sampleRate
                )

                val success = withContext(Dispatchers.Default) {
                    ReferenceGenerator.generateFromSegment(
                        segment = subSegment,
                        outputFile = sonoPng,
                        config = ReferenceGenerator.RefConfig(
                            maxDurationSec = viewEndSec - viewStartSec,
                            minDurationSec = 0.5f,
                            paddingSec = 0.1f,
                            fMin = 1000f,
                            fMax = 11000f
                        )
                    )
                }

                if (success) {
                    refStore.addEntry(selectedCollection, ReferenceEntry(
                        id = id,
                        scientificName = scientificName.trim(),
                        commonName = commonName.trim(),
                        callType = callType.trim(),
                        quality = quality.trim(),
                        source = if (selectedCollection == "xeno") "Xeno-Canto"
                                else if (selectedCollection == "glutz") "Literatur"
                                else "Eigenaufnahme",
                        notes = notes.trim(),
                        verified = true,
                        clipStartSec = viewStartSec,
                        clipEndSec = viewEndSec,
                        durationSec = totalDurationSec,
                        sonogramFile = "sono/$id.png"
                    ))

                    processedIds = processedIds + (batchFiles.getOrNull(currentIndex)?.name ?: "")
                    statusText = "Referenz gespeichert: $scientificName ($callType)"

                    // Naechste Datei
                    if (currentIndex < batchFiles.size - 1) {
                        currentIndex++
                        loadFile(batchFiles[currentIndex])
                    }
                } else {
                    statusText = "Fehler bei Sonogramm-Generierung"
                }
            } catch (e: Exception) {
                statusText = "Fehler: ${e.message}"
            }
            isLoading = false
        }
    }

    // ════════════════════ Layout ════════════════════

    Column(modifier = modifier.fillMaxSize()) {
        // Top-Bar
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // ZURUECK-BUTTON (prominent, links)
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurueck zum Hauptmodus",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp))
                    }
                    Icon(Icons.Default.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Referenz-Editor", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "— $statusText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Batch-Import
                    Button(
                        onClick = {
                            val chooser = JFileChooser().apply {
                                isMultiSelectionEnabled = true
                                fileFilter = FileNameExtensionFilter("Audio (WAV, MP3, FLAC)", "wav", "mp3", "flac", "ogg")
                            }
                            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                batchFiles = chooser.selectedFiles.toList()
                                currentIndex = 0
                                processedIds = emptySet()
                                if (batchFiles.isNotEmpty()) loadFile(batchFiles[0])
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Dateien importieren")
                    }

                    // Unverified XC laden
                    if (unverifiedEntries.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                val xcFiles = unverifiedEntries.mapNotNull { entry ->
                                    refStore.getAudioFile("xeno", entry)
                                }
                                if (xcFiles.isNotEmpty()) {
                                    batchFiles = xcFiles
                                    currentIndex = 0
                                    processedIds = emptySet()
                                    loadFile(xcFiles[0])
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${unverifiedEntries.size} XC pruefen")
                        }
                    }

                    // Filter Toggle
                    IconButton(onClick = { showFilter = !showFilter }) {
                        Icon(Icons.Default.Tune, "Filter",
                            tint = if (showFilter) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // Schliessen
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, "Schliessen")
                    }
                }
            }
        }

        // Filter-Panel
        if (showFilter) {
            FilterPanel(
                config = filterConfig,
                isBatMode = false,
                onConfigChanged = {
                    filterConfig = it
                    recomputeZoom()
                },
                onClose = { showFilter = false }
            )
        }

        // Hauptbereich
        Row(modifier = Modifier.fillMaxSize()) {
            // ═══ Links: Batch-Queue ═══
            if (batchFiles.isNotEmpty()) {
                Surface(
                    modifier = Modifier.width(220.dp).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp
                ) {
                    Column {
                        Text(
                            "Dateien (${currentIndex + 1}/${batchFiles.size})",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(8.dp)
                        )
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(batchFiles.withIndex().toList()) { (idx, file) ->
                                val isActive = idx == currentIndex
                                val isDone = processedIds.contains(file.name)
                                val isPlayingThis = playingFile == file &&
                                    playbackState == AudioPlayer.PlaybackState.PLAYING
                                Surface(
                                    onClick = { currentIndex = idx; loadFile(file) },
                                    color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            when {
                                                isDone -> Icons.Default.CheckCircle
                                                isActive -> Icons.Default.PlayCircle
                                                else -> Icons.Default.AudioFile
                                            },
                                            null,
                                            modifier = Modifier.size(16.dp),
                                            tint = when {
                                                isDone -> Color(0xFF4CAF50)
                                                isActive -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            }
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            file.nameWithoutExtension,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                        // Wiedergabe-Button pro Datei
                                        IconButton(
                                            onClick = {
                                                if (isPlayingThis) {
                                                    // Wiedergabe stoppen
                                                    audioPlayer.stop()
                                                    playingFile = null
                                                } else {
                                                    // Audio dekodieren und abspielen
                                                    scope.launch {
                                                        try {
                                                            val seg = withContext(Dispatchers.IO) {
                                                                AudioDecoder.decode(file)
                                                            }
                                                            audioPlayer.stop()
                                                            playingFile = file
                                                            audioPlayer.play(seg)
                                                        } catch (_: Exception) {
                                                            // Audio-Fehler ignorieren
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(
                                                if (isPlayingThis) Icons.Default.Stop
                                                else Icons.Default.PlayArrow,
                                                contentDescription = if (isPlayingThis) "Stoppen" else "Abspielen",
                                                modifier = Modifier.size(14.dp),
                                                tint = if (isPlayingThis) Color(0xFF4CAF50)
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Statistik
                        Text(
                            "${processedIds.size} verarbeitet",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            // ═══ Mitte: Sonogramm ═══
            Column(modifier = Modifier.weight(1f)) {
                if (overviewData != null) {
                    // Overview
                    OverviewStrip(
                        spectrogramData = overviewData,
                        viewStartSec = viewStartSec,
                        viewEndSec = viewEndSec,
                        totalDurationSec = totalDurationSec,
                        onViewRangeChanged = { s, e ->
                            viewStartSec = s; viewEndSec = e; recomputeZoom()
                        },
                        onViewRangeDrag = { s, e -> viewStartSec = s; viewEndSec = e },
                        onViewRangeDragEnd = { recomputeZoom() }
                    )

                    // Zeit-Info + Wiedergabe des aktuellen Ausschnitts
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${"%.2f".format(viewStartSec)}s", style = MaterialTheme.typography.labelSmall, color = Color(0xFF90CAF9))

                        // Ausschnitt abspielen
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val isPlayingSegment = playbackState == AudioPlayer.PlaybackState.PLAYING &&
                                playingFile == segmentMarker
                            IconButton(
                                onClick = {
                                    val seg = audioSegment ?: return@IconButton
                                    if (isPlayingSegment) {
                                        audioPlayer.stop()
                                    } else {
                                        audioPlayer.stop()
                                        playingFile = segmentMarker
                                        audioPlayer.play(seg, viewStartSec, viewEndSec)
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    if (isPlayingSegment) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlayingSegment) "Stoppen" else "Ausschnitt abspielen",
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isPlayingSegment) Color(0xFF4CAF50)
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                            Text("Ausschnitt: ${"%.2f".format(viewEndSec - viewStartSec)}s", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }

                        Text("${"%.2f".format(viewEndSec)}s", style = MaterialTheme.typography.labelSmall, color = Color(0xFF90CAF9))
                    }

                    // Zoom-Ansicht
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        zoomedData?.let { data ->
                            val bitmap = remember(data) { createSpectrogramBitmap(data) }
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Sonogramm",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center).size(32.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    // Leer-Zustand
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.LibraryMusic, null, modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                            Spacer(Modifier.height(12.dp))
                            Text("Dateien importieren oder XC-Downloads pruefen",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }

            // ═══ Rechts: Metadaten-Panel ═══
            if (audioSegment != null) {
                Surface(
                    modifier = Modifier.width(280.dp).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    tonalElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Metadaten", style = MaterialTheme.typography.titleSmall)

                        // Sammlung
                        Text("Sammlung:", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (coll in collections) {
                                FilterChip(
                                    selected = selectedCollection == coll.id,
                                    onClick = { selectedCollection = coll.id },
                                    label = { Text(coll.name, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(28.dp)
                                )
                            }
                        }

                        HorizontalDivider()

                        // Wissenschaftlicher Name
                        OutlinedTextField(
                            value = scientificName,
                            onValueChange = { scientificName = it },
                            label = { Text("Wiss. Name *") },
                            placeholder = { Text("Turdus merula") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Deutscher Name
                        OutlinedTextField(
                            value = commonName,
                            onValueChange = { commonName = it },
                            label = { Text("Dt. Name") },
                            placeholder = { Text("Amsel") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Ruftyp
                        Text("Ruftyp:", style = MaterialTheme.typography.labelSmall)
                        val callTypes = listOf("Gesang", "Ruf", "Alarm", "Flug", "Bettelruf", "Andere")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            // Erste Zeile
                            for (ct in callTypes.take(3)) {
                                FilterChip(
                                    selected = callType == ct,
                                    onClick = { callType = ct },
                                    label = { Text(ct, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(26.dp)
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (ct in callTypes.drop(3)) {
                                FilterChip(
                                    selected = callType == ct,
                                    onClick = { callType = ct },
                                    label = { Text(ct, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(26.dp)
                                )
                            }
                        }

                        // Qualitaet
                        Text("Qualitaet:", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (q in listOf("A", "B", "C", "D")) {
                                FilterChip(
                                    selected = quality == q,
                                    onClick = { quality = q },
                                    label = { Text(q, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(26.dp)
                                )
                            }
                        }

                        // Notizen
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("Notizen") },
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            maxLines = 3
                        )

                        HorizontalDivider()

                        // Aktions-Buttons
                        Button(
                            onClick = { saveReference() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            enabled = scientificName.isNotBlank() && !isLoading
                        ) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Referenz speichern")
                        }

                        // Skip
                        OutlinedButton(
                            onClick = {
                                if (currentIndex < batchFiles.size - 1) {
                                    currentIndex++
                                    loadFile(batchFiles[currentIndex])
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = currentIndex < batchFiles.size - 1
                        ) {
                            Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Ueberspringen")
                        }

                        // Statistik
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("Referenz-Statistik", style = MaterialTheme.typography.labelMedium)
                                val totalRefs = refStore.getTotalEntryCount()
                                val verifiedRefs = refStore.getVerifiedCount()
                                val speciesCount = refStore.getSpeciesCount()
                                Text("$totalRefs Eintraege ($verifiedRefs verifiziert)", style = MaterialTheme.typography.labelSmall)
                                Text("$speciesCount Arten", style = MaterialTheme.typography.labelSmall)
                                for (coll in collections) {
                                    val count = refStore.getEntries(coll.id).size
                                    if (count > 0) {
                                        Text("  ${coll.name}: $count", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
