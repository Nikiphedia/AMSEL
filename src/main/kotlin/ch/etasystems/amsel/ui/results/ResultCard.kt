package ch.etasystems.amsel.ui.results

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

@Composable
fun ResultCard(
    result: MatchResult,
    onClick: () -> Unit,
    onPlayAudio: ((MatchResult) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(result.sonogramUrl) { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember(result.sonogramUrl) { mutableStateOf(false) }

    LaunchedEffect(result.sonogramUrl) {
        if (result.sonogramUrl.isBlank()) {
            loadError = true
            return@LaunchedEffect
        }

        try {
            bitmap = withContext(Dispatchers.IO) {
                val bufferedImage = if (result.sonogramUrl.startsWith("file:///")) {
                    // Lokale Datei aus Offline-Cache
                    val path = result.sonogramUrl.removePrefix("file:///")
                    val file = File(path)
                    if (file.exists()) ImageIO.read(file) else null
                } else {
                    // Remote URL (Xeno-Canto)
                    val url = URI(result.sonogramUrl).toURL()
                    ImageIO.read(url)
                }
                bufferedImage?.toComposeImageBitmap()
            }
            if (bitmap == null) loadError = true
        } catch (_: Exception) {
            loadError = true
        }
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            // Sonogramm-Bild
            Box(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!,
                        contentDescription = "${result.species} Sonogramm",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillWidth
                    )
                } else if (loadError) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Bild nicht verfuegbar",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                // Play-Button Overlay (unten rechts auf dem Bild)
                if (onPlayAudio != null) {
                    IconButton(
                        onClick = { onPlayAudio(result) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(32.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Abspielen",
                                tint = Color.White,
                                modifier = Modifier.padding(2.dp)
                            )
                        }
                    }
                }
            }

            // Info
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    result.species,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Text(
                    result.scientificName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = qualityColor(result.quality)
                    ) {
                        Text(
                            "Q:${result.quality}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    if (result.type.isNotBlank()) {
                        Text(
                            result.type,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    Text(
                        result.country,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                // Similarity-Balken
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { result.similarity },
                        modifier = Modifier.weight(1f).height(6.dp),
                        color = similarityColor(result.similarity),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        "${(result.similarity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = similarityColor(result.similarity)
                    )
                }
            }
        }
    }
}

private fun qualityColor(q: String) = when (q.uppercase()) {
    "A" -> Color(0xFF4CAF50)
    "B" -> Color(0xFF8BC34A)
    "C" -> Color(0xFFFFC107)
    "D" -> Color(0xFFFF9800)
    else -> Color(0xFFF44336)
}

private fun similarityColor(score: Float) = when {
    score >= 0.8f -> Color(0xFF4CAF50)
    score >= 0.6f -> Color(0xFF8BC34A)
    score >= 0.4f -> Color(0xFFFFC107)
    else -> Color(0xFFFF9800)
}
