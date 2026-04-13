package ch.etasystems.amsel.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Laedt ONNX-Modelle und Label-Dateien aus dem Internet herunter.
 * Fortschritt wird ueber Callback gemeldet.
 */
object ModelDownloader {
    private val logger = LoggerFactory.getLogger(ModelDownloader::class.java)

    data class DownloadProgress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val isComplete: Boolean = false,
        val error: String? = null
    ) {
        val percent: Int get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else -1
        val displayText: String get() = when {
            totalBytes > 0 -> "$percent%"
            bytesDownloaded > 0 -> "${"%.1f".format(bytesDownloaded / (1024.0 * 1024.0))} MB"
            else -> "..."
        }
    }

    /**
     * Laedt eine Datei herunter mit Fortschritts-Callback.
     * @param url Download-URL
     * @param targetFile Zieldatei
     * @param onProgress Wird regelmaessig aufgerufen (~jede 100KB)
     * @return true bei Erfolg, false bei Fehler
     */
    suspend fun download(
        url: String,
        targetFile: File,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            logger.info("Download gestartet: {} → {}", url, targetFile.name)
            targetFile.parentFile?.mkdirs()

            // Manuelles Redirect-Handling (Cross-Domain, HTTP→HTTPS)
            var currentUrl = url
            var connection: HttpURLConnection? = null
            var redirectCount = 0
            val maxRedirects = 5

            while (redirectCount < maxRedirects) {
                connection = URI(currentUrl).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false  // Manuell handlen
                connection.setRequestProperty("User-Agent", "AMSEL-Desktop/1.0")

                val responseCode = connection.responseCode
                if (responseCode in listOf(301, 302, 303, 307, 308)) {
                    val redirectUrl = connection.getHeaderField("Location")
                    if (redirectUrl.isNullOrBlank()) {
                        val msg = "Redirect ohne Location-Header bei $currentUrl"
                        logger.error(msg)
                        onProgress(DownloadProgress(0, 0, isComplete = true, error = msg))
                        return@withContext false
                    }
                    logger.debug("Redirect {} → {}", responseCode, redirectUrl)
                    currentUrl = redirectUrl
                    connection.disconnect()
                    redirectCount++
                    continue
                }

                if (responseCode != 200) {
                    val msg = "HTTP $responseCode fuer $currentUrl"
                    logger.error(msg)
                    onProgress(DownloadProgress(0, 0, isComplete = true, error = msg))
                    return@withContext false
                }
                break
            }

            if (connection == null || redirectCount >= maxRedirects) {
                val msg = "Zu viele Redirects ($maxRedirects) fuer $url"
                logger.error(msg)
                onProgress(DownloadProgress(0, 0, isComplete = true, error = msg))
                return@withContext false
            }

            val totalBytes = connection.contentLengthLong
            var downloaded = 0L
            val buffer = ByteArray(8192)

            // Temp-Datei um partielle Downloads zu vermeiden
            val tempFile = File(targetFile.absolutePath + ".download")

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        // Progress ca. alle 100KB melden
                        if (downloaded % 102400 < 8192) {
                            onProgress(DownloadProgress(downloaded, totalBytes))
                        }
                    }
                }
            }

            // Temp → Ziel umbenennen
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)

            onProgress(DownloadProgress(downloaded, totalBytes, isComplete = true))
            logger.info("Download fertig: {} ({} MB)", targetFile.name, downloaded / (1024 * 1024))
            true
        } catch (e: Exception) {
            val msg = "Download-Fehler: ${e.message}"
            logger.error(msg, e)
            onProgress(DownloadProgress(0, 0, isComplete = true, error = msg))
            false
        }
    }
}
