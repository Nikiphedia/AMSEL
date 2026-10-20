package ch.etasystems.amsel.core.audio

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.ServiceLoader
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.spi.AudioFileReader

/**
 * Dekodiert WAV/MP3/FLAC/M4A-Dateien zu rohen PCM-Floats (Mono, [-1..1]).
 * Nutzt javax.sound.sampled + SPI-Provider (JLayer/mp3spi fuer MP3, JFLAC fuer FLAC).
 *
 * Ablauf: Datei oeffnen → in PCM mit Original-Kanalzahl und Original-Bittiefe wandeln →
 * selbst nach Float-Mono umrechnen. Dadurch bleibt die volle Praezision erhalten
 * (24/32 Bit, Float-WAV), und mp3spi muss nie selbst auf Mono heruntermischen
 * (das stuerzt bei Stereo-MP3s mit ArrayIndexOutOfBounds ab).
 */
object AudioDecoder {

    // 1 MB Buffer — groß genug für MP3-Header und WAV-Metadaten
    private const val BUFFER_SIZE = 1024 * 1024

    /** Container, die die JDK-eigenen Reader zuverlaessig lesen. */
    private val PCM_CONTAINER_EXTENSIONS = setOf("wav", "wave", "aif", "aiff", "aifc", "au")

    /** Komplett in den Speicher dekodieren. */
    fun decode(file: File): AudioSegment {
        return decodeStream(openPcmStream(file))
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
        val pcmStream = openPcmStream(file)
        val converter = MonoConverter(pcmStream.format)
        val buffer = ByteArray(chunkSizeSamples * converter.frameSize)
        var totalSamples = 0L

        pcmStream.use { stream ->
            while (true) {
                val bytesRead = readFully(stream, buffer)
                val frames = bytesRead / converter.frameSize
                if (frames <= 0) break

                val samples = FloatArray(frames)
                converter.convert(buffer, frames, samples, 0)

                onChunk(samples, totalSamples)
                totalSamples += frames
            }
        }

        return StreamingResult(
            sampleRate = pcmStream.format.sampleRate.toInt(),
            totalSamples = totalSamples
        )
    }

    /**
     * Dekodiert nur einen Zeitbereich der Datei.
     */
    fun decodeRange(file: File, startSec: Float, endSec: Float): AudioSegment {
        val pcmStream = openPcmStream(file)
        val converter = MonoConverter(pcmStream.format)

        val sampleRate = pcmStream.format.sampleRate.toInt()
        val startSample = (startSec * sampleRate).toLong()
        val endSample = (endSec * sampleRate).toLong()
        val numSamples = (endSample - startSample).toInt()

        pcmStream.use { stream ->
            skipFully(stream, startSample * converter.frameSize)

            val bytes = ByteArray(numSamples * converter.frameSize)
            val frames = readFully(stream, bytes) / converter.frameSize

            val samples = FloatArray(frames)
            converter.convert(bytes, frames, samples, 0)

            return AudioSegment(samples = samples, sampleRate = sampleRate)
        }
    }

    data class StreamingResult(val sampleRate: Int, val totalSamples: Long) {
        val durationSec: Float get() = totalSamples.toFloat() / sampleRate
    }

    // --- Robuste Stream-Öffnung ---

    /** Oeffnet die Datei und liefert einen PCM-Stream (Original-Kanalzahl und -Bittiefe). */
    private fun openPcmStream(file: File): AudioInputStream {
        val original = openAudioStream(file)
        try {
            return toPcm(original)
        } catch (e: Exception) {
            original.close()
            throw e
        }
    }

    /**
     * Öffnet eine Audio-Datei mit Fallback-Strategie:
     * 0. WAV/AIFF/AU: zuerst die JDK-eigenen Reader (siehe [openWithBuiltinReaders])
     * 1. File-basierte API (best für WAV, nutzt RandomAccessFile)
     * 2. BufferedInputStream mit 1MB Buffer (Fallback für MP3/FLAC mit großen Headern)
     */
    private fun openAudioStream(file: File): AudioInputStream {
        // M4A/AAC: javax.sound hat keinen SPI dafuer.
        // Bevorzugt ffmpeg (korrekte Dekodierung); JCodec nur als Fallback —
        // dessen AAC-Decoder weicht deutlich vom Original ab und verliert teils Frames.
        val ext = file.extension.lowercase()
        if (ext == "m4a" || ext == "aac") {
            if (ch.etasystems.amsel.core.export.AudioExporter.ffmpegAvailable) {
                try {
                    return decodeWithFfmpeg(file)
                } catch (_: Exception) {
                    // Fallthrough zu JCodec
                }
            }
            return decodeM4aToAudioStream(file)
        }

        if (ext in PCM_CONTAINER_EXTENSIONS) {
            openWithBuiltinReaders(file)?.let { return it }
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
     * JDK-eigene AudioFileReader (com.sun.media.sound.*), ohne Fremd-SPIs.
     * Hintergrund: mp3spi erkennt WAV-Extensible-Dateien (24 Bit, Float, >2 Kanaele)
     * faelschlich als MPEG und gewinnt bei AudioSystem.getAudioInputStream —
     * Ergebnis waren 0 Samples bei falscher Sample-Rate.
     */
    private val builtinReaders: List<AudioFileReader> by lazy {
        runCatching {
            ServiceLoader.load(AudioFileReader::class.java).stream()
                .filter { it.type().name.startsWith("com.sun.media.sound.") }
                .map { it.get() }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun openWithBuiltinReaders(file: File): AudioInputStream? {
        for (reader in builtinReaders) {
            try {
                return reader.getAudioInputStream(file)
            } catch (_: Exception) {
                // naechsten Reader versuchen
            }
        }
        return null
    }

    /**
     * Dekodiert M4A/AAC-Dateien via JCodec (JAAD AAC Decoder).
     * MP4-Container demuxen → AAC-Frames dekodieren → PCM AudioInputStream.
     * Streaming: Frames werden erst beim Lesen dekodiert, statt das gesamte PCM
     * vorab in einen ByteArrayOutputStream zu schreiben.
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

            val pcmStream = M4aPcmInputStream(audioTrack, decoder, channel)
            // Ersten Frame dekodieren, um Sample-Rate und Kanalzahl zu kennen
            pcmStream.prime()

            val format = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                pcmStream.sampleRate.toFloat(),
                16,
                pcmStream.channels,
                pcmStream.channels * 2,
                pcmStream.sampleRate.toFloat(),
                false // Little-Endian
            )
            return AudioInputStream(pcmStream, format, AudioSystem.NOT_SPECIFIED.toLong())
        } catch (e: Exception) {
            throw IllegalArgumentException("M4A/AAC-Dekodierung fehlgeschlagen: ${e.message}", e)
        }
    }

    /**
     * Dekodiert ueber einen ffmpeg-Prozess: ffmpeg schreibt WAV (32-Bit-Float,
     * Original-Kanalzahl) auf stdout, der Header wird hier gelesen und der Rest
     * als PCM_FLOAT-Stream weitergereicht. close() beendet den Prozess.
     */
    private fun decodeWithFfmpeg(file: File): AudioInputStream {
        val process = ProcessBuilder(
            "ffmpeg", "-v", "error", "-i", file.absolutePath,
            "-map", "0:a:0", "-f", "wav", "-acodec", "pcm_f32le", "-"
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

        val stream = object : java.io.FilterInputStream(BufferedInputStream(process.inputStream, 1 shl 16)) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    process.destroy()
                }
            }
        }
        try {
            val (channels, sampleRate) = readWavHeaderBisDaten(stream)
            val format = AudioFormat(
                AudioFormat.Encoding.PCM_FLOAT,
                sampleRate.toFloat(), 32, channels, 4 * channels, sampleRate.toFloat(), false
            )
            return AudioInputStream(stream, format, AudioSystem.NOT_SPECIFIED.toLong())
        } catch (e: Exception) {
            stream.close()
            throw e
        }
    }

    /**
     * Liest einen WAV-Header bis zum Beginn des data-Chunks (Stream steht danach auf
     * dem ersten Sample). Unbekannte Chunks (z.B. LIST) werden uebersprungen.
     * @return Kanalzahl und Sample-Rate aus dem fmt-Chunk
     */
    private fun readWavHeaderBisDaten(input: java.io.InputStream): Pair<Int, Int> {
        fun bytes(n: Int): ByteArray {
            val b = ByteArray(n)
            if (readFully(input, b) < n) throw java.io.EOFException("WAV-Header unvollstaendig")
            return b
        }
        fun le32(b: ByteArray, o: Int): Long =
            (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
                ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

        val riff = bytes(12)
        require(String(riff, 0, 4, Charsets.US_ASCII) == "RIFF" && String(riff, 8, 4, Charsets.US_ASCII) == "WAVE") {
            "Keine WAV-Ausgabe von ffmpeg"
        }
        var channels = 0
        var sampleRate = 0
        while (true) {
            val header = bytes(8)
            val id = String(header, 0, 4, Charsets.US_ASCII)
            val size = le32(header, 4)
            when (id) {
                "fmt " -> {
                    val fmt = bytes(size.toInt())
                    channels = (fmt[2].toInt() and 0xFF) or ((fmt[3].toInt() and 0xFF) shl 8)
                    sampleRate = le32(fmt, 4).toInt()
                    if (size % 2 == 1L) bytes(1)
                }
                "data" -> {
                    require(channels > 0 && sampleRate > 0) { "WAV ohne gueltigen fmt-Chunk" }
                    return channels to sampleRate
                }
                else -> skipFully(input, size + (size % 2))
            }
        }
    }

    /** Liefert dekodiertes 16-Bit-LE-PCM einer AAC-Spur, Frame fuer Frame beim Lesen. */
    private class M4aPcmInputStream(
        private val track: org.jcodec.common.DemuxerTrack,
        private val decoder: org.jcodec.codecs.aac.AACDecoder,
        private val channel: java.nio.channels.ReadableByteChannel
    ) : java.io.InputStream() {
        var sampleRate = 44100
            private set
        var channels = 1
            private set

        private var chunk = ByteArray(0)
        private var pos = 0
        private var finished = false

        fun prime() {
            fill()
            pos = 0
        }

        /** Dekodiert so lange Frames, bis PCM-Daten vorliegen oder die Spur endet. */
        private fun fill(): Boolean {
            while (!finished) {
                val packet = track.nextFrame()
                if (packet == null) {
                    finished = true
                    break
                }
                try {
                    val audioBuf = decoder.decodeFrame(packet.data, null) ?: continue
                    val fmt = audioBuf.format
                    sampleRate = fmt.sampleRate
                    channels = fmt.channels

                    val data = audioBuf.data
                    data.rewind()
                    val bytes = when (fmt.sampleSizeInBits) {
                        // 16-bit PCM: direkt kopieren
                        16 -> ByteArray(data.remaining()).also { data.get(it) }
                        // 24-bit → 16-bit: obere 2 Bytes nehmen
                        24 -> {
                            val out = ByteArray(data.remaining() / 3 * 2)
                            var o = 0
                            while (data.remaining() >= 3) {
                                data.get() // LSB (verwerfen)
                                out[o++] = data.get()
                                out[o++] = data.get() // MSB
                            }
                            out
                        }
                        else -> continue
                    }
                    if (bytes.isEmpty()) continue
                    chunk = bytes
                    pos = 0
                    return true
                } catch (_: Exception) {
                    // Einzelne kaputte Frames ueberspringen
                }
            }
            chunk = ByteArray(0)
            pos = 0
            return false
        }

        override fun read(): Int {
            if (pos >= chunk.size && !fill()) return -1
            return chunk[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= chunk.size && !fill()) return -1
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = channel.close()
    }

    // --- Interne Hilfsfunktionen ---

    /**
     * Wandelt einen beliebigen Stream in PCM um — mit Original-Kanalzahl und, wo moeglich,
     * Original-Bittiefe (FLAC 24 Bit). PCM-Streams (auch Float-WAV) bleiben unveraendert.
     */
    private fun toPcm(stream: AudioInputStream): AudioInputStream {
        val format = stream.format
        val encoding = format.encoding
        if (encoding == AudioFormat.Encoding.PCM_SIGNED ||
            encoding == AudioFormat.Encoding.PCM_UNSIGNED ||
            encoding == AudioFormat.Encoding.PCM_FLOAT
        ) {
            return stream
        }

        val channels = format.channels
        require(channels > 0) { "Audio-Format ohne Kanalangabe: $format" }

        // MP3/AAC melden keine Bittiefe (-1) → 16 Bit; FLAC 24/32 Bit → Original zuerst
        val preferredBits = if (format.sampleSizeInBits == 24 || format.sampleSizeInBits == 32) {
            format.sampleSizeInBits
        } else 16
        for (bits in listOf(preferredBits, 16).distinct()) {
            val pcmFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                format.sampleRate,
                bits,
                channels,
                channels * bits / 8,
                format.sampleRate,
                false
            )
            if (AudioSystem.isConversionSupported(pcmFormat, format)) {
                return AudioSystem.getAudioInputStream(pcmFormat, stream)
            }
        }

        throw IllegalArgumentException(
            "Audio-Format nicht unterstützt: ${format.encoding} " +
            "${format.sampleSizeInBits}bit ${format.channels}ch " +
            "${format.sampleRate}Hz"
        )
    }

    private fun decodeStream(pcmStream: AudioInputStream): AudioSegment {
        val converter = MonoConverter(pcmStream.format)
        val frameSize = converter.frameSize

        // Chunk-weise lesen und direkt in Floats wandeln — kein ByteArrayOutputStream
        // (der wuerde durch Verdoppeln + toByteArray() ein Mehrfaches des Speichers belegen).
        // Laenge ist bei WAV/FLAC bekannt → exakt vorallozieren; sonst (MP3) wachsen lassen.
        val knownFrames = pcmStream.frameLength
        var samples = FloatArray(
            if (knownFrames in 1..Int.MAX_VALUE.toLong()) knownFrames.toInt() else 1 shl 20
        )
        var numSamples = 0
        val readBuffer = ByteArray(frameSize * 16384)
        var carry = 0  // Bytes eines unvollstaendigen Frames aus dem letzten read()
        pcmStream.use { stream ->
            while (true) {
                val read = stream.read(readBuffer, carry, readBuffer.size - carry)
                if (read <= 0) break
                val available = carry + read
                val frames = available / frameSize
                if (numSamples + frames > samples.size) {
                    samples = samples.copyOf(maxOf(samples.size * 2, numSamples + frames))
                }
                converter.convert(readBuffer, frames, samples, numSamples)
                numSamples += frames
                carry = available - frames * frameSize
                if (carry > 0) System.arraycopy(readBuffer, frames * frameSize, readBuffer, 0, carry)
            }
        }
        if (numSamples != samples.size) samples = samples.copyOf(numSamples)

        return AudioSegment(samples = samples, sampleRate = pcmStream.format.sampleRate.toInt())
    }

    /**
     * Interleaved PCM (8/16/24/32 Bit signed/unsigned, 32/64 Bit Float, beliebige
     * Kanalzahl und Byte-Reihenfolge) → Float-Mono [-1..1]. Mehrkanal: Mittelwert.
     */
    private class MonoConverter(format: AudioFormat) {
        private val channels = format.channels
        private val bytesPerSample = (format.sampleSizeInBits + 7) / 8
        val frameSize = if (format.frameSize > 0) format.frameSize else channels * bytesPerSample
        private val bigEndian = format.isBigEndian
        private val kind: Kind = when (format.encoding) {
            AudioFormat.Encoding.PCM_SIGNED -> when (format.sampleSizeInBits) {
                8 -> Kind.S8; 16 -> Kind.S16; 24 -> Kind.S24; 32 -> Kind.S32
                else -> unsupported(format)
            }
            AudioFormat.Encoding.PCM_UNSIGNED -> when (format.sampleSizeInBits) {
                8 -> Kind.U8; 16 -> Kind.U16
                else -> unsupported(format)
            }
            AudioFormat.Encoding.PCM_FLOAT -> when (format.sampleSizeInBits) {
                32 -> Kind.F32; 64 -> Kind.F64
                else -> unsupported(format)
            }
            else -> unsupported(format)
        }

        private enum class Kind { S8, U8, S16, U16, S24, S32, F32, F64 }

        private fun unsupported(format: AudioFormat): Nothing =
            throw IllegalArgumentException("PCM-Format nicht unterstützt: $format")

        fun convert(bytes: ByteArray, frames: Int, dst: FloatArray, dstOffset: Int) {
            if (channels == 1) {
                for (f in 0 until frames) dst[dstOffset + f] = sample(bytes, f * frameSize)
            } else {
                for (f in 0 until frames) {
                    val base = f * frameSize
                    var sum = 0f
                    for (ch in 0 until channels) sum += sample(bytes, base + ch * bytesPerSample)
                    dst[dstOffset + f] = sum / channels
                }
            }
        }

        private fun u(bytes: ByteArray, i: Int): Int = bytes[i].toInt() and 0xFF

        private fun sample(b: ByteArray, o: Int): Float = when (kind) {
            Kind.S8 -> b[o] / 128f
            Kind.U8 -> (u(b, o) - 128) / 128f
            Kind.S16 -> {
                val v = if (bigEndian) (b[o].toInt() shl 8) or u(b, o + 1)
                        else (b[o + 1].toInt() shl 8) or u(b, o)
                v / 32768f
            }
            Kind.U16 -> {
                val v = if (bigEndian) (u(b, o) shl 8) or u(b, o + 1)
                        else (u(b, o + 1) shl 8) or u(b, o)
                (v - 32768) / 32768f
            }
            Kind.S24 -> {
                val v = if (bigEndian) (b[o].toInt() shl 16) or (u(b, o + 1) shl 8) or u(b, o + 2)
                        else (b[o + 2].toInt() shl 16) or (u(b, o + 1) shl 8) or u(b, o)
                v / 8388608f
            }
            Kind.S32 -> (int32(b, o) / 2147483648.0).toFloat()
            Kind.F32 -> Float.fromBits(int32(b, o))
            Kind.F64 -> {
                val hi = int32(b, if (bigEndian) o else o + 4).toLong()
                val lo = int32(b, if (bigEndian) o + 4 else o).toLong() and 0xFFFFFFFFL
                Double.fromBits((hi shl 32) or lo).toFloat()
            }
        }

        private fun int32(b: ByteArray, o: Int): Int =
            if (bigEndian) (b[o].toInt() shl 24) or (u(b, o + 1) shl 16) or (u(b, o + 2) shl 8) or u(b, o + 3)
            else (b[o + 3].toInt() shl 24) or (u(b, o + 2) shl 16) or (u(b, o + 1) shl 8) or u(b, o)
    }

    /**
     * Ueberspringt n Bytes. skip() springt bei unkomprimiertem WAV direkt in der Datei;
     * liefert ein Stream 0 (z.B. Konverter-Streams), wird per read() verworfen.
     */
    private fun skipFully(stream: java.io.InputStream, n: Long) {
        var remaining = n
        var scratch: ByteArray? = null
        while (remaining > 0) {
            // Manche SPI-Streams (z.B. JFLAC) werfen bei skip() statt 0 zu liefern
            val skipped = try { stream.skip(remaining) } catch (_: java.io.IOException) { 0L }
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
