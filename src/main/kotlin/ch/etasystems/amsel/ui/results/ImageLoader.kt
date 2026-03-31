package ch.etasystems.amsel.ui.results

import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

/**
 * Gemeinsame Image-Loading-Utility fuer Sonogramm-Bilder.
 * Konsolidiert die Fallback-Ketten aus SonogramGallery und ReferenceSonogramPanel.
 */
object ImageLoader {

    private val logger = LoggerFactory.getLogger(ImageLoader::class.java)

    /**
     * Laedt ein BufferedImage von einem lokalen Pfad oder einer URL.
     * Fallback-Kette: file:/// -> absoluter Pfad -> HTTP URL.
     * Lokale Dateien werden robust geladen (ImageIO -> Toolkit-Fallback).
     */
    fun loadBufferedImage(url: String): BufferedImage? {
        logger.debug("loadBufferedImage: url='{}'", url)
        return try {
            when {
                url.startsWith("file:///") -> {
                    val path = url.removePrefix("file:///")
                    val file = File(path)
                    if (file.exists()) readImageRobust(file) else {
                        logger.debug("Datei nicht gefunden: {}", path)
                        null
                    }
                }
                url.startsWith("/") || url.contains(":\\") -> {
                    val file = File(url)
                    if (file.exists()) readImageRobust(file) else {
                        logger.debug("Datei nicht gefunden: {}", url)
                        null
                    }
                }
                else -> {
                    logger.debug("Lade remote: {}", url)
                    val conn = URI(url).toURL().openConnection()
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    conn.setRequestProperty("User-Agent", "AMSEL/0.1")
                    ImageIO.read(conn.getInputStream())
                }
            }
        } catch (e: Exception) {
            logger.warn("loadBufferedImage fehlgeschlagen: {} url={}", e.message, url)
            null
        }
    }

    /**
     * Prueft ob ein Bild komplett schwarz ist (3x3 Testpunkte, Schwellwert < 5 pro Kanal).
     * Verwendet ein 3x3-Raster (9 Punkte) fuer zuverlaessige Erkennung.
     */
    fun isImageBlack(img: BufferedImage): Boolean {
        for (testY in listOf(img.height / 4, img.height / 2, img.height * 3 / 4)) {
            for (testX in listOf(img.width / 4, img.width / 2, img.width * 3 / 4)) {
                if (testX < 0 || testX >= img.width || testY < 0 || testY >= img.height) continue
                val px = img.getRGB(testX, testY)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                if (r > 5 || g > 5 || b > 5) return false
            }
        }
        logger.debug("Bild ist komplett schwarz/korrupt ({}x{})", img.width, img.height)
        return true
    }

    /**
     * Konvertiert ein BufferedImage auf TYPE_INT_ARGB (noetig fuer toComposeImageBitmap).
     */
    fun toArgb(img: BufferedImage): BufferedImage {
        if (img.type == BufferedImage.TYPE_INT_ARGB) return img
        val argb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
        val g = argb.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return argb
    }

    /**
     * Skaliert grosse Bilder auf maxWidth herunter (bilinear).
     */
    fun scaleIfNeeded(img: BufferedImage, maxWidth: Int): BufferedImage {
        if (img.width <= maxWidth) return img
        val scale = maxWidth.toDouble() / img.width
        val newW = maxWidth
        val newH = (img.height * scale).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img, 0, 0, newW, newH, null)
        g.dispose()
        return scaled
    }

    /**
     * Prueft ob eine Datei ein gueltiges PNG ist (Magic Bytes + Mindestgroesse).
     * Gibt false zurueck fuer abgebrochene Downloads, 0-Byte-Dateien oder beschaedigte Dateien.
     */
    fun isValidImageFile(file: File): Boolean {
        if (!file.exists() || file.length() < 100) return false
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(4)
                val read = stream.read(header)
                if (read < 4) return false
                // PNG Magic Bytes: 89 50 4E 47
                val isPng = header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
                // JPEG Magic Bytes: FF D8 FF
                val isJpeg = header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() &&
                    header[2] == 0xFF.toByte()
                isPng || isJpeg
            }
        } catch (e: Exception) {
            logger.debug("isValidImageFile fehlgeschlagen: {}", file.absolutePath)
            false
        }
    }

    /**
     * Robustes Bild-Laden: Erst ImageIO.read(InputStream), dann Toolkit-Fallback.
     * Behandelt PNG-Dateien die als .jpg benannt sind (Palette-Mode P).
     */
    private fun readImageRobust(file: File): BufferedImage? {
        logger.debug("readImageRobust: {}", file.absolutePath)
        // Vorab-Pruefung: Magic Bytes + Mindestgroesse
        if (!isValidImageFile(file)) {
            logger.debug("Ungueltige Bilddatei (korrupt/zu klein): {}", file.absolutePath)
            return null
        }
        val raw = try {
            file.inputStream().buffered().use { ImageIO.read(it) }
        } catch (_: Exception) {
            logger.debug("ImageIO fehlgeschlagen, versuche Toolkit-Fallback: {}", file.absolutePath)
            try {
                val tkImg = java.awt.Toolkit.getDefaultToolkit().createImage(file.absolutePath)
                val tracker = java.awt.MediaTracker(java.awt.Canvas())
                tracker.addImage(tkImg, 0)
                tracker.waitForAll(5000)
                val w = tkImg.getWidth(null)
                val h = tkImg.getHeight(null)
                if (w > 0 && h > 0) {
                    val bi = BufferedImage(w.coerceAtMost(2000), h.coerceAtMost(500), BufferedImage.TYPE_INT_RGB)
                    val g = bi.createGraphics()
                    g.drawImage(tkImg, 0, 0, bi.width, bi.height, null)
                    g.dispose()
                    bi
                } else null
            } catch (_: Exception) {
                logger.warn("Toolkit-Fallback fehlgeschlagen: {}", file.absolutePath)
                null
            }
        }
        if (raw != null) {
            logger.debug("Bild geladen: {}x{} type={}", raw.width, raw.height, raw.type)
        }
        return raw
    }
}
