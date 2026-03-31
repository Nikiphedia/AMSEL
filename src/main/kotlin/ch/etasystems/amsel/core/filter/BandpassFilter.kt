package ch.etasystems.amsel.core.filter

import ch.etasystems.amsel.core.spectrogram.MelFilterbank
import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import kotlin.math.log2
import kotlin.math.pow

/**
 * Bandpass-Filter im Spektrogramm-Bereich mit 24 dB/Oktave Flanke.
 *
 * Statt Brick-Wall (ON/OFF) wird ein gradueller Rolloff angewendet:
 * - Innerhalb des Passband: unveraendert (0 dB Daempfung)
 * - Ausserhalb: -24 dB pro Oktave Abstand zur Grenzfrequenz
 * - Daempfung wird auf minValue begrenzt
 *
 * 24 dB/Oktave entspricht einem Butterworth 4. Ordnung.
 */
object BandpassFilter {

    private const val ROLLOFF_DB_PER_OCTAVE = 24f  // Butterworth 4. Ordnung

    fun apply(
        data: SpectrogramData,
        lowHz: Float = 1000f,
        highHz: Float = 16000f
    ): SpectrogramData {
        if (data.nFrames == 0) return data

        val melMin = MelFilterbank.hzToMel(data.fMin)
        val melMax = MelFilterbank.hzToMel(data.fMax)
        val minValue = data.minValue

        // Vorberechnung: Daempfung pro Mel-Bin (in log10-Einheiten)
        val attenuation = FloatArray(data.nMels)
        for (mel in 0 until data.nMels) {
            val melCenter = melMin + (mel + 0.5f) * (melMax - melMin) / data.nMels
            val hzCenter = MelFilterbank.melToHz(melCenter)

            attenuation[mel] = when {
                hzCenter < lowHz && lowHz > 0f -> {
                    // Unterhalb Passband: Daempfung steigt mit Abstand
                    val octavesBelow = log2(lowHz / hzCenter.coerceAtLeast(1f))
                    val attenuationDb = octavesBelow * ROLLOFF_DB_PER_OCTAVE
                    attenuationDb / 10f  // dB → log10-Einheiten
                }
                hzCenter > highHz && highHz > 0f -> {
                    // Oberhalb Passband: Daempfung steigt mit Abstand
                    val octavesAbove = log2(hzCenter / highHz)
                    val attenuationDb = octavesAbove * ROLLOFF_DB_PER_OCTAVE
                    attenuationDb / 10f  // dB → log10-Einheiten
                }
                else -> 0f  // Im Passband: keine Daempfung
            }
        }

        val filteredMatrix = FloatArray(data.nMels * data.nFrames)

        for (mel in 0 until data.nMels) {
            val att = attenuation[mel]
            val baseIdx = mel * data.nFrames
            if (att <= 0f) {
                // Passband: unveraendert kopieren
                System.arraycopy(data.matrix, baseIdx, filteredMatrix, baseIdx, data.nFrames)
            } else {
                // Rolloff anwenden
                for (frame in 0 until data.nFrames) {
                    filteredMatrix[baseIdx + frame] = (data.matrix[baseIdx + frame] - att).coerceAtLeast(minValue)
                }
            }
        }

        return data.copy(matrix = filteredMatrix)
    }
}
