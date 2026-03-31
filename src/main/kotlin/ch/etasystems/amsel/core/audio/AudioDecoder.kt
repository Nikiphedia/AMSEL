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
                for (i in 0 until numSamples) {
                    val lo = buffer[i * 2].toInt() and 0xFF
                    val hi = buffer[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo) / 32768f
                }

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

        val skipBytes = startSample * 2
        var remaining = skipBytes
        val skipBuffer = ByteArray(minOf(remaining, 65536L).toInt())
        try {
            while (remaining > 0) {
                val toRead = minOf(remaining, skipBuffer.size.toLong()).toInt()
                val read = decodedStream.read(skipBuffer, 0, toRead)
                if (read <= 0) break
                remaining -= read
            }

            val bytes = ByteArray(numSamples * 2)
            val bytesRead = readFully(decodedStream, bytes)
            val actualSamples = bytesRead / 2

            val samples = FloatArray(actualSamples)
            for (i in 0 until actualSamples) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                samples[i] = ((hi shl 8) or lo) / 32768f
            }

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

        // Chunk-weise lesen statt readBytes() — robuster bei unbekannter Länge
        val baos = ByteArrayOutputStream()
        val readBuffer = ByteArray(65536)
        try {
            while (true) {
                val read = decodedStream.read(readBuffer)
                if (read <= 0) break
                baos.write(readBuffer, 0, read)
            }
        } finally {
            decodedStream.close()
            originalStream.close()
        }

        val bytes = baos.toByteArray()
        val numSamples = bytes.size / 2
        val samples = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo) / 32768f
        }

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
     * Manuelles Stereo→Mono Downmix: Mittelwert beider Kanäle.
     */
    private fun manualStereoToMono(
        stereoStream: AudioInputStream,
        stereoFormat: AudioFormat
    ): AudioInputStream {
        val channels = stereoFormat.channels
        val bytesPerFrame = channels * 2  // 16-Bit pro Kanal
        val frameBuffer = ByteArray(bytesPerFrame)
        val monoBytes = ByteArrayOutputStream()

        while (true) {
            val read = readFully(stereoStream, frameBuffer)
            if (read < bytesPerFrame) break

            // Mittelwert aller Kanäle
            var sum = 0
            for (ch in 0 until channels) {
                val lo = frameBuffer[ch * 2].toInt() and 0xFF
                val hi = frameBuffer[ch * 2 + 1].toInt()
                sum += (hi shl 8) or lo
            }
            val mono = (sum / channels).toShort()
            monoBytes.write(mono.toInt() and 0xFF)
            monoBytes.write((mono.toInt() shr 8) and 0xFF)
        }

        val monoData = monoBytes.toByteArray()
        val monoFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            stereoFormat.sampleRate,
            16, 1, 2,
            stereoFormat.sampleRate,
            false
        )
        return AudioInputStream(
            java.io.ByteArrayInputStream(monoData),
            monoFormat,
            (monoData.size / 2).toLong()
        )
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
