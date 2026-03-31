package ch.etasystems.amsel.ui.sonogram

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.core.filter.ExpanderGate
import ch.etasystems.amsel.core.filter.FilterConfig
import ch.etasystems.amsel.data.FilterPreset
import ch.etasystems.amsel.data.SettingsStore
import ch.etasystems.amsel.ui.util.formatFreq
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

// ════════════════════════════════════════════════════════════════════
// Wiederverwendbarer Slider mit ◀ ▶ Buttons links/rechts
// ════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    tooltip: String = ""
) {
    Column(modifier = modifier) {
        if (tooltip.isNotEmpty()) {
            LabelWithTooltip("$label: ${format(value)}", tooltip)
        } else {
            Text(
                "$label: ${format(value)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // ◀ Button (kleiner)
            Surface(
                onClick = {
                    val snapped = ((value - step) * (1f / step)).roundToInt() * step
                    onValueChange(snapped.coerceIn(valueRange))
                },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("◀", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            // Slider
            val totalSteps = ((valueRange.endInclusive - valueRange.start) / step).roundToInt()
            Slider(
                value = value.coerceIn(valueRange),
                onValueChange = { raw ->
                    val snapped = ((raw - valueRange.start) / step).roundToInt() * step + valueRange.start
                    onValueChange(snapped.coerceIn(valueRange))
                },
                valueRange = valueRange,
                steps = (totalSteps - 1).coerceAtLeast(0),
                modifier = Modifier.weight(1f).height(20.dp)
            )

            // ▶ Button (groesser)
            Surface(
                onClick = {
                    val snapped = ((value + step) * (1f / step)).roundToInt() * step
                    onValueChange(snapped.coerceIn(valueRange))
                },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("▶", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Variante fuer Integer-Werte */
@Composable
fun StepSliderInt(
    label: String,
    value: Int,
    valueRange: IntRange,
    step: Int = 1,
    format: (Int) -> String = { "$it" },
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tooltip: String = ""
) {
    StepSlider(
        label = label,
        value = value.toFloat(),
        valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
        step = step.toFloat(),
        format = { format(it.roundToInt()) },
        onValueChange = { onValueChange(it.roundToInt()) },
        modifier = modifier,
        tooltip = tooltip
    )
}

// ════════════════════════════════════════════════════════════════════
// Hilfsfunktion: Label mit Tooltip
// ════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelWithTooltip(label: String, tooltip: String) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState()
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

// ════════════════════════════════════════════════════════════════════
// Hauptpanel
// ════════════════════════════════════════════════════════════════════

@Composable
fun FilterPanel(
    config: FilterConfig,
    isBatMode: Boolean,
    onConfigChanged: (FilterConfig) -> Unit,
    onClose: () -> Unit,
    onNormalize: () -> Unit = {},
    hasAudio: Boolean = false,
    isNormalized: Boolean = false,
    normGainDb: Float = 0f,
    modifier: Modifier = Modifier
) {
    var localConfig by remember(config) { mutableStateOf(config) }
    var presets by remember { mutableStateOf(SettingsStore.loadPresets()) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var savePresetName by remember { mutableStateOf("") }
    var selectedPresetName by remember { mutableStateOf<String?>(null) }
    var presetDropdownExpanded by remember { mutableStateOf(false) }
    var showAdvancedGate by remember { mutableStateOf(false) }

    fun update(newConfig: FilterConfig) {
        localConfig = newConfig
        onConfigChanged(newConfig)
    }

    val maxFreq = if (isBatMode) 125000f else 16000f
    val minFreq = if (isBatMode) 10000f else 100f

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // ====== Header mit Preset-Steuerung ======
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(0.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        OutlinedButton(
                            onClick = { presetDropdownExpanded = true },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(selectedPresetName ?: "Preset...", style = MaterialTheme.typography.labelSmall)
                        }
                        DropdownMenu(
                            expanded = presetDropdownExpanded,
                            onDismissRequest = { presetDropdownExpanded = false }
                        ) {
                            if (presets.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Keine Presets", style = MaterialTheme.typography.bodySmall) },
                                    onClick = { presetDropdownExpanded = false },
                                    enabled = false
                                )
                            }
                            for (preset in presets) {
                                DropdownMenuItem(
                                    text = { Text(preset.name, style = MaterialTheme.typography.bodySmall) },
                                    onClick = {
                                        selectedPresetName = preset.name
                                        update(preset.toFilterConfig())
                                        presetDropdownExpanded = false
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = {
                                                SettingsStore.deletePreset(preset.name)
                                                presets = SettingsStore.loadPresets()
                                                if (selectedPresetName == preset.name) selectedPresetName = null
                                            },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, "Loeschen",
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showSaveDialog = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Save, "Preset speichern", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { selectedPresetName = null; update(FilterConfig()) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.RestartAlt, "Reset", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "Schliessen", modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Preset-Speichern
            if (showSaveDialog) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = savePresetName,
                        onValueChange = { savePresetName = it },
                        placeholder = { Text("Preset-Name", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f).height(36.dp),
                        textStyle = MaterialTheme.typography.labelSmall,
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (savePresetName.isNotBlank()) {
                                SettingsStore.savePreset(FilterPreset.fromFilterConfig(savePresetName.trim(), localConfig))
                                presets = SettingsStore.loadPresets()
                                selectedPresetName = savePresetName.trim()
                                savePresetName = ""
                                showSaveDialog = false
                            }
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) { Text("Speichern", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = { showSaveDialog = false; savePresetName = "" }, modifier = Modifier.height(36.dp)) {
                        Text("Abbruch", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ====== Zeile 1: Funktions-Checkboxen (alle gleiche Hoehe) ======
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Feste Hoehe pro Checkbox-Zeile damit Slider darunter auf gleicher Achse liegen
                val cbRowMod = Modifier.height(28.dp)

                Row(modifier = Modifier.weight(1f).then(cbRowMod), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(checked = localConfig.bandpass, onCheckedChange = { update(localConfig.copy(bandpass = it)) }, modifier = Modifier.size(20.dp))
                    LabelWithTooltip("Bandpass 24dB/Oct", "Hochpass + Tiefpass mit 24 dB/Oktave Flanke (Butterworth 4. Ordnung).")
                }
                Row(modifier = Modifier.weight(1f).then(cbRowMod), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(checked = localConfig.limiter, onCheckedChange = { update(localConfig.copy(limiter = it)) }, modifier = Modifier.size(20.dp))
                    LabelWithTooltip("Limiter", "Brickwall: Entfernt alles ueber Schwelle.")
                }
                Row(modifier = Modifier.weight(1f).then(cbRowMod), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(checked = isNormalized, onCheckedChange = { onNormalize() }, enabled = hasAudio, modifier = Modifier.size(20.dp))
                    Text("Norm. -2dBFS", style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable(enabled = hasAudio) { onNormalize() })
                    if (isNormalized) Text("${"%+.1f".format(normGainDb)}dB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Row(modifier = Modifier.weight(1f).then(cbRowMod), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(checked = localConfig.spectralGating, onCheckedChange = { update(localConfig.copy(spectralGating = it)) }, modifier = Modifier.size(20.dp))
                    LabelWithTooltip("Spectral Gate", "Frequenzband-individuell. Erkennt Rauschen automatisch pro Band.")
                }
                Row(modifier = Modifier.weight(1f).then(cbRowMod), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(checked = localConfig.noiseFilter, onCheckedChange = { update(localConfig.copy(noiseFilter = it, spectralSubtraction = false)) }, modifier = Modifier.size(20.dp))
                    LabelWithTooltip("Noise-Filter", "Entfernt leise Anteile unter Schwellenwert.")
                }
                Row(modifier = Modifier.weight(2f).then(cbRowMod), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(checked = localConfig.expanderGate, onCheckedChange = { update(localConfig.copy(expanderGate = it)) }, modifier = Modifier.size(20.dp))
                    LabelWithTooltip("Expander/Gate", "Gate: Stumm unter Schwelle. Expander: Leise Stellen leiser (Ratio).")
                    if (localConfig.expanderGate) {
                        Spacer(modifier = Modifier.width(4.dp))
                        val isGate = localConfig.expanderMode == ExpanderGate.Mode.GATE
                        FilterChip(selected = !isGate, onClick = { update(localConfig.copy(expanderMode = ExpanderGate.Mode.EXPANDER)) },
                            label = { Text("Weich", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(22.dp))
                        FilterChip(selected = isGate, onClick = { update(localConfig.copy(expanderMode = ExpanderGate.Mode.GATE)) },
                            label = { Text("Gate", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(22.dp))
                        FilterChip(selected = showAdvancedGate, onClick = { showAdvancedGate = !showAdvancedGate },
                            label = { Text("Erweitert", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(22.dp))
                    }
                }
                Row(modifier = Modifier.weight(1f).then(cbRowMod), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(checked = localConfig.medianFilter, onCheckedChange = { update(localConfig.copy(medianFilter = it)) }, modifier = Modifier.size(20.dp))
                    LabelWithTooltip("Median", "Glaettet das Sonogramm. 1=aus, 2-3=fein, 5+=aggressiv.")
                }
            }

            // ====== Zeile 2: Parameter/Slider (gleiche Spaltenbreite wie oben) ======
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ═══ Bandpass Slider ═══
                Column(modifier = Modifier.weight(1f)) {
                    if (localConfig.bandpass) {
                        val logMin = ln(minFreq)
                        val logMax = ln(maxFreq)
                        val lowNorm = ((ln(localConfig.bandpassLowHz.coerceIn(minFreq, maxFreq)) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
                        StepSlider(
                            label = "Tief: ${formatFreq(localConfig.bandpassLowHz)}",
                            value = lowNorm, valueRange = 0f..1f, step = 0.01f, format = { "" },
                            onValueChange = { norm ->
                                val hz = exp(logMin + norm * (logMax - logMin)).coerceAtMost(localConfig.bandpassHighHz * 0.9f)
                                update(localConfig.copy(bandpassLowHz = hz))
                            },
                            tooltip = "Untere Grenzfrequenz (Hochpass)."
                        )
                        val highNorm = ((ln(localConfig.bandpassHighHz.coerceIn(minFreq, maxFreq)) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
                        StepSlider(
                            label = "Hoch: ${formatFreq(localConfig.bandpassHighHz)}",
                            value = highNorm, valueRange = 0f..1f, step = 0.01f, format = { "" },
                            onValueChange = { norm ->
                                val hz = exp(logMin + norm * (logMax - logMin)).coerceAtLeast(localConfig.bandpassLowHz * 1.1f)
                                update(localConfig.copy(bandpassHighHz = hz))
                            },
                            tooltip = "Obere Grenzfrequenz (Tiefpass)."
                        )
                    }
                }

                // ═══ Limiter Slider ═══
                Column(modifier = Modifier.weight(1f)) {
                    if (localConfig.limiter) {
                        StepSlider(
                            label = "Ceiling",
                            value = -localConfig.limiterThresholdDb,
                            valueRange = 0f..40f,
                            step = 0.2f,
                            format = { "${"%.1f".format(-it)} dB" },
                            onValueChange = { update(localConfig.copy(limiterThresholdDb = -it)) },
                            tooltip = "Obergrenze. Alles darueber wird abgeschnitten."
                        )
                    }
                }

                // ═══ Normalisierung (kein Slider — Gain wird in Zeile 1 angezeigt) ═══
                Column(modifier = Modifier.weight(1f)) {}

                // ═══ Spectral Gate Slider ═══
                Column(modifier = Modifier.weight(1f)) {
                    if (localConfig.spectralGating) {
                        StepSlider(
                            label = "Schwelle", value = -localConfig.spectralGatingThresholdDb,
                            valueRange = 5f..80f, step = 0.5f,
                            format = { "-${it.roundToInt()} dB" },
                            onValueChange = { update(localConfig.copy(spectralGatingThresholdDb = -it)) },
                            tooltip = "dB unter Peak. Links=aggressiv (wenig bleibt), rechts=konservativ (viel bleibt)."
                        )
                        StepSlider(
                            label = "Weichheit", value = localConfig.spectralGatingSoftness,
                            valueRange = 0.5f..10f, step = 0.5f,
                            format = { "${it.roundToInt()} dB" },
                            onValueChange = { update(localConfig.copy(spectralGatingSoftness = it)) },
                            tooltip = "Uebergangsbreite in dB. 2=scharf, 5=normal, 10=sehr weich."
                        )
                    }
                }

                // ═══ Noise-Filter Slider ═══
                Column(modifier = Modifier.weight(1f)) {
                    if (localConfig.noiseFilter) {
                        StepSlider(
                            label = "Schwelle", value = localConfig.noiseFilterPercent,
                            valueRange = 0f..95f, step = 0.5f, format = { "${"%.1f".format(it)} dB" },
                            onValueChange = { update(localConfig.copy(noiseFilterPercent = it)) },
                            tooltip = "Prozent des Dynamikbereichs der entfernt wird."
                        )
                    }
                }

                // ═══ Expander/Gate Slider ═══
                Column(modifier = Modifier.weight(2f)) {
                    if (localConfig.expanderGate) {
                        // Hauptregler: Schwelle + Ratio (immer sichtbar)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StepSlider("Schwelle", localConfig.expanderThreshold, -40f..16f, 0.2f,
                                { "${"%.1f".format(it)} dB" },
                                { update(localConfig.copy(expanderThreshold = it)) }, Modifier.weight(1f),
                                tooltip = "0dB = Median, negativ = empfindlicher.")
                            if (localConfig.expanderMode == ExpanderGate.Mode.EXPANDER) {
                                StepSlider("Ratio", localConfig.expanderRatio, 1.5f..8f, 0.5f,
                                    { "1:${"%.1f".format(it)}" },
                                    { update(localConfig.copy(expanderRatio = it)) }, Modifier.weight(1f),
                                    tooltip = "1:2 = sanft, 1:8 = stark.")
                            }
                        }
                        // Erweiterte Parameter (aufklappbar)
                        AnimatedVisibility(visible = showAdvancedGate) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StepSlider("Range", localConfig.expanderRangeDb, -120f..0f, 1f,
                                        { "${it.roundToInt()} dB" },
                                        { update(localConfig.copy(expanderRangeDb = it)) }, Modifier.weight(1f),
                                        tooltip = "Maximale Absenkung.")
                                    StepSlider("Knee", localConfig.expanderKneeDb, 0f..12f, 0.5f,
                                        { "${"%.1f".format(it)} dB" },
                                        { update(localConfig.copy(expanderKneeDb = it)) }, Modifier.weight(1f),
                                        tooltip = "0 = hart, 6 = weich.")
                                    StepSlider("Hysterese", localConfig.expanderHysteresisDb, 0f..6f, 0.2f,
                                        { "${"%.1f".format(it)} dB" },
                                        { update(localConfig.copy(expanderHysteresisDb = it)) }, Modifier.weight(1f),
                                        tooltip = "Verhindert Flattern.")
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StepSliderInt("Attack", localConfig.expanderAttackFrames, 0..20, 1, { "$it" },
                                        { update(localConfig.copy(expanderAttackFrames = it)) }, Modifier.weight(1f),
                                        tooltip = "Oeffnungszeit in Frames.")
                                    StepSliderInt("Release", localConfig.expanderReleaseFrames, 0..50, 1, { "$it" },
                                        { update(localConfig.copy(expanderReleaseFrames = it)) }, Modifier.weight(1f),
                                        tooltip = "Schliesszeit in Frames.")
                                    StepSliderInt("Hold", localConfig.expanderHoldFrames, 0..30, 1, { "$it" },
                                        { update(localConfig.copy(expanderHoldFrames = it)) }, Modifier.weight(1f),
                                        tooltip = "Mindest-Haltezeit offen.")
                                }
                            }
                        }
                    }
                }

                // ═══ Median Slider ═══
                Column(modifier = Modifier.weight(1f)) {
                    if (localConfig.medianFilter) {
                        StepSliderInt(
                            label = "Kernel", value = localConfig.medianKernelSize,
                            valueRange = 1..10, step = 1, format = { "${it}x${it}" },
                            onValueChange = { v -> update(localConfig.copy(medianKernelSize = v.coerceIn(1, 10))) },
                            tooltip = "Groesse des Glaettungs-Fensters."
                        )
                    }
                }
            }
        }
    }
}

