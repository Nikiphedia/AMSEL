package ch.etasystems.amsel.ui.results

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.core.annotation.MatchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ReferenceSonogramPanel")

/**
 * Zeigt das Referenz-Sonogramm einer ausgewaehlten Datenbank-Aufnahme an.
 * Das Bild wird im Original-Seitenverhaeltnis angezeigt (kein Stretching).
 * Dunkler Hintergrund passend zum Sonogramm-Theme.
 */
@Composable
fun ReferenceSonogramPanel(
    result: MatchResult,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(result.sonogramUrl) { mutableStateOf<ImageBitmap?>(null) }
    var imageAspect by remember(result.sonogramUrl) { mutableStateOf(4f) }  // width/height
    var loadError by remember(result.sonogramUrl) { mutableStateOf(false) }

    LaunchedEffect(result.sonogramUrl) {
        logger.debug("[ReferenceSono] LaunchedEffect: url='{}'", result.sonogramUrl)
        if (result.sonogramUrl.isBlank()) {
            logger.debug("[ReferenceSono] URL ist leer!")
            loadError = true
            return@LaunchedEffect
        }
        loadError = false
        bitmap = null

        try {
            logger.debug("[ReferenceSono] Starte Laden...")
            val (bmp, aspect) = withContext(Dispatchers.IO) {
                val raw = ImageLoader.loadBufferedImage(result.sonogramUrl)
                if (raw == null) return@withContext Pair(null, 4f)
                val scaled = ImageLoader.scaleIfNeeded(raw, maxWidth = 2000)
                if (ImageLoader.isImageBlack(scaled)) {
                    logger.debug("[ReferenceSono] Bild ist komplett schwarz/korrupt — ueberspringe")
                    return@withContext Pair(null, 4f)
                }
                val composeBmp = try {
                    ImageLoader.toArgb(scaled).toComposeImageBitmap()
                } catch (e: Exception) {
                    logger.debug("[ReferenceSono] toComposeImageBitmap FEHLER", e)
                    null
                }
                val a = if (raw.height > 0) raw.width.toFloat() / raw.height else 4f
                Pair(composeBmp, a)
            }
            bitmap = bmp
            imageAspect = aspect
            if (bmp == null) loadError = true
        } catch (e: Exception) {
            loadError = true
            logger.debug("[ReferenceSono] FEHLER: {}, url={}", e.message, result.sonogramUrl)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF1A1A2E),  // Gleicher dunkler Hintergrund wie Sonogramm
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            // Header-Zeile: Info links, Close rechts
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Art-Info kompakt in einer Zeile
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val displayName = result.species.ifBlank { result.scientificName }
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
                    Text(
                        "${(result.similarity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = similarityColorRef(result.similarity)
                    )
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

            Spacer(modifier = Modifier.height(2.dp))

            // Sonogramm-Bild — fuellt verfuegbaren Platz (kein aspectRatio,
            // da aeusserer Container feste Hoehe hat)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)  // Nimmt den restlichen Platz nach Header
                    .background(Color.White)  // Weisser Hintergrund damit dunkle Sonogramme sichtbar sind
            ) {
                if (bitmap != null) {
                    // Canvas-basiertes Rendering (robuster als Image composable)
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val bmp = bitmap!!
                        drawImage(
                            image = bmp,
                            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
                        )
                    }
                } else if (loadError) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Sonogramm nicht verfuegbar",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
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

private fun similarityColorRef(score: Float) = when {
    score >= 0.8f -> Color(0xFF4CAF50)
    score >= 0.6f -> Color(0xFF8BC34A)
    score >= 0.4f -> Color(0xFFFFC107)
    else -> Color(0xFFFF9800)
}
