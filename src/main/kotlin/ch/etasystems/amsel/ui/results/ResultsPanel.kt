package ch.etasystems.amsel.ui.results

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.core.annotation.MatchResult

/**
 * Ergebnis-Anzeige mit Gruppierung nach Art + Ruftyp.
 * Zeigt den besten Treffer pro Gruppe als Repraesentant,
 * aufklappbar fuer alle Varianten.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ResultsPanel(
    results: List<MatchResult>,
    isLoading: Boolean,
    onResultClicked: (MatchResult) -> Unit,
    onPlayAudio: ((MatchResult) -> Unit)? = null,
    onShowInSonogram: ((MatchResult) -> Unit)? = null,
    onAdoptSpecies: ((MatchResult) -> Unit)? = null,
    onSearchXenoCanto: ((MatchResult) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // U2c: Rechtsklick-Kontextmenue State
    var rightClickedResult by remember { mutableStateOf<MatchResult?>(null) }
    var rightClickOffset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current

    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Suche laeuft...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            results.isEmpty() -> {
                Text(
                    "Aehnliche Sonogramme erscheinen hier",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            else -> {
                // Nach Art gruppieren, innerhalb nach Ruftyp
                val grouped = remember(results) { groupResults(results) }

                Column {
                    // Header
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ) {
                        Text(
                            "${results.size} Treffer in ${grouped.size} Arten",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Gruppierte Liste mit sichtbarem Scrollbalken
                    Box(modifier = Modifier.fillMaxSize()) {
                        val lazyListState = rememberLazyListState()
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(grouped, key = { it.key }) { group ->
                                SpeciesGroup(
                                    group = group,
                                    onResultClicked = onResultClicked,
                                    onPlayAudio = onPlayAudio,
                                    onResultRightClicked = { result, px, py ->
                                        rightClickedResult = result
                                        rightClickOffset = with(density) {
                                            DpOffset(px.toDp(), py.toDp())
                                        }
                                    }
                                )
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(lazyListState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        )
                    }
                }
            }
        }

        // U2c: Ergebnis-Kontextmenue
        DropdownMenu(
            expanded = rightClickedResult != null,
            onDismissRequest = { rightClickedResult = null },
            offset = rightClickOffset
        ) {
            val result = rightClickedResult ?: return@DropdownMenu
            DropdownMenuItem(
                text = { Text("In Sonogramm anzeigen") },
                onClick = {
                    onShowInSonogram?.invoke(result)
                    rightClickedResult = null
                },
                leadingIcon = { Icon(Icons.Default.ZoomIn, null) }
            )
            DropdownMenuItem(
                text = { Text("Art uebernehmen") },
                onClick = {
                    onAdoptSpecies?.invoke(result)
                    rightClickedResult = null
                },
                leadingIcon = { Icon(Icons.Default.ExpandMore, null) }
            )
            if (onPlayAudio != null) {
                DropdownMenuItem(
                    text = { Text("Abspielen") },
                    onClick = {
                        onPlayAudio.invoke(result)
                        rightClickedResult = null
                    },
                    leadingIcon = { Icon(Icons.Default.ExpandLess, null) }
                )
            }
            DropdownMenuItem(
                text = { Text("Auf Xeno-Canto suchen") },
                onClick = {
                    onSearchXenoCanto?.invoke(result)
                    rightClickedResult = null
                },
                leadingIcon = { Icon(Icons.Default.Search, null) }
            )
        }
    }
}

/**
 * Eine Artgruppe: bester Treffer als Karte, aufklappbar fuer Varianten.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SpeciesGroup(
    group: ResultGroup,
    onResultClicked: (MatchResult) -> Unit,
    onPlayAudio: ((MatchResult) -> Unit)? = null,
    onResultRightClicked: ((MatchResult, Float, Float) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        // Haupt-Karte (bester Treffer der Gruppe)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Ergebnis-Karte
            Box(
                modifier = Modifier.weight(1f)
                    .pointerInput(group.best) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                if (event.type != PointerEventType.Release) continue
                                val change = event.changes.firstOrNull() ?: continue
                                if (!change.previousPressed || change.pressed) continue
                                val btn = event.button ?: continue
                                if (btn != androidx.compose.ui.input.pointer.PointerButton.Secondary) continue
                                change.consume()
                                onResultRightClicked?.invoke(group.best, change.position.x, change.position.y)
                            }
                        }
                    }
            ) {
                ResultCard(
                    result = group.best,
                    onClick = { onResultClicked(group.best) },
                    onPlayAudio = onPlayAudio
                )
            }

            // Gruppen-Info Seitenleiste
            if (group.variants.size > 1) {
                Column(
                    modifier = Modifier
                        .width(60.dp)
                        .clickable { expanded = !expanded }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Anzahl Badge
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    ) {
                        Text(
                            "${group.variants.size}x",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Ruftypen als Chips
                    for (type in group.types.take(3)) {
                        Text(
                            type,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1
                        )
                    }
                    if (group.types.size > 3) {
                        Text(
                            "+${group.types.size - 3}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }

                    // Expand Icon
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Zuklappen" else "Alle anzeigen",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // Aufgeklappte Varianten als horizontale scrollbare Reihe
        AnimatedVisibility(visible = expanded) {
            Box(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp)) {
                val rowState = rememberLazyListState()
                LazyRow(
                    state = rowState,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(end = 8.dp)
                ) {
                    items(group.variants.drop(1), key = { it.recordingId }) { variant ->
                        Box(
                            modifier = Modifier.width(200.dp)
                                .pointerInput(variant) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Main)
                                            if (event.type != PointerEventType.Release) continue
                                            val change = event.changes.firstOrNull() ?: continue
                                            if (!change.previousPressed || change.pressed) continue
                                            val btn = event.button ?: continue
                                            if (btn != androidx.compose.ui.input.pointer.PointerButton.Secondary) continue
                                            change.consume()
                                            onResultRightClicked?.invoke(variant, change.position.x, change.position.y)
                                        }
                                    }
                                }
                        ) {
                            ResultCard(
                                result = variant,
                                onClick = { onResultClicked(variant) },
                                onPlayAudio = onPlayAudio
                            )
                        }
                    }
                }
                if (group.variants.size > 2) {
                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(rowState),
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

// ================================================================
// Gruppierungs-Logik
// ================================================================

private data class ResultGroup(
    val key: String,               // scientificName
    val best: MatchResult,         // Hoechste Similarity
    val variants: List<MatchResult>, // Alle Treffer, sortiert nach Similarity
    val types: List<String>        // Vorkommende Ruftypen ("song", "call", ...)
)

/**
 * Gruppiert Ergebnisse nach Art.
 * Innerhalb jeder Art: sortiert nach Similarity absteigend.
 * Gruppen untereinander: sortiert nach bester Similarity.
 */
private fun groupResults(results: List<MatchResult>): List<ResultGroup> {
    return results
        .groupBy { it.scientificName }
        .map { (name, entries) ->
            val sorted = entries.sortedByDescending { it.similarity }
            val types = entries.map { it.type }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            ResultGroup(
                key = name,
                best = sorted.first(),
                variants = sorted,
                types = types
            )
        }
        .sortedByDescending { it.best.similarity }
}
