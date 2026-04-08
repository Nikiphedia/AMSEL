package ch.etasystems.amsel.core.export

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Audio-Export in verschiedene Formate.
 * WAV: nativ (16-Bit PCM Mono).
 * FLAC, M4A, MP3: via ffmpeg (muss im PATH sein).
 */

enum class AudioExportFormat { WAV, FLAC, M4A, MP3 }

object AudioExporter {
    private val logger = LoggerFactory.getLogger(AudioExporter::class.java)

    /** Prueft ob ffmpeg im PATH verfuegbar ist. */
    val ffmpegAvailable: Boolean by lazy {
        try {
            val p = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) { false }
    }

    /**
     * Exportiert Float-Samples in das gewaehlte Format.
     * @throws IllegalStateException wenn ffmpeg fuer FLAC/M4A/MP3 nicht verfuegbar
     */
    fun export(file: File, samples: FloatArray, sampleRate: Int, format: AudioExportFormat) {
        when (format) {
            AudioExportFormat.WAV  -> writeWav(file, samples, sampleRate)
            AudioExportFormat.FLAC -> convertViaFfmpeg(file, samples, sampleRate, "flac", "-c:a", "flac")
            AudioExportFormat.M4A  -> convertViaFfmpeg(file, samples, sampleRate, "m4a",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
            AudioExportFormat.MP3  -> convertViaFfmpeg(file, samples, sampleRate, "mp3",
                "-b:a", "192k", "-q:a", "2")
        }
        logger.debug("Audio exportiert: {} ({})", file.name, format)
    }

    // === WAV Writer (16-Bit PCM Mono, Little-Endian) ===

    fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val numSamples = samples.size
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val dataSize = numSamples.toLong() * bytesPerSample
        if (dataSize > Int.MAX_VALUE) {
            throw IllegalArgumentException("Audio zu lang fuer WAV-Format (>${Int.MAX_VALUE / bytesPerSample} Samples). Bitte kuerzeren Ausschnitt waehlen.")
        }
        val dataSizeInt = dataSize.toInt()
        val fileSize = 36 + dataSizeInt

        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(file))).use { out ->
            // RIFF Header
            out.writeBytes("RIFF")
            out.writeIntLE(fileSize)
            out.writeBytes("WAVE")
            // fmt Chunk
            out.writeBytes("fmt ")
            out.writeIntLE(16)
            out.writeShortLE(1)         // PCM
            out.writeShortLE(1)         // Mono
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * bytesPerSample)
            out.writeShortLE(bytesPerSample)
            out.writeShortLE(bitsPerSample)
            // data Chunk
            out.writeBytes("data")
            out.writeIntLE(dataSizeInt)
            for (sample in samples) {
                val clamped = sample.coerceIn(-1f, 1f)
                val pcm = (clamped * 32767f).toInt().toShort()
                out.writeShortLE(pcm.toInt())
            }
        }
    }

    // === ffmpeg-Konvertierung ===

    private fun convertViaFfmpeg(
        outputFile: File, samples: FloatArray, sampleRate: Int,
        formatName: String, vararg codecArgs: String
    ) {
        check(ffmpegAvailable) { "$formatName-Export erfordert ffmpeg im PATH" }

        val tempWav = File.createTempFile("amsel_export_", ".wav")
        try {
            writeWav(tempWav, samples, sampleRate)
            val cmd = listOf("ffmpeg", "-y", "-i", tempWav.absolutePath) + codecArgs + listOf(outputFile.absolutePath)
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            proc.inputStream.readBytes()
            val finished = proc.waitFor(60, TimeUnit.SECONDS)
            val exitCode = if (finished) proc.exitValue() else -1
            if (!finished) {
                proc.destroyForcibly()
                throw RuntimeException("ffmpeg $formatName Timeout nach 60s")
            }
            if (exitCode != 0) throw RuntimeException("ffmpeg $formatName Fehler (Exit $exitCode)")
        } finally {
            tempWav.delete()
        }
    }

    // === Little-Endian Hilfsfunktionen ===

    private fun java.io.DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun java.io.DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
