package ch.etasystems.amsel.ui.annotation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.core.audio.AudioSlice
import ch.etasystems.amsel.ui.compare.AudioManager

/**
 * Panel das alle geladenen Audio-Dateien auflistet.
 * Aktive Datei ist hervorgehoben. Klick wechselt die aktive Datei.
 * Slices werden als ausklappbare Unter-Eintraege angezeigt.
 */
@Composable
fun AudiofilesPanel(
    loadedFiles: Map<String, AudioManager.AudioFileState>,
    activeFileId: String?,
    activeSliceIndex: Int = 0,
    onSelectFile: (String) -> Unit,
    onSelectSlice: (Int) -> Unit = {},
    onRemoveFile: (String) -> Unit,
    onAddFile: () -> Unit,
    annotationCount: Int = 0,
    isFocused: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Audiofiles (${loadedFiles.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (annotationCount > 0) {
                    Text(
                        "Markierungen: $annotationCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(
                onClick = onAddFile,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Audio hinzufuegen",
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (loadedFiles.isEmpty()) {
            Text(
                "Keine Dateien geladen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(loadedFiles.entries.toList(), key = { it.key }) { (fileId, fileState) ->
                    val isActive = fileId == activeFileId
                    // Annotation-Counts pro Slice berechnen
                    val annotationCounts = if (isActive && fileState.sliceManager != null) {
                        fileState.sliceManager?.annotationCountsPerSlice(emptyList()) ?: emptyMap()
                    } else {
                        emptyMap()
                    }
                    AudiofileItem(
                        fileState = fileState,
                        isActive = isActive,
                        activeSliceIndex = activeSliceIndex,
                        annotationCounts = annotationCounts,
                        onSelect = { onSelectFile(fileId) },
                        onSelectSlice = onSelectSlice,
                        onRemove = { onRemoveFile(fileId) },
                        isHighlighted = isFocused && isActive
                    )
                }
            }
        }
    }
}

@Composable
private fun AudiofileItem(
    fileState: AudioManager.AudioFileState,
    isActive: Boolean,
    activeSliceIndex: Int,
    annotationCounts: Map<Int, Int>,
    onSelect: () -> Unit,
    onSelectSlice: (Int) -> Unit,
    onRemove: () -> Unit,
    isHighlighted: Boolean = false
) {
    var expanded by remember { mutableStateOf(isActive) }
    val hasSlices = fileState.sliceManager != null && (fileState.sliceManager?.sliceCount ?: 0) > 1

    // Automatisch ausklappen wenn Datei aktiv wird
    LaunchedEffect(isActive) {
        if (isActive && hasSlices) expanded = true
    }

    Column {
        // --- Datei-Zeile ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(when {
                    isHighlighted -> MaterialTheme.colorScheme.secondaryContainer
                    isActive -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surface
                })
                .clickable(onClick = onSelect)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Expand/Collapse Icon (nur wenn Slices vorhanden)
            if (hasSlices) {
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = if (expanded) "Einklappen" else "Ausklappen",
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else {
                Spacer(Modifier.width(20.dp))
            }

            Icon(
                Icons.Default.AudioFile,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    fileState.audioFile.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatDuration(fileState.durationSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Entfernen",
                    modifier = Modifier.size(14.dp),
                    tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        // --- Slice-Liste (ausgeklappt) ---
        if (expanded && hasSlices && isActive) {
            val sm = fileState.sliceManager!!
            sm.slices.forEach { slice ->
                val isActiveSlice = slice.index == activeSliceIndex
                val count = annotationCounts[slice.index] ?: 0
                SliceSubItem(
                    slice = slice,
                    isActive = isActiveSlice,
                    annotationCount = count,
                    onClick = { onSelectSlice(slice.index) }
                )
            }
        }
    }
}

@Composable
private fun SliceSubItem(
    slice: AudioSlice,
    isActive: Boolean,
    annotationCount: Int,
    onClick: () -> Unit
) {
    val bgColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            slice.shortLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
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

private fun formatDuration(sec: Float): String {
    val m = (sec / 60).toInt()
    val s = (sec % 60).toInt()
    return "${m}:${s.toString().padStart(2, '0')}"
}
