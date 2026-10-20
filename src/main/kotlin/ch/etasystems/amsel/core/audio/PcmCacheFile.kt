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
        private const val CHUNK_SAMPLES = 65536

        private fun newCacheFile(sourceName: String): File {
            val cacheDir = File(System.getProperty("user.home"), "Documents/AMSEL/cache")
            cacheDir.mkdirs()
            return File(cacheDir, "pcm_${sourceName}_${System.currentTimeMillis()}.raw")
        }

        private fun writeHeader(raf: RandomAccessFile, sampleRate: Int, totalSamples: Long) {
            raf.seek(0)
            raf.write(MAGIC.toByteArray(Charsets.US_ASCII))  // 4 Bytes
            val headerBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            headerBuf.putInt(sampleRate)
            headerBuf.putLong(totalSamples)
            raf.write(headerBuf.array())
        }

        /** Schreibt Samples als Float32-LE per Bulk-Put an die aktuelle Dateiposition. */
        private fun writeSamples(raf: RandomAccessFile, samples: FloatArray, from: Int, count: Int, buf: ByteBuffer) {
            var written = 0
            val chunk = buf.capacity() / 4
            while (written < count) {
                val n = minOf(chunk, count - written)
                buf.clear()
                buf.asFloatBuffer().put(samples, from + written, n)
                buf.limit(n * 4)
                raf.channel.write(buf)
                written += n
            }
        }

        /**
         * Erstellt eine PCM-Cache-Datei aus einem bereits dekodierten Segment.
         * Bevorzugt verwenden, wenn das Audio ohnehin schon im Speicher liegt
         * (spart das erneute Dekodieren der Quelldatei).
         */
        fun createFromSegment(
            segment: AudioSegment,
            sourceName: String,
            onProgress: ((Float) -> Unit)? = null
        ): PcmCacheFile {
            val cacheFile = newCacheFile(sourceName)
            val samples = segment.samples
            val buf = ByteBuffer.allocate(CHUNK_SAMPLES * 4).order(ByteOrder.LITTLE_ENDIAN)
            RandomAccessFile(cacheFile, "rw").use { raf ->
                writeHeader(raf, segment.sampleRate, samples.size.toLong())
                var written = 0
                while (written < samples.size) {
                    val n = minOf(CHUNK_SAMPLES * 16, samples.size - written)
                    writeSamples(raf, samples, written, n, buf)
                    written += n
                    onProgress?.invoke(written.toFloat() / samples.size)
                }
            }
            onProgress?.invoke(1.0f)
            return PcmCacheFile(cacheFile, segment.sampleRate, samples.size.toLong())
        }

        /**
         * Erstellt eine PCM-Cache-Datei aus einer Audio-Datei.
         * Dekodiert streaming und schreibt jeden Chunk direkt in die Datei —
         * das gesamte Audio liegt nie komplett im Speicher.
         *
         * @param audioFile Quell-Audiodatei (WAV, MP3, FLAC, M4A etc.)
         * @param onProgress Fortschritts-Callback (0.0 .. 1.0)
         * @return PcmCacheFile mit Random-Access-Zugriff
         */
        fun createFromAudioFile(
            audioFile: File,
            onProgress: ((Float) -> Unit)? = null
        ): PcmCacheFile {
            val cacheFile = newCacheFile(audioFile.nameWithoutExtension)
            // Erwartete Laenge fuer Fortschrittsanzeige (bei MP3 oft unbekannt → -1)
            val expectedSamples = runCatching {
                javax.sound.sampled.AudioSystem.getAudioFileFormat(audioFile).frameLength.toLong()
            }.getOrDefault(-1L)

            onProgress?.invoke(0.0f)
            val buf = ByteBuffer.allocate(CHUNK_SAMPLES * 4).order(ByteOrder.LITTLE_ENDIAN)
            val result = RandomAccessFile(cacheFile, "rw").use { raf ->
                // Header mit Platzhalter, Laenge wird am Ende nachgetragen
                writeHeader(raf, 0, 0L)
                val streaming = AudioDecoder.decodeStreaming(audioFile) { chunk, offset ->
                    writeSamples(raf, chunk, 0, chunk.size, buf)
                    if (expectedSamples > 0) {
                        onProgress?.invoke(((offset + chunk.size).toFloat() / expectedSamples).coerceAtMost(0.99f))
                    }
                }
                writeHeader(raf, streaming.sampleRate, streaming.totalSamples)
                streaming
            }

            onProgress?.invoke(1.0f)

            return PcmCacheFile(cacheFile, result.sampleRate, result.totalSamples)
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
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
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
