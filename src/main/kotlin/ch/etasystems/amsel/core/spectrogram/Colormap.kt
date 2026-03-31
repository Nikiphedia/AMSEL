package ch.etasystems.amsel.core.spectrogram

import kotlin.math.pow

/**
 * Farbpaletten fuer Spektrogramm-Darstellung.
 * Unterstuetzt: Magma, Viridis, Inferno, Graustufen.
 */
object Colormap {

    enum class Palette { MAGMA, VIRIDIS, INFERNO, GRAYSCALE, BW_PRINT }

    private var activePalette: Palette = Palette.MAGMA

    private val magmaLut by lazy { buildLut(MAGMA_ANCHORS) }
    private val viridisLut by lazy { buildLut(VIRIDIS_ANCHORS) }
    private val infernoLut by lazy { buildLut(INFERNO_ANCHORS) }
    private val grayscaleLut by lazy {
        IntArray(256) { i -> (0xFF shl 24) or (i shl 16) or (i shl 8) or i }
    }
    // S/W Druck: leise=weiss, laut=schwarz (invertiert, Glutz-Stil)
    private val bwPrintLut by lazy {
        IntArray(256) { i -> val g = 255 - i; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
    }

    fun setActivePalette(palette: Palette) {
        activePalette = palette
    }

    fun getActivePalette(): Palette = activePalette

    private fun activeLut(): IntArray = when (activePalette) {
        Palette.MAGMA -> magmaLut
        Palette.VIRIDIS -> viridisLut
        Palette.INFERNO -> infernoLut
        Palette.GRAYSCALE -> grayscaleLut
        Palette.BW_PRINT -> bwPrintLut
    }

    /** ARGB-Int fuer die aktive Palette (0..1 → ARGB) */
    fun mapValue(value: Float): Int {
        val idx = (value.coerceIn(0f, 1f) * 255).toInt()
        return activeLut()[idx]
    }

    /** RGB-Triple fuer die aktive Palette (0..1 → r, g, b) */
    fun mapValueRgb(value: Float): Triple<Int, Int, Int> {
        val argb = activeLut()[(value.coerceIn(0f, 1f) * 255).toInt()]
        return Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }

    fun toPixels(data: SpectrogramData): IntArray =
        toPixels(data, dbRange = 0f, gamma = 1f)

    /**
     * Konvertiert Spektrogramm-Daten zu ARGB-Pixelarray.
     * @param dbRange Nur die oberen N dB anzeigen (0 = voller Bereich)
     * @param gamma Gamma-Korrektur (< 1 = mehr Detail in leisen Bereichen)
     *
     * Wenn data.ref0dBFS > 0, wird dieser als feste Obergrenze verwendet (0 dBFS Referenz).
     * Damit erscheinen leise Aufnahmen optisch dunkler als laute.
     */
    fun toPixels(data: SpectrogramData, dbRange: Float = 0f, gamma: Float = 1f, volumeGainsLog10: FloatArray? = null, referenceMaxDb: Float = 0f): IntArray {
        val lut = activeLut()
        val pixels = IntArray(data.nMels * data.nFrames)
        // Eingefrorene Referenz hat Prioritaet, dann ref0dBFS, dann dynamisch
        val maxVal = if (referenceMaxDb != 0f) referenceMaxDb
                     else if (data.ref0dBFS > 0f) data.ref0dBFS
                     else data.maxValue
        val minVal = data.minValue
        val fullRange = maxVal - minVal

        // dB-basierte Normalisierung: nur obere dbRange dB anzeigen
        val useDbRange = dbRange > 0f
        val floor = if (useDbRange) maxVal - dbRange else minVal
        val displayRange = if (useDbRange) dbRange else fullRange

        // Gamma: 1.0 = linear, < 1.0 = Detail in leisen Bereichen, > 1.0 = Kontrast
        val applyGamma = gamma != 1f && gamma > 0f

        if (displayRange <= 0f) return pixels

        for (mel in 0 until data.nMels) {
            for (frame in 0 until data.nFrames) {
                val row = data.nMels - 1 - mel
                var value = data.valueAt(mel, frame)
                // Volume Envelope: Gain pro Frame addieren (dB/10 → log10)
                if (volumeGainsLog10 != null && frame < volumeGainsLog10.size) {
                    value += volumeGainsLog10[frame]
                }

                var normalized = ((value - floor) / displayRange).coerceIn(0f, 1f)

                if (applyGamma) {
                    normalized = normalized.pow(gamma)
                }

                val idx = (normalized * 255).toInt().coerceIn(0, 255)
                pixels[row * data.nFrames + frame] = lut[idx]
            }
        }
        return pixels
    }

    // ═══════════ Stuetzpunkte ═══════════

    private val MAGMA_ANCHORS = arrayOf(
        floatArrayOf(0.00f, 0f, 0f, 4f),
        floatArrayOf(0.13f, 20f, 11f, 53f),
        floatArrayOf(0.25f, 63f, 15f, 98f),
        floatArrayOf(0.38f, 113f, 31f, 129f),
        floatArrayOf(0.50f, 159f, 53f, 122f),
        floatArrayOf(0.63f, 204f, 80f, 96f),
        floatArrayOf(0.75f, 237f, 121f, 62f),
        floatArrayOf(0.88f, 253f, 180f, 47f),
        floatArrayOf(1.00f, 252f, 253f, 191f)
    )

    private val VIRIDIS_ANCHORS = arrayOf(
        floatArrayOf(0.00f, 68f, 1f, 84f),
        floatArrayOf(0.13f, 72f, 35f, 116f),
        floatArrayOf(0.25f, 64f, 67f, 135f),
        floatArrayOf(0.38f, 52f, 94f, 141f),
        floatArrayOf(0.50f, 33f, 145f, 140f),
        floatArrayOf(0.63f, 53f, 183f, 121f),
        floatArrayOf(0.75f, 109f, 206f, 89f),
        floatArrayOf(0.88f, 180f, 222f, 44f),
        floatArrayOf(1.00f, 253f, 231f, 37f)
    )

    private val INFERNO_ANCHORS = arrayOf(
        floatArrayOf(0.00f, 0f, 0f, 4f),
        floatArrayOf(0.13f, 31f, 12f, 72f),
        floatArrayOf(0.25f, 85f, 15f, 109f),
        floatArrayOf(0.38f, 136f, 34f, 106f),
        floatArrayOf(0.50f, 187f, 55f, 84f),
        floatArrayOf(0.63f, 225f, 100f, 45f),
        floatArrayOf(0.75f, 246f, 150f, 20f),
        floatArrayOf(0.88f, 252f, 206f, 37f),
        floatArrayOf(1.00f, 252f, 255f, 164f)
    )

    private fun buildLut(anchors: Array<FloatArray>): IntArray {
        val lut = IntArray(256)
        for (i in 0 until 256) {
            val t = i / 255f
            var lo = 0
            for (j in 0 until anchors.size - 1) {
                if (t >= anchors[j][0]) lo = j
            }
            val hi = (lo + 1).coerceAtMost(anchors.size - 1)
            val range = anchors[hi][0] - anchors[lo][0]
            val frac = if (range > 0f) (t - anchors[lo][0]) / range else 0f

            val r = (anchors[lo][1] + frac * (anchors[hi][1] - anchors[lo][1])).toInt().coerceIn(0, 255)
            val g = (anchors[lo][2] + frac * (anchors[hi][2] - anchors[lo][2])).toInt().coerceIn(0, 255)
            val b = (anchors[lo][3] + frac * (anchors[hi][3] - anchors[lo][3])).toInt().coerceIn(0, 255)

            lut[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return lut
    }
}
