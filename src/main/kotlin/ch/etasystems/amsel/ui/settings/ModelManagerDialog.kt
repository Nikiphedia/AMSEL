package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.etasystems.amsel.data.DownloadableModel
import ch.etasystems.amsel.data.ModelDownloader
import ch.etasystems.amsel.data.ModelEntry
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun ModelManagerDialog(
    models: List<ModelEntry>,
    activeModel: String,
    onSelectModel: (String) -> Unit,
    onAddModel: (File, File?) -> Unit,
    onRemoveModel: (String) -> Unit,
    onDismiss: () -> Unit,
    knownModels: List<DownloadableModel> = emptyList(),
    modelsDir: File? = null,
    onDownloadModel: ((DownloadableModel) -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.width(550.dp).heightIn(min = 300.dp, max = 600.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    "Modelle verwalten",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (model in models) {
                        val isActive = model.filename == activeModel
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(
                                    selected = isActive,
                                    onClick = { onSelectModel(model.filename) }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        model.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val infoparts = mutableListOf<String>()
                                    if (model.version.isNotBlank()) infoparts.add("v${model.version}")
                                    if (model.speciesCount != null) infoparts.add("${model.speciesCount} Arten")
                                    if (model.fileSizeMB != null) infoparts.add("${model.fileSizeMB} MB")
                                    infoparts.add(model.type)
                                    Text(
                                        infoparts.joinToString(" | "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        model.filename,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                // Loeschen nur fuer custom Modelle
                                if (model.type == "custom") {
                                    IconButton(onClick = { onRemoveModel(model.filename) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Entfernen",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val chooser = JFileChooser()
                            chooser.dialogTitle = "ONNX-Modell auswaehlen"
                            chooser.fileFilter = FileNameExtensionFilter("ONNX-Modelle (*.onnx)", "onnx")
                            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                val onnxFile = chooser.selectedFile
                                // Optional: Labels-Datei
                                val labelsChooser = JFileChooser()
                                labelsChooser.dialogTitle = "Labels-CSV (optional)"
                                labelsChooser.fileFilter = FileNameExtensionFilter("CSV-Dateien (*.csv)", "csv")
                                val labelsFile = if (labelsChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    labelsChooser.selectedFile
                                } else null
                                onAddModel(onnxFile, labelsFile)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Modell hinzufuegen...")
                    }

                    TextButton(onClick = onDismiss) {
                        Text("Schliessen")
                    }
                }

                // === Verfuegbare Downloads ===
                if (knownModels.isNotEmpty() && modelsDir != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        "Verfuegbare Modelle",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    val scope = rememberCoroutineScope()
                    var downloadingId by remember { mutableStateOf<String?>(null) }
                    var downloadProgress by remember { mutableStateOf(0f) }
                    var downloadError by remember { mutableStateOf<String?>(null) }

                    for (known in knownModels) {
                        val installed = File(modelsDir, known.expectedFilename).exists()
                        val isDownloading = downloadingId == known.id

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(known.name, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                                Text(known.description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                            when {
                                installed -> {
                                    Text("Installiert", style = MaterialTheme.typography.labelSmall,
                                        color = androidx.compose.ui.graphics.Color(0xFF4CAF50))
                                }
                                isDownloading -> {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        LinearProgressIndicator(
                                            progress = { downloadProgress },
                                            modifier = Modifier.width(80.dp)
                                        )
                                        Text("${(downloadProgress * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                else -> {
                                    Button(
                                        onClick = {
                                            if (known.downloadUrl.isBlank()) {
                                                downloadError = "Download-URL nicht verfuegbar"
                                                return@Button
                                            }
                                            downloadingId = known.id
                                            downloadProgress = 0f
                                            downloadError = null
                                            scope.launch {
                                                val target = File(modelsDir, known.expectedFilename)
                                                val success = ModelDownloader.download(
                                                    url = known.downloadUrl,
                                                    targetFile = target,
                                                    onProgress = { p ->
                                                        downloadProgress = if (p.totalBytes > 0)
                                                            p.bytesDownloaded.toFloat() / p.totalBytes
                                                        else 0f
                                                        if (p.error != null) downloadError = p.error
                                                    }
                                                )
                                                downloadingId = null
                                                if (!success && downloadError == null) {
                                                    downloadError = "Download fehlgeschlagen"
                                                }
                                            }
                                        },
                                        enabled = downloadingId == null && known.downloadUrl.isNotBlank(),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Download", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    // Fehler-Anzeige
                    if (downloadError != null) {
                        Text(
                            downloadError!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
