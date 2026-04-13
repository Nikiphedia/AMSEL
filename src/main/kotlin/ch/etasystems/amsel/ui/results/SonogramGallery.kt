package ch.etasystems.amsel.ui.results

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.core.annotation.MatchResult
import ch.etasystems.amsel.data.SpeciesRegistry
import ch.etasystems.amsel.ui.layout.HorizontalSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adaptive Thumbnail-Galerie fuer Referenz-Sonogramme.
 * Zeigt matchResults als scrollbares Grid mit kleinen Vorschaubildern.
 * Eintraege mit Sonogramm werden vor n/a-Eintraegen sortiert.
 *
 * Layout (von oben nach unten):
 * 1. Grosses Referenz-Sonogramm (wenn selectedResult != null, feste Hoehe 150dp)
 * 2. LazyVerticalGrid mit adaptiven Spalten (min 200dp pro Karte)
 */
@Composable
fun SonogramGallery(
    matchResults: List<MatchResult>,
    selectedResult: MatchResult?,
    onSelectResult: (MatchResult) -> Unit,
    onPlayAudio: (MatchResult) -> Unit,
    onStopAudio: () -> Unit = {},
    onClose: () -> Unit,
    isLoading: Boolean = false,
    isPlayingAudio: Boolean = false,
    playingRecordingId: String = "",
    downloadingRecordingId: String = "",
    referencePlaybackPositionSec: Float = 0f,
    referenceAudioDurationSec: Float = 0f,
    speciesLocale: String = "de",
    modifier: Modifier = Modifier
) {
    // Ziehbare Hoehe fuer das grosse Referenzbild (80-500dp)
    var referenceHeight by remember { mutableStateOf(180f) }

    Column(modifier = modifier) {
        // Grosses Referenz-Sonogramm (wenn ausgewaehlt)
        if (selectedResult != null) {
            HorizontalDivider(thickness = 2.dp, color = Color(0xFFFFEB3B).copy(alpha = 0.7f))
            ReferenceImageLarge(
                result = selectedResult,
                onClose = onClose,
                speciesLocale = speciesLocale,
                modifier = Modifier.fillMaxWidth().height(referenceHeight.dp)
            )
            HorizontalSplitter(
                onDrag = { deltaDp ->
                    referenceHeight = (referenceHeight + deltaDp).coerceIn(80f, 500f)
                }
            )
        }

        // Thumbnail-Galerie
        when {
            isLoading -> {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                "Suche Referenz-Sonogramme...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            matchResults.isEmpty() -> {
                // Platzhalter: dezenter Hinweis
                Surface(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    color = Color.Transparent
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "Referenz-Sonogramme erscheinen hier nach Auswahl einer Markierung",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            else -> {
                // Header mit Anzahl
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Text(
                        "${matchResults.size} Referenz-Sonogramme",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Vertikales Grid — passt sich der Fensterbreite an
                val sortedResults = remember(matchResults) {
                    matchResults.sortedWith(compareBy(
                        { when {
                            it.sonogramUrl.isNotBlank() && it.audioUrl.isNotBlank() -> 0  // Audio + Bild
                            it.sonogramUrl.isNotBlank() -> 1                              // Nur Bild
                            else -> 2                                                     // Weder noch
                        }},
                        { -it.similarity }
                    ))
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val gridState = rememberLazyGridState()
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 200.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(sortedResults, key = { it.recordingId }) { result ->
                            val isThisPlaying = isPlayingAudio && playingRecordingId == result.recordingId
                            val isThisDownloading = downloadingRecordingId == result.recordingId
                            ThumbnailCard(
                                result = result,
                                isSelected = selectedResult?.recordingId == result.recordingId,
                                speciesLocale = speciesLocale,
                                onClick = { onSelectResult(result) },
                                onPlay = {
                                    if (isThisPlaying) onStopAudio()
                                    else onPlayAudio(result)
                                },
                                isPlaying = isThisPlaying,
                                isDownloading = isThisDownloading,
                                hasLocalAudio = result.audioUrl.isNotBlank(),
                                playbackProgress = if (playingRecordingId == result.recordingId && referenceAudioDurationSec > 0f)
                                    (referencePlaybackPositionSec / referenceAudioDurationSec).coerceIn(0f, 1f)
                                else 0f
                            )
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(gridState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
                }
            }
        }
    }
}

// ================================================================
// Grosses Referenz-Bild (wiederverwendet Lade-Logik aus ReferenceSonogramPanel)
// ================================================================

@Composable
private fun ReferenceImageLarge(
    result: MatchResult,
    onClose: () -> Unit,
    speciesLocale: String = "de",
    modifier: Modifier = Modifier
) {
    var bitmap by remember(result.sonogramUrl) { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember(result.sonogramUrl) { mutableStateOf(false) }

    LaunchedEffect(result.sonogramUrl) {
        if (result.sonogramUrl.isBlank()) {
            loadError = true
            return@LaunchedEffect
        }
        loadError = false
        bitmap = null

        try {
            bitmap = withContext(Dispatchers.IO) {
                val raw = ImageLoader.loadBufferedImage(result.sonogramUrl)
                if (raw == null) return@withContext null
                // Grosse Bilder runterskalieren
                val scaled = ImageLoader.scaleIfNeeded(raw, maxWidth = 2000)
                // Schwarze/korrupte Bilder erkennen
                if (ImageLoader.isImageBlack(scaled)) return@withContext null
                // Auf ARGB konvertieren (noetig fuer toComposeImageBitmap)
                ImageLoader.toArgb(scaled).toComposeImageBitmap()
            }
            if (bitmap == null) loadError = true
        } catch (_: Exception) {
            loadError = true
        }
    }

    Surface(
        modifier = modifier,
        color = Color(0xFF1A1A2E)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            // Header: Art-Info + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Artname in gewaehlter Sprache (Fallback-Kette in SpeciesRegistry)
                    val displayName = SpeciesRegistry.getDisplayName(result.scientificName, speciesLocale)
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1
                    )
                    if (result.species.isNotBlank() && result.scientificName.isNotBlank()) {
                        Text(
                            result.scientificName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1
                        )
                    }
                    if (result.type.isNotBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = Color.White.copy(alpha = 0.15f)
                        ) {
                            Text(
                                result.type,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    // ID anzeigen (XC-Prefix nur wenn es eine XC-Aufnahme ist)
                    val idLabel = if (result.recordingId.all { it.isDigit() }) "#${result.recordingId}" else "XC${result.recordingId}"
                    Text(
                        idLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.35f)
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Schliessen",
                        modifier = Modifier.size(16.dp),
                        tint = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            // Bild
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).background(Color.White)
            ) {
                if (bitmap != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawImage(
                            image = bitmap!!,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt())
                        )
                    }
                } else if (loadError) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Sonogramm nicht verfuegbar",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

// ================================================================
// Thumbnail-Karte (einzelnes Item in der Galerie)
// ================================================================

@Composable
private fun ThumbnailCard(
    result: MatchResult,
    isSelected: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    speciesLocale: String = "de",
    isPlaying: Boolean = false,
    isDownloading: Boolean = false,
    hasLocalAudio: Boolean = false,
    playbackProgress: Float = 0f
) {
    var bitmap by remember(result.sonogramUrl) { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember(result.sonogramUrl) { mutableStateOf(false) }
    var isCorrupt by remember(result.sonogramUrl) { mutableStateOf(false) }

    LaunchedEffect(result.sonogramUrl) {
        if (result.sonogramUrl.isBlank()) {
            loadError = true
            return@LaunchedEffect
        }
        loadError = false
        isCorrupt = false
        bitmap = null

        try {
            val (bmp, corrupt) = withContext(Dispatchers.IO) {
                val raw = ImageLoader.loadBufferedImage(result.sonogramUrl)
                if (raw == null) return@withContext Pair(null, false)
                // Thumbnail-Groesse: max 300px breit
                val scaled = ImageLoader.scaleIfNeeded(raw, maxWidth = 300)
                // Schwarze Bilder erkennen
                if (ImageLoader.isImageBlack(scaled)) return@withContext Pair(null, true)
                val composeBmp = ImageLoader.toArgb(scaled).toComposeImageBitmap()
                Pair(composeBmp, false)
            }
            bitmap = bmp
            isCorrupt = corrupt
            if (bmp == null && !corrupt) loadError = true
        } catch (_: Exception) {
            loadError = true
        }
    }

    // Thumbnail: 200 x 90dp
    val borderColor = when {
        isSelected -> Color(0xFFFFEB3B)           // Gelb: ausgewaehlt
        hasLocalAudio -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)  // Blau: Audio vorhanden
        else -> Color.Transparent
    }
    val borderWidth = when {
        isSelected -> 2.dp
        hasLocalAudio -> 1.5.dp
        else -> 0.dp
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .then(
                if (borderWidth > 0.dp) Modifier.border(borderWidth, borderColor, MaterialTheme.shapes.medium)
                else Modifier
            ),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            // Sonogramm-Thumbnail
            Box(modifier = Modifier.fillMaxWidth().height(60.dp)) {
                when {
                    isCorrupt -> {
                        // Korruptes/schwarzes Bild: dezenter Platzhalter
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Image,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                                    Text(
                                        "n/a",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                    }
                    bitmap != null -> {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawImage(
                                image = bitmap!!,
                                dstSize = IntSize(size.width.toInt(), size.height.toInt())
                            )
                        }
                    }
                    loadError -> {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "n/a",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    else -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 1.5.dp
                            )
                        }
                    }
                }

                // Playback-Pointer (vertikaler Strich)
                if (isPlaying && playbackProgress > 0f) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val posX = size.width * playbackProgress
                        drawLine(
                            color = Color(0xFFFF4444),
                            start = Offset(posX, 0f),
                            end = Offset(posX, size.height),
                            strokeWidth = 2f
                        )
                    }
                }

                // Play-Button Overlay (unten rechts)
                if (bitmap != null && !isCorrupt) {
                    if (isDownloading) {
                        // Downloading-State: Spinner
                        Box(
                            modifier = Modifier.align(Alignment.BottomEnd).size(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = Color.Black.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 1.5.dp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    } else {
                        IconButton(
                            onClick = onPlay,
                            modifier = Modifier.align(Alignment.BottomEnd).size(24.dp)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = if (isPlaying) Color(0xCC4CAF50) else Color.Black.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Stoppen" else "Abspielen",
                                    tint = Color.White,
                                    modifier = Modifier.padding(1.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Art-Name + Qualitaet
            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                val displayName = SpeciesRegistry.getDisplayName(result.scientificName, speciesLocale)
                Text(
                    displayName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Qualitaets-Badge
                    if (result.quality.isNotBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = qualityColor(result.quality)
                        ) {
                            Text(
                                "Q:${result.quality}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                                color = Color.White
                            )
                        }
                    }
                    if (result.type.isNotBlank()) {
                        Text(
                            result.type,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1
                        )
                    }
                    val idLabel = if (result.recordingId.all { it.isDigit() }) "#${result.recordingId}" else "XC${result.recordingId}"
                    Text(
                        idLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

// ================================================================
// Hilfsfunktionen fuer Bild-Laden (gemeinsam genutzt)
// ================================================================


private fun qualityColor(q: String) = when (q.uppercase()) {
    "A" -> Color(0xFF4CAF50)
    "B" -> Color(0xFF8BC34A)
    "C" -> Color(0xFFFFC107)
    "D" -> Color(0xFFFF9800)
    else -> Color(0xFFF44336)
}
