package ch.etasystems.amsel.core.audio

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * Dekodiert WAV/MP3/FLAC-Dateien zu rohen PCM-Floats.
 * Nutzt javax.sound.sampled + SPI-Provider (JLayer für MP3, JFLAC für FLAC).
 *
 * Robuste Format-Erkennung: Versucht zuerst File-basierte API,
 * dann BufferedInputStream mit großem Buffer als Fallback.
 */
object AudioDecoder {

    // 1 MB Buffer — groß genug für MP3-Header und WAV-Metadaten
    private const val BUFFER_SIZE = 1024 * 1024

    /** Komplett in den Speicher dekodieren. */
    fun decode(file: File): AudioSegment {
        val audioInputStream = openAudioStream(file)
        return decodeStream(audioInputStream)
    }

    /**
     * Streaming-Decode: Liest Audio in Chunks und ruft für jeden Chunk den Callback auf.
     * Verhindert OOM bei langen Dateien (2h+).
     */
    fun decodeStreaming(
        file: File,
        chunkSizeSamples: Int = 1_000_000,
        onChunk: (samples: FloatArray, offsetSamples: Long) -> Unit
    ): StreamingResult {
        val audioInputStream = openAudioStream(file)
        val originalFormat = audioInputStream.format

        val targetFormat = buildTargetFormat(originalFormat)
        val decodedStream = convertToTarget(audioInputStream, originalFormat, targetFormat)

        val bytesPerChunk = chunkSizeSamples * 2
        val buffer = ByteArray(bytesPerChunk)
        var totalSamples = 0L

        try {
            while (true) {
                val bytesRead = readFully(decodedStream, buffer)
                if (bytesRead <= 0) break

                val numSamples = bytesRead / 2
                val samples = FloatArray(numSamples)
                pcm16ToFloats(buffer, numSamples, samples, 0)

                onChunk(samples, totalSamples)
                totalSamples += numSamples
            }
        } finally {
            decodedStream.close()
            audioInputStream.close()
        }

        return StreamingResult(
            sampleRate = targetFormat.sampleRate.toInt(),
            totalSamples = totalSamples
        )
    }

    /**
     * Dekodiert nur einen Zeitbereich der Datei.
     */
    fun decodeRange(file: File, startSec: Float, endSec: Float): AudioSegment {
        val audioInputStream = openAudioStream(file)
        val originalFormat = audioInputStream.format
        val targetFormat = buildTargetFormat(originalFormat)
        val decodedStream = convertToTarget(audioInputStream, originalFormat, targetFormat)

        val sampleRate = targetFormat.sampleRate.toInt()
        val startSample = (startSec * sampleRate).toLong()
        val endSample = (endSec * sampleRate).toLong()
        val numSamples = (endSample - startSample).toInt()

        try {
            skipFully(decodedStream, startSample * 2)

            val bytes = ByteArray(numSamples * 2)
            val bytesRead = readFully(decodedStream, bytes)
            val actualSamples = bytesRead / 2

            val samples = FloatArray(actualSamples)
            pcm16ToFloats(bytes, actualSamples, samples, 0)

            return AudioSegment(samples = samples, sampleRate = sampleRate)
        } finally {
            decodedStream.close()
            audioInputStream.close()
        }
    }

    data class StreamingResult(val sampleRate: Int, val totalSamples: Long) {
        val durationSec: Float get() = totalSamples.toFloat() / sampleRate
    }

    // --- Robuste Stream-Öffnung ---

    /**
     * Öffnet eine Audio-Datei mit Fallback-Strategie:
     * 1. File-basierte API (best für WAV, nutzt RandomAccessFile)
     * 2. BufferedInputStream mit 1MB Buffer (Fallback für MP3/FLAC mit großen Headern)
     */
    private fun openAudioStream(file: File): AudioInputStream {
        // M4A/AAC: JCodec-basierter Decoder (javax.sound hat keinen SPI dafuer)
        val ext = file.extension.lowercase()
        if (ext == "m4a" || ext == "aac") {
            return decodeM4aToAudioStream(file)
        }

        // Versuch 1: File-basierte API (kein mark/reset nötig)
        try {
            return AudioSystem.getAudioInputStream(file)
        } catch (_: Exception) {
            // Fallthrough zu Versuch 2
        }

        // Versuch 2: BufferedInputStream mit großem Buffer
        try {
            val bis = BufferedInputStream(file.inputStream(), BUFFER_SIZE)
            return AudioSystem.getAudioInputStream(bis)
        } catch (_: Exception) {
            // Fallthrough zu Versuch 3
        }

        // Versuch 3: Datei komplett in den Speicher laden (letzter Ausweg)
        val bytes = file.readBytes()
        val bais = ByteArrayInputStream(bytes)
        return AudioSystem.getAudioInputStream(bais)
    }

    /**
     * Dekodiert M4A/AAC-Dateien via JCodec (JAAD AAC Decoder).
     * MP4-Container demuxen → AAC-Frames dekodieren → PCM AudioInputStream.
     */
    private fun decodeM4aToAudioStream(file: File): AudioInputStream {
        try {
            val channel = org.jcodec.common.io.NIOUtils.readableChannel(file)
            val demuxer = org.jcodec.containers.mp4.demuxer.MP4Demuxer.createMP4Demuxer(channel)
            val audioTrack = demuxer.audioTracks.firstOrNull()
                ?: throw IllegalArgumentException("Keine Audio-Spur in M4A-Datei gefunden")

            val trackMeta = audioTrack.meta
            val codecPrivate = trackMeta.codecPrivate

            // AAC-Decoder mit Codec-Private-Data (ASC) initialisieren
            val decoder = if (codecPrivate != null && codecPrivate.remaining() > 0) {
                org.jcodec.codecs.aac.AACDecoder(codecPrivate.duplicate())
            } else {
                // Fallback: ersten Frame als Config nutzen
                val firstPacket = audioTrack.nextFrame()
                    ?: throw IllegalArgumentException("Leere Audio-Spur")
                org.jcodec.codecs.aac.AACDecoder(firstPacket.data.duplicate())
            }

            val baos = ByteArrayOutputStream()
            var outSampleRate = 44100
            var outChannels = 1

            var packet = audioTrack.nextFrame()
            while (packet != null) {
                try {
                    val audioBuf = decoder.decodeFrame(packet.data, null)
                    if (audioBuf != null) {
                        val fmt = audioBuf.format
                        outSampleRate = fmt.sampleRate
                        outChannels = fmt.channels

                        val data = audioBuf.data
                        data.rewind()

                        if (fmt.sampleSizeInBits == 16) {
                            // 16-bit PCM: direkt kopieren
                            val arr = ByteArray(data.remaining())
                            data.get(arr)
                            baos.write(arr)
                        } else if (fmt.sampleSizeInBits == 24) {
                            // 24-bit → 16-bit: obere 2 Bytes nehmen
                            while (data.remaining() >= 3) {
                                data.get() // LSB (verwerfen)
                                val b1 = data.get()
                                val b2 = data.get() // MSB
                                if (fmt.isBigEndian) {
                                    baos.write(b1.toInt() and 0xFF)
                                    baos.write(b2.toInt() and 0xFF)
                                } else {
                                    baos.write(b1.toInt() and 0xFF)
                                    baos.write(b2.toInt() and 0xFF)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Einzelne kaputte Frames ueberspringen
                }
                packet = audioTrack.nextFrame()
            }
            channel.close()

            val pcmBytes = baos.toByteArray()
            val format = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                outSampleRate.toFloat(),
                16,
                outChannels,
                outChannels * 2,
                outSampleRate.toFloat(),
                false // Little-Endian
            )
            return AudioInputStream(
                ByteArrayInputStream(pcmBytes),
                format,
                (pcmBytes.size / (outChannels * 2)).toLong()
            )
        } catch (e: Exception) {
            throw IllegalArgumentException("M4A/AAC-Dekodierung fehlgeschlagen: ${e.message}", e)
        }
    }

    // --- Interne Hilfsfunktionen ---

    private fun decodeStream(originalStream: AudioInputStream): AudioSegment {
        val originalFormat = originalStream.format
        val targetFormat = buildTargetFormat(originalFormat)
        val decodedStream = convertToTarget(originalStream, originalFormat, targetFormat)

        // Chunk-weise lesen und direkt in Floats wandeln — kein ByteArrayOutputStream
        // (der wuerde durch Verdoppeln + toByteArray() ein Mehrfaches des Speichers belegen).
        // Laenge ist bei WAV/FLAC bekannt → exakt vorallozieren; sonst (MP3) wachsen lassen.
        val knownFrames = decodedStream.frameLength
        var samples = FloatArray(
            if (knownFrames in 1..Int.MAX_VALUE.toLong()) knownFrames.toInt() else 1 shl 20
        )
        var numSamples = 0
        val readBuffer = ByteArray(1 shl 16)
        var carry = 0  // ungerades Byte aus dem letzten read()
        try {
            while (true) {
                val read = decodedStream.read(readBuffer, carry, readBuffer.size - carry)
                if (read <= 0) break
                val available = carry + read
                val count = available / 2
                if (numSamples + count > samples.size) {
                    samples = samples.copyOf(maxOf(samples.size * 2, numSamples + count))
                }
                pcm16ToFloats(readBuffer, count, samples, numSamples)
                numSamples += count
                carry = available - count * 2
                if (carry == 1) readBuffer[0] = readBuffer[available - 1]
            }
        } finally {
            decodedStream.close()
            originalStream.close()
        }
        if (numSamples != samples.size) samples = samples.copyOf(numSamples)

        return AudioSegment(samples = samples, sampleRate = targetFormat.sampleRate.toInt())
    }

    private fun buildTargetFormat(originalFormat: AudioFormat): AudioFormat {
        return AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            originalFormat.sampleRate,
            16,
            1,    // Mono
            2,    // frameSize = 2 Bytes
            originalFormat.sampleRate,
            false // Little-Endian
        )
    }

    private fun convertToTarget(
        originalStream: AudioInputStream,
        originalFormat: AudioFormat,
        targetFormat: AudioFormat
    ): AudioInputStream {
        // Prüfe ob das Format schon passt (PCM 16-Bit Mono LE)
        if (originalFormat.encoding == AudioFormat.Encoding.PCM_SIGNED &&
            originalFormat.sampleSizeInBits == 16 &&
            originalFormat.channels == 1 &&
            !originalFormat.isBigEndian
        ) {
            return originalStream
        }

        // Direkte Konvertierung versuchen
        if (AudioSystem.isConversionSupported(targetFormat, originalFormat)) {
            return AudioSystem.getAudioInputStream(targetFormat, originalStream)
        }

        // Zweistufig: erst zu PCM (gleiche Kanäle), dann zu Mono
        val pcmFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            originalFormat.sampleRate,
            16,
            originalFormat.channels,
            originalFormat.channels * 2,
            originalFormat.sampleRate,
            false
        )

        if (AudioSystem.isConversionSupported(pcmFormat, originalFormat)) {
            val pcmStream = AudioSystem.getAudioInputStream(pcmFormat, originalStream)
            if (originalFormat.channels == 1) {
                return pcmStream
            }
            // Manuelle Stereo→Mono Konvertierung falls AudioSystem es nicht kann
            if (AudioSystem.isConversionSupported(targetFormat, pcmFormat)) {
                return AudioSystem.getAudioInputStream(targetFormat, pcmStream)
            }
            // Manuelles Downmix
            return manualStereoToMono(pcmStream, pcmFormat)
        }

        throw IllegalArgumentException(
            "Audio-Format nicht unterstützt: ${originalFormat.encoding} " +
            "${originalFormat.sampleSizeInBits}bit ${originalFormat.channels}ch " +
            "${originalFormat.sampleRate}Hz"
        )
    }

    /**
     * Manuelles Stereo→Mono Downmix: Mittelwert aller Kanaele.
     * Streaming — wandelt blockweise beim Lesen, statt alles vorab in den Speicher zu laden.
     */
    private fun manualStereoToMono(
        stereoStream: AudioInputStream,
        stereoFormat: AudioFormat
    ): AudioInputStream {
        val monoFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            stereoFormat.sampleRate,
            16, 1, 2,
            stereoFormat.sampleRate,
            false
        )
        return AudioInputStream(
            DownmixInputStream(stereoStream, stereoFormat.channels),
            monoFormat,
            stereoStream.frameLength
        )
    }

    /** Liest interleaved 16-Bit-LE-PCM mit n Kanaelen und liefert 16-Bit-LE-Mono. */
    private class DownmixInputStream(
        private val source: java.io.InputStream,
        private val channels: Int
    ) : java.io.InputStream() {
        private val bytesPerFrame = channels * 2
        private val inBuffer = ByteArray(bytesPerFrame * 8192)
        private val outBuffer = ByteArray(2 * 8192)
        private var outPos = 0
        private var outLen = 0

        private fun fill(): Boolean {
            val read = readFully(source, inBuffer)
            val frames = read / bytesPerFrame
            for (f in 0 until frames) {
                var sum = 0
                val base = f * bytesPerFrame
                for (ch in 0 until channels) {
                    val lo = inBuffer[base + ch * 2].toInt() and 0xFF
                    val hi = inBuffer[base + ch * 2 + 1].toInt()
                    sum += (hi shl 8) or lo
                }
                val mono = sum / channels
                outBuffer[f * 2] = (mono and 0xFF).toByte()
                outBuffer[f * 2 + 1] = ((mono shr 8) and 0xFF).toByte()
            }
            outPos = 0
            outLen = frames * 2
            return outLen > 0
        }

        override fun read(): Int {
            if (outPos >= outLen && !fill()) return -1
            return outBuffer[outPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (outPos >= outLen && !fill()) return -1
            val n = minOf(len, outLen - outPos)
            System.arraycopy(outBuffer, outPos, b, off, n)
            outPos += n
            return n
        }

        override fun close() = source.close()
    }

    /** 16-Bit-LE-PCM → Float [-1..1]. */
    private fun pcm16ToFloats(bytes: ByteArray, count: Int, dst: FloatArray, dstOffset: Int) {
        for (i in 0 until count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            dst[dstOffset + i] = ((hi shl 8) or lo) / 32768f
        }
    }

    /**
     * Ueberspringt n Bytes. skip() springt bei unkomprimiertem WAV direkt in der Datei;
     * liefert ein Stream 0 (z.B. Konverter-Streams), wird per read() verworfen.
     */
    private fun skipFully(stream: java.io.InputStream, n: Long) {
        var remaining = n
        var scratch: ByteArray? = null
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val buf = scratch ?: ByteArray(65536).also { scratch = it }
            val read = stream.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (read <= 0) break
            remaining -= read
        }
    }

    /** Liest so viele Bytes wie möglich in den Buffer. */
    private fun readFully(stream: java.io.InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = stream.read(buffer, totalRead, buffer.size - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        return totalRead
    }
}
