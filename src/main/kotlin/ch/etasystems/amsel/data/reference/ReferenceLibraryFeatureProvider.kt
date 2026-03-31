package ch.etasystems.amsel.data.reference

import ch.etasystems.amsel.core.similarity.FeatureCacheProvider
import ch.etasystems.amsel.core.similarity.RecordingInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adapter: Delegiert FeatureCacheProvider-Aufrufe an ReferenceLibrary (Metadaten)
 * und einen separaten Feature-Cache-Ordner (berechnete Feature-Vektoren).
 *
 * Feature .bin Dateien bleiben in ~/Documents/AMSEL/cache/ (getrennt von Referenzen).
 */
class ReferenceLibraryFeatureProvider(
    private val library: ReferenceLibrary,
    private val featureCacheDir: File = defaultFeatureCacheDir()
) : FeatureCacheProvider {

    init { featureCacheDir.mkdirs() }

    override fun loadAllFeatures(cacheKey: String): Map<String, ByteArray> {
        val dir = File(featureCacheDir, cacheKey)
        if (!dir.exists()) return emptyMap()

        val result = mutableMapOf<String, ByteArray>()
        val allRecordings = library.getSpeciesList().flatMap { library.getRecordingsForSpecies(it) }
        for (rec in allRecordings) {
            val file = File(dir, "ref_${rec.id}.bin")
            if (file.exists()) {
                result[rec.id] = file.readBytes()
            }
        }
        return result
    }

    override fun loadAllMfccFeatures(): Map<String, FloatArray> {
        val mfccDir = File(featureCacheDir, "mfcc")
        if (!mfccDir.exists()) return emptyMap()

        val result = mutableMapOf<String, FloatArray>()
        val allRecordings = library.getSpeciesList().flatMap { library.getRecordingsForSpecies(it) }
        for (rec in allRecordings) {
            val file = File(mfccDir, "ref_${rec.id}.bin")
            if (file.exists()) {
                val bytes = file.readBytes()
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val floats = FloatArray(bytes.size / 4)
                buffer.asFloatBuffer().get(floats)
                result[rec.id] = floats
            }
        }
        return result
    }

    override fun getRecordingInfos(): List<RecordingInfo> =
        library.getSpeciesList().flatMap { sciName ->
            library.getRecordingsForSpecies(sciName).map { rec ->
                val sonoUrl = if (rec.pngFile != null) {
                    "file:///${rec.pngFile.absolutePath.replace('\\', '/')}"
                } else ""
                RecordingInfo(
                    recordingId = rec.id,
                    species = "",  // Englischer Name nicht in ReferenceLibrary gespeichert
                    scientificName = rec.scientificName,
                    sonogramUrl = sonoUrl,
                    audioUrl = rec.wavFile?.absolutePath ?: "",
                    quality = rec.qualitaet,
                    country = "",
                    type = rec.typ
                )
            }
        }

    companion object {
        fun defaultFeatureCacheDir(): File {
            val userHome = System.getProperty("user.home")
            return File(userHome, "Documents/AMSEL/cache").also { it.mkdirs() }
        }
    }
}
