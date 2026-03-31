package ch.etasystems.amsel.ui.sonogram

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import ch.etasystems.amsel.core.spectrogram.Colormap
import ch.etasystems.amsel.core.spectrogram.MelFilterbank
import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ========================================================================
// Schnelle Bitmap-Erzeugung via Skia
// ========================================================================

internal fun createSpectrogramBitmap(
    data: SpectrogramData,
    dbRange: Float = 0f,
    gamma: Float = 1f,
    volumeGainsLog10: FloatArray? = null,
    referenceMaxDb: Float = 0f
): ImageBitmap {
    val width = data.nFrames
    val height = data.nMels
    if (width == 0 || height == 0) return ImageBitmap(1, 1)

    val pixels = Colormap.toPixels(data, dbRange, gamma, volumeGainsLog10, referenceMaxDb)

    val skiaBitmap = Bitmap()
    val imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
    skiaBitmap.allocPixels(imageInfo)

    val byteBuffer = ByteBuffer.allocate(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (pixel in pixels) {
        val a = (pixel shr 24) and 0xFF
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        byteBuffer.put(b.toByte())
        byteBuffer.put(g.toByte())
        byteBuffer.put(r.toByte())
        byteBuffer.put(a.toByte())
    }
    byteBuffer.rewind()
    skiaBitmap.installPixels(byteBuffer.array())

    return skiaBitmap.asComposeImageBitmap()
}

// ========================================================================
// Shared Helper-Funktionen
// ========================================================================

/**
 * Rechnet Hz in normalisierte Y-Position (0.0=fMin, 1.0=fMax) auf Mel-Skala um.
 * Das Bitmap ist in Mel-Bins gleichmaessig verteilt, daher muss die Achse
 * die gleiche Mel-Transformation verwenden.
 */
internal fun hzToNormalizedMel(hz: Float, fMin: Float, fMax: Float): Float {
    val melMin = MelFilterbank.hzToMel(fMin)
    val melMax = MelFilterbank.hzToMel(fMax)
    val mel = MelFilterbank.hzToMel(hz)
    return ((mel - melMin) / (melMax - melMin)).coerceIn(0f, 1f)
}

/**
 * Hilfsfunktion fuer lineare Interpolation eines Farbkanals.
 * Wird bei der Linear-Frequenzachse verwendet um zwischen Mel-Bin-Zeilen zu interpolieren.
 */
internal fun lerpChannel(c0: Int, c1: Int, frac: Float, shift: Int): Int {
    val v0 = (c0 shr shift) and 0xFF
    val v1 = (c1 shr shift) and 0xFF
    return (v0 + (v1 - v0) * frac).toInt().coerceIn(0, 255)
}
