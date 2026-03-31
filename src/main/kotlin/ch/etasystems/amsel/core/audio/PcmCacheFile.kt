package ch.etasystems.amsel.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cache-Datei fuer dekodiertes PCM-Audio (Float32, Mono).
 *
 * Schreibt das gesamte dekodierte Audio einmalig als Raw-PCM in eine Temp-Datei.
 * Danach: Random Access auf beliebige Zeitbereiche in O(1) — kein Re-Decode noetig.
 *
 * Format: Header (16 Bytes) + Raw Float32 Samples
 * Header: [Magic "BSPC" (4)] [SampleRate Int32 (4)] [TotalSamples Int64 (8)]
 *
 * Speicherort: Dokumente/AMSEL/cache/pcm_XXXX.raw
 */
class PcmCacheFile private constructor(
    val cacheFile: File,
    val sampleRate: Int,
    val totalSamples: Long
) : AutoCloseable {

    private var raf: RandomAccessFile? = RandomAccessFile(cacheFile, "r")

    companion object {
        private const val MAGIC = "BSPC"
        private const val HEADER_SIZE = 16L  // 4 (magic) + 4 (sampleRate) + 8 (totalSamples)

        /**
         * Erstellt eine PCM-Cache-Datei aus einer Audio-Datei.
         * Dekodiert das gesamte Audio und schreibt es als Raw-Float32.
         *
         * @param audioFile Quell-Audiodatei (WAV, MP3, FLAC, M4A etc.)
         * @param onProgress Fortschritts-Callback (0.0 .. 1.0)
         * @return PcmCacheFile mit Random-Access-Zugriff
         */
        fun createFromAudioFile(
            audioFile: File,
            onProgress: ((Float) -> Unit)? = null
        ): PcmCacheFile {
            // Cache-Verzeichnis
            val cacheDir = File(System.getProperty("user.home"), "Documents/AMSEL/cache")
            cacheDir.mkdirs()
            val cacheFile = File(cacheDir, "pcm_${audioFile.nameWithoutExtension}_${System.currentTimeMillis()}.raw")

            onProgress?.invoke(0.1f)

            // Audio streaming dekodieren und direkt in Datei schreiben
            val segment = AudioDecoder.decode(audioFile)
            val samples = segment.samples
            val sampleRate = segment.sampleRate

            onProgress?.invoke(0.5f)

            // Header + Samples schreiben
            RandomAccessFile(cacheFile, "rw").use { raf ->
                // Header
                raf.write(MAGIC.toByteArray(Charsets.US_ASCII))  // 4 Bytes
                val headerBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                headerBuf.putInt(sampleRate)
                headerBuf.putLong(samples.size.toLong())
                raf.write(headerBuf.array())

                // Samples in Chunks schreiben (vermeidet riesigen ByteBuffer)
                val chunkSize = 65536
                val buf = ByteBuffer.allocate(chunkSize * 4).order(ByteOrder.LITTLE_ENDIAN)
                var written = 0
                while (written < samples.size) {
                    val count = minOf(chunkSize, samples.size - written)
                    buf.clear()
                    for (i in 0 until count) {
                        buf.putFloat(samples[written + i])
                    }
                    buf.flip()
                    val bytes = ByteArray(count * 4)
                    buf.get(bytes)
                    raf.write(bytes)
                    written += count

                    if (written % (chunkSize * 10) == 0) {
                        onProgress?.invoke(0.5f + 0.5f * written.toFloat() / samples.size)
                    }
                }
            }

            onProgress?.invoke(1.0f)

            return PcmCacheFile(cacheFile, sampleRate, samples.size.toLong())
        }

        /** Oeffnet eine bestehende PCM-Cache-Datei */
        fun open(cacheFile: File): PcmCacheFile {
            val raf = RandomAccessFile(cacheFile, "r")
            val magicBytes = ByteArray(4)
            raf.readFully(magicBytes)
            val magic = String(magicBytes, Charsets.US_ASCII)
            require(magic == MAGIC) { "Keine gueltige PCM-Cache-Datei: $magic" }

            val headerBuf = ByteArray(12)
            raf.readFully(headerBuf)
            val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
            val sampleRate = bb.getInt()
            val totalSamples = bb.getLong()
            raf.close()

            return PcmCacheFile(cacheFile, sampleRate, totalSamples)
        }
    }

    /** Dauer in Sekunden */
    val durationSec: Float get() = totalSamples.toFloat() / sampleRate

    /**
     * Liest einen Zeitbereich als AudioSegment.
     * Random Access: springt direkt zur richtigen Stelle in der Datei.
     */
    fun readRange(startSec: Float, endSec: Float): AudioSegment {
        val startSample = (startSec * sampleRate).toLong().coerceIn(0, totalSamples)
        val endSample = (endSec * sampleRate).toLong().coerceIn(startSample, totalSamples)
        val count = (endSample - startSample).toInt()

        if (count <= 0) return AudioSegment(FloatArray(0), sampleRate)

        val samples = FloatArray(count)
        val file = raf ?: RandomAccessFile(cacheFile, "r").also { raf = it }

        synchronized(file) {
            file.seek(HEADER_SIZE + startSample * 4)
            val bytes = ByteArray(count * 4)
            file.readFully(bytes)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                samples[i] = buf.getFloat()
            }
        }

        return AudioSegment(samples, sampleRate)
    }

    /**
     * Liest ALLE Samples (nur fuer kurze Dateien oder Export verwenden!)
     */
    fun readAll(): AudioSegment = readRange(0f, durationSec)

    override fun close() {
        raf?.close()
        raf = null
    }

    /** Loescht die Cache-Datei */
    fun delete() {
        close()
        cacheFile.delete()
    }
}
