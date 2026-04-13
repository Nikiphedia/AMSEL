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
import ch.etasystems.amsel.core.audio.AudioSlice

/**
 * Slice-Auswahl in der Seitenleiste.
 * Zeigt Navigation [<] Label [>] und scrollbare Slice-Liste mit Annotation-Badges.
 */
@Composable
fun SliceSelector(
    slices: List<AudioSlice>,
    activeSliceIndex: Int,
    annotationCounts: Map<Int, Int>,
    onSelectSlice: (Int) -> Unit,
    onPreviousSlice: () -> Unit,
    onNextSlice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(8.dp)) {
        // Header
        Text(
            "Slices (${slices.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Navigation: [<] Slice-Label [>]
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onPreviousSlice,
                enabled = activeSliceIndex > 0,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Vorheriger Slice")
            }

            Text(
                slices.getOrNull(activeSliceIndex)?.displayLabel() ?: "",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )

            IconButton(
                onClick = onNextSlice,
                enabled = activeSliceIndex < slices.size - 1,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Naechster Slice")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Slice-Liste (scrollbar)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(slices) { index, slice ->
                val isActive = index == activeSliceIndex
                val count = annotationCounts[index] ?: 0
                SliceItem(
                    slice = slice,
                    isActive = isActive,
                    annotationCount = count,
                    onClick = { onSelectSlice(index) }
                )
            }
        }
    }
}

@Composable
private fun SliceItem(
    slice: AudioSlice,
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
            slice.shortLabel(),
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
