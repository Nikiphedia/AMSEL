package ch.etasystems.amsel.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

@Serializable
data class ModelEntry(
    val filename: String,
    val labelsFilename: String? = null,
    val name: String,
    val version: String = "",
    val type: String,
    val speciesCount: Int? = null,
    val fileSizeMB: Long? = null
)

@Serializable
data class ModelsConfig(
    val activeModel: String = "birdnet_v3.onnx",
    val models: List<ModelEntry> = emptyList()
)

class ModelRegistry(
    private val modelsDir: File = defaultModelsDir()
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val configFile get() = File(modelsDir, "models.json")

    companion object {
        private fun defaultModelsDir(): File {
            val userHome = System.getProperty("user.home")
            return File(userHome, "Documents/AMSEL/models")
        }
    }

    fun load(): ModelsConfig {
        modelsDir.mkdirs()
        return try {
            if (configFile.exists()) {
                val config = json.decodeFromString<ModelsConfig>(configFile.readText())
                // Ergaenze ggf. fehlende Modelle aus Ordner-Scan
                val scanned = scanModels()
                val knownFilenames = config.models.map { it.filename }.toSet()
                val newModels = scanned.filter { it.filename !in knownFilenames }
                if (newModels.isNotEmpty()) {
                    val updated = config.copy(models = config.models + newModels)
                    save(updated)
                    updated
                } else {
                    config
                }
            } else {
                val scanned = scanModels()
                val config = ModelsConfig(models = scanned)
                save(config)
                config
            }
        } catch (e: Exception) {
            logger.warn("models.json lesen fehlgeschlagen: {}", e.message)
            val scanned = scanModels()
            ModelsConfig(models = scanned)
        }
    }

    fun save(config: ModelsConfig) {
        try {
            modelsDir.mkdirs()
            configFile.writeText(json.encodeToString(ModelsConfig.serializer(), config))
        } catch (e: Exception) {
            logger.warn("models.json schreiben fehlgeschlagen: {}", e.message)
        }
    }

    fun scanModels(): List<ModelEntry> {
        if (!modelsDir.exists()) return emptyList()

        val entries = mutableListOf<ModelEntry>()

        val onnxFiles = modelsDir.listFiles { f -> f.extension.lowercase() == "onnx" } ?: emptyArray()
        for (onnxFile in onnxFiles) {
            val entry = classifyModel(onnxFile)
            entries.add(entry)
        }

        // Virtueller Eintrag fuer Python-Bridge (BirdNET V2.4)
        entries.add(
            ModelEntry(
                filename = "_python_bridge",
                name = "BirdNET V2.4 (Python)",
                version = "2.4",
                type = "birdnet_v2",
                speciesCount = 6362,
                fileSizeMB = null
            )
        )

        return entries
    }

    fun addModel(sourceFile: File, labelsFile: File?): ModelEntry {
        modelsDir.mkdirs()
        val targetOnnx = File(modelsDir, sourceFile.name)
        sourceFile.copyTo(targetOnnx, overwrite = true)

        val targetLabels = if (labelsFile != null) {
            val lf = File(modelsDir, labelsFile.name)
            labelsFile.copyTo(lf, overwrite = true)
            lf
        } else null

        val entry = classifyModel(targetOnnx).copy(
            labelsFilename = targetLabels?.name
        )

        val config = load()
        val updated = config.copy(
            models = config.models.filter { it.filename != entry.filename } + entry
        )
        save(updated)

        return entry
    }

    fun removeModel(filename: String) {
        val config = load()
        val model = config.models.find { it.filename == filename } ?: return

        // BirdNET-Modelle nicht loeschen
        if (model.type == "birdnet_v3" || model.type == "birdnet_v2") return

        File(modelsDir, filename).delete()
        if (model.labelsFilename != null) {
            File(modelsDir, model.labelsFilename).delete()
        }

        val updated = config.copy(
            models = config.models.filter { it.filename != filename },
            activeModel = if (config.activeModel == filename) "birdnet_v3.onnx" else config.activeModel
        )
        save(updated)
    }

    fun isModelAvailable(filename: String): Boolean {
        if (filename == "_python_bridge") return true
        return File(modelsDir, filename).exists()
    }

    private fun classifyModel(onnxFile: File): ModelEntry {
        val name = onnxFile.nameWithoutExtension
        val sizeMB = onnxFile.length() / (1024 * 1024)

        // Labels-Datei suchen (gleicher Name mit .csv)
        val labelsFile = listOf(
            File(modelsDir, "${onnxFile.nameWithoutExtension}_labels.csv"),
            File(modelsDir, "birdnet_v3_labels.csv"),
            File(modelsDir, "BirdNET+_V3.0-preview3_Global_11K_Labels.csv")
        ).firstOrNull { it.exists() }

        val labelsCount = labelsFile?.let { countCsvLines(it) }

        return when {
            name.contains("birdnet_v3", ignoreCase = true) ||
            name.contains("BirdNET+_V3", ignoreCase = true) -> ModelEntry(
                filename = onnxFile.name,
                labelsFilename = labelsFile?.name,
                name = "BirdNET V3.0",
                version = "preview3",
                type = "birdnet_v3",
                speciesCount = labelsCount ?: 11560,
                fileSizeMB = sizeMB
            )
            name.contains("efficientnet", ignoreCase = true) -> ModelEntry(
                filename = onnxFile.name,
                labelsFilename = labelsFile?.name,
                name = "EfficientNet Bird",
                version = "",
                type = "custom",
                speciesCount = labelsCount,
                fileSizeMB = sizeMB
            )
            else -> ModelEntry(
                filename = onnxFile.name,
                labelsFilename = labelsFile?.name,
                name = name,
                version = "",
                type = "custom",
                speciesCount = labelsCount,
                fileSizeMB = sizeMB
            )
        }
    }

    private fun countCsvLines(file: File): Int? {
        return try {
            val lines = file.readLines()
            if (lines.size > 1) lines.size - 1 else null
        } catch (_: Exception) {
            null
        }
    }
}
