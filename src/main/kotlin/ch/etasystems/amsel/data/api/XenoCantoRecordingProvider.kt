package ch.etasystems.amsel.data.api

import ch.etasystems.amsel.core.similarity.RecordingInfo
import ch.etasystems.amsel.core.similarity.RecordingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/**
 * Adapter: Delegiert RecordingProvider-Aufrufe an XenoCantoApi.
 * Mappt XenoCantoRecording → RecordingInfo.
 * Download-Logik (inkl. Groessenlimit und Timeouts) gehoert hierher, nicht in die Core-Schicht.
 */
class XenoCantoRecordingProvider(
    private val api: XenoCantoApi,
    private val tempDir: File = File(System.getProperty("java.io.tmpdir"), "amsel_cache")
) : RecordingProvider {

    init {
        tempDir.mkdirs()
    }

    override suspend fun search(query: String, page: Int, perPage: Int): List<RecordingInfo> {
        return api.search(query, page, perPage).map { rec ->
            val sonoUrl = when {
                rec.sono.large.isNotBlank() -> fixUrl(rec.sono.large)
                rec.sono.med.isNotBlank() -> fixUrl(rec.sono.med)
                else -> ""
            }
            RecordingInfo(
                recordingId = rec.id,
                species = rec.en,
                scientificName = "${rec.gen} ${rec.sp}",
                sonogramUrl = sonoUrl,
                audioUrl = rec.file,
                quality = rec.q,
                country = rec.cnt,
                type = rec.type
            )
        }
    }

    override suspend fun downloadAudio(audioUrl: String, recordingId: String): File =
        withContext(Dispatchers.IO) {
            val url = if (audioUrl.startsWith("//")) "https:$audioUrl" else audioUrl
            val cacheFile = File(tempDir, "xc_${recordingId}.mp3")

            if (!cacheFile.exists()) {
                val connection = URI(url).toURL().openConnection()
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.getInputStream().use { input ->
                    cacheFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var totalRead = 0
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1 && totalRead < 2_000_000) {
                            output.write(buffer, 0, read)
                            totalRead += read
                        }
                    }
                }
            }

            cacheFile
        }

    companion object {
        private fun fixUrl(url: String): String = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            else -> "https://$url"
        }
    }
}
