package ch.etasystems.amsel.ui.annotation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.core.audio.AudioChunk

/**
 * Chunk-Auswahl in der Seitenleiste.
 * Zeigt Navigation [<] Label [>] und scrollbare Chunk-Liste mit Annotation-Badges.
 */
@Composable
fun ChunkSelector(
    chunks: List<AudioChunk>,
    activeChunkIndex: Int,
    annotationCounts: Map<Int, Int>,
    onSelectChunk: (Int) -> Unit,
    onPreviousChunk: () -> Unit,
    onNextChunk: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(8.dp)) {
        // Header
        Text(
            "Chunks (${chunks.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Navigation: [<] Chunk-Label [>]
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onPreviousChunk,
                enabled = activeChunkIndex > 0,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Vorheriger Chunk")
            }

            Text(
                chunks.getOrNull(activeChunkIndex)?.displayLabel() ?: "",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )

            IconButton(
                onClick = onNextChunk,
                enabled = activeChunkIndex < chunks.size - 1,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Naechster Chunk")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Chunk-Liste (scrollbar)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(chunks) { index, chunk ->
                val isActive = index == activeChunkIndex
                val count = annotationCounts[index] ?: 0
                ChunkItem(
                    chunk = chunk,
                    isActive = isActive,
                    annotationCount = count,
                    onClick = { onSelectChunk(index) }
                )
            }
        }
    }
}

@Composable
private fun ChunkItem(
    chunk: AudioChunk,
    isActive: Boolean,
    annotationCount: Int,
    onClick: () -> Unit
) {
    val bgColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val textColor = if (isActive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            chunk.shortLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
        if (annotationCount > 0) {
            Badge(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
            ) {
                Text("$annotationCount")
            }
        }
    }
}
