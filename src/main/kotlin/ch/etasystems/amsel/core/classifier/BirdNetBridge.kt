package ch.etasystems.amsel.core.classifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import org.slf4j.LoggerFactory
import javax.sound.sampled.AudioSystem

/**
 * BirdNET Classifier via Python-Bridge.
 *
 * Nutzt bevorzugt einen persistenten Python-Daemon (classify_daemon.py) auf localhost:5757,
 * der das BirdNET-Modell einmalig laedt und im Speicher haelt.
 * Fallback: Startet Python-Prozess pro Aufruf (langsam, ~10-15s TF-Init).
 *
 * Voraussetzungen:
 * - Python 3.x installiert (mit birdnetlib: pip install birdnetlib)
 * - classify.py + classify_daemon.py werden automatisch nach Dokumente/AMSEL/models/ extrahiert
 *
 * Wenn Python nicht verfuegbar: gibt leere Liste zurueck (Fallback auf Embedding/MFCC)
 */
object BirdNetBridge {

    private val logger = LoggerFactory.getLogger(BirdNetBridge::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private const val DAEMON_PORT = 5757
    private const val DAEMON_URL = "http://127.0.0.1:$DAEMON_PORT"

    // Daemon-Prozess Referenz (null = nicht gestartet)
    @Volatile
    private var daemonProcess: Process? = null

    // Gecachter Python-Pfad (null = noch nicht gesucht, "" = nicht gefunden)
    @Volatile
    private var cachedPythonPath: String? = null

    // Letzter Fehler fuer UI-Anzeige
    @Volatile
    var lastError: String? = null
        private set

    /** Pruefe ob Python + Scripts verfuegbar sind */
    fun isAvailable(): Boolean {
        return try {
            ensureScripts()
            val scriptFile = getScriptFile()
            if (!scriptFile.exists()) {
                lastError = "classify.py nicht gefunden: ${scriptFile.absolutePath}"
                System.err.println("[BirdNetBridge] $lastError")
                return false
            }
            val python = findPython()
            if (python.isEmpty()) {
                lastError = "Python mit birdnetlib nicht gefunden. Bitte installieren: pip install birdnetlib"
                System.err.println("[BirdNetBridge] $lastError")
                return false
            }
            val process = ProcessBuilder(python, "--version")
                .redirectErrorStream(true)
                .start()
            val ok = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) { process.destroyForcibly(); return false }
            val output = process.inputStream.bufferedReader().readText().trim()
            val available = output.contains("Python")
            System.err.println("[BirdNetBridge] Python verfuegbar: $available ($output, path=$python)")
            lastError = null
            available
        } catch (e: Exception) {
            lastError = "BirdNET-Pruefung fehlgeschlagen: ${e.message}"
            System.err.println("[BirdNetBridge] isAvailable Fehler: ${e.message}")
            false
        }
    }

    private fun getModelsDir(): File {
        val userHome = System.getProperty("user.home")
        return File(userHome, "Documents/AMSEL/models")
    }

    private fun getScriptFile(): File {
        return File(getModelsDir(), "classify.py")
    }

    private fun getDaemonScriptFile(): File {
        return File(getModelsDir(), "classify_daemon.py")
    }

    /**
     * Sucht Python auf Windows — robuster als nur PATH.
     * Prueft: spezifische Installationspfade, PATH, 'where python', Registry-Pfade.
     */
    private fun findPython(): String {
        // Gecachten Wert zurueckgeben
        cachedPythonPath?.let { return it }

        val home = System.getProperty("user.home")

        // 1. Spezifische Installationspfade (haeufigste Windows-Installationen)
        val candidates = mutableListOf<String>()
        // Standard Python Installer
        for (ver in listOf("312", "313", "311", "310", "39")) {
            candidates.add("$home/AppData/Local/Programs/Python/Python$ver/python.exe")
        }
        // Anaconda / Miniconda
        candidates.add("$home/anaconda3/python.exe")
        candidates.add("$home/miniconda3/python.exe")
        candidates.add("$home/AppData/Local/anaconda3/python.exe")
        candidates.add("$home/AppData/Local/miniconda3/python.exe")
        // System-weite Installation
        for (ver in listOf("312", "313", "311", "310")) {
            candidates.add("C:/Python$ver/python.exe")
            candidates.add("C:/Program Files/Python$ver/python.exe")
        }
        // Scoop
        candidates.add("$home/scoop/apps/python/current/python.exe")

        for (candidate in candidates) {
            try {
                val f = File(candidate)
                if (!f.exists()) continue
                if (testPythonWithBirdnet(candidate)) {
                    cachedPythonPath = candidate
                    System.err.println("[BirdNetBridge] Python mit birdnetlib gefunden: $candidate")
                    return candidate
                }
            } catch (_: Exception) { }
        }

        // 2. PATH-basiert: 'python' und 'python3'
        for (cmd in listOf("python", "python3")) {
            try {
                if (testPythonWithBirdnet(cmd)) {
                    cachedPythonPath = cmd
                    System.err.println("[BirdNetBridge] Python mit birdnetlib im PATH: $cmd")
                    return cmd
                }
            } catch (_: Exception) { }
        }

        // 3. Windows 'where python' — findet auch py-Launcher und Store-Versionen
        try {
            val whereProc = ProcessBuilder("where", "python")
                .redirectErrorStream(true)
                .start()
            val whereOutput = whereProc.inputStream.bufferedReader().readText().trim()
            if (whereProc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                for (line in whereOutput.lines()) {
                    val path = line.trim()
                    if (path.isNotEmpty() && File(path).exists() &&
                        !path.contains("WindowsApps")) { // Microsoft Store Stub ignorieren
                        if (testPythonWithBirdnet(path)) {
                            cachedPythonPath = path
                            System.err.println("[BirdNetBridge] Python via 'where': $path")
                            return path
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        System.err.println("[BirdNetBridge] KEIN Python mit birdnetlib gefunden!")
        cachedPythonPath = ""
        return ""
    }

    /** Prueft ob ein Python-Pfad funktioniert UND birdnetlib hat */
    private fun testPythonWithBirdnet(pythonCmd: String): Boolean {
        return try {
            val process = ProcessBuilder(pythonCmd, "-c", "import birdnetlib; print('OK')")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && output.contains("OK")
        } catch (_: Exception) {
            false
        }
    }

    // ==============================================================
    // Script-Verwaltung: Ressourcen nach Documents extrahieren
    // ==============================================================

    /**
     * Extrahiert classify.py + classify_daemon.py aus JAR-Ressourcen
     * nach Dokumente/AMSEL/models/ (erstellt Verzeichnis bei Bedarf).
     * Ueberschreibt bestehende Dateien bei neuer Version.
     */
    private fun ensureScripts() {
        val modelsDir = getModelsDir()
        modelsDir.mkdirs()

        extractResource("/models/classify.py", File(modelsDir, "classify.py"))
        extractResource("/models/classify_daemon.py", File(modelsDir, "classify_daemon.py"))
    }

    private fun extractResource(resourcePath: String, target: File) {
        try {
            val stream = BirdNetBridge::class.java.getResourceAsStream(resourcePath)
            if (stream != null) {
                val newContent = stream.use { it.readBytes() }
                // Nur ueberschreiben wenn Inhalt sich unterscheidet
                if (!target.exists() || !target.readBytes().contentEquals(newContent)) {
                    target.writeBytes(newContent)
                    System.err.println("[BirdNetBridge] $resourcePath extrahiert nach ${target.absolutePath}")
                }
            } else {
                System.err.println("[BirdNetBridge] Ressource nicht gefunden: $resourcePath")
            }
        } catch (e: Exception) {
            System.err.println("[BirdNetBridge] Extraktion fehlgeschlagen ($resourcePath): ${e.message}")
        }
    }

    // ==============================================================
    // Daemon-Verwaltung
    // ==============================================================

    /**
     * Prueft ob der Daemon auf localhost:5757 laeuft (GET /health).
     * Timeout: 2 Sekunden.
     */
    fun isDaemonRunning(): Boolean {
        return try {
            val conn = URL("$DAEMON_URL/health").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            code == 200 && body.contains("ok")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Startet den Python-Daemon als Hintergrund-Prozess.
     * Wartet bis zu 60 Sekunden auf /health (TF-Modell laden braucht Zeit).
     */
    fun startDaemon() {
        if (isDaemonRunning()) {
            System.err.println("[BirdNetBridge] Daemon laeuft bereits.")
            return
        }

        ensureScripts()
        val script = getDaemonScriptFile()
        if (!script.exists()) {
            lastError = "classify_daemon.py nicht gefunden: ${script.absolutePath}"
            System.err.println("[BirdNetBridge] $lastError")
            return
        }

        val python = findPython()
        if (python.isEmpty()) {
            lastError = "Python mit birdnetlib nicht gefunden"
            System.err.println("[BirdNetBridge] $lastError")
            return
        }
        System.err.println("[BirdNetBridge] Starte Daemon: $python ${script.absolutePath}")

        val process = ProcessBuilder(python, script.absolutePath)
            .redirectErrorStream(false)
            .start()
        daemonProcess = process

        // stderr in separatem Thread loggen (nicht blockierend)
        Thread({
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    System.err.println("[Daemon] $line")
                }
            } catch (_: Exception) { }
        }, "birdnet-daemon-stderr").apply { isDaemon = true }.start()

        // Warte bis Daemon antwortet (max 60s, TF laden dauert)
        val startTime = System.currentTimeMillis()
        val timeoutMs = 60_000L
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isDaemonRunning()) {
                System.err.println("[BirdNetBridge] Daemon bereit nach ${System.currentTimeMillis() - startTime}ms")
                lastError = null
                return
            }
            // Pruefe ob Prozess abgestuerzt ist
            if (!process.isAlive) {
                lastError = "Daemon-Prozess beendet (exit=${process.exitValue()}). Ist birdnetlib installiert?"
                System.err.println("[BirdNetBridge] $lastError")
                daemonProcess = null
                return
            }
            Thread.sleep(500)
        }
        lastError = "Daemon-Timeout nach ${timeoutMs}ms — TF-Modell konnte nicht geladen werden"
        System.err.println("[BirdNetBridge] $lastError")
    }

    /**
     * Beendet den Daemon sauber via POST /shutdown.
     * Wird bei App-Beenden aufgerufen (Shutdown-Hook).
     */
    fun stopDaemon() {
        try {
            if (isDaemonRunning()) {
                val conn = URL("$DAEMON_URL/shutdown").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.write("{}".toByteArray())
                conn.responseCode // Response abwarten
                conn.disconnect()
                System.err.println("[BirdNetBridge] Daemon Shutdown gesendet.")
            }
        } catch (_: Exception) {
            // Ignorieren - Daemon war vielleicht schon beendet
        }
        // Prozess sicherheitshalber beenden
        daemonProcess?.let { proc ->
            if (proc.isAlive) {
                proc.destroyForcibly()
                System.err.println("[BirdNetBridge] Daemon-Prozess forciert beendet.")
            }
        }
        daemonProcess = null
    }

    /**
     * Klassifiziert eine Datei ueber den Daemon (HTTP POST /classify).
     * Gibt null zurueck wenn Daemon nicht erreichbar (Caller nutzt dann Fallback).
     */
    private fun classifyViaDaemon(
        audioFile: File,
        minConf: Float,
        lat: Float,
        lon: Float
    ): List<BridgeResult>? {
        return try {
            val conn = URL("$DAEMON_URL/classify").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 120_000 // Lange Dateien brauchen Zeit
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            // JSON-Body bauen (ohne extra Library)
            val body = buildString {
                append("{")
                append("\"audio_file\":\"${audioFile.absolutePath.replace("\\", "\\\\")}\"")
                append(",\"min_conf\":$minConf")
                if (lat > 0 && lon > 0) {
                    append(",\"lat\":$lat,\"lon\":$lon")
                }
                append("}")
            }
            conn.outputStream.write(body.toByteArray())

            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return null
            }
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            json.decodeFromString<List<BridgeResult>>(response)
        } catch (e: Exception) {
            System.err.println("[BirdNetBridge] Daemon-Request fehlgeschlagen: ${e.message}")
            null
        }
    }

    /** Shutdown-Hook registrieren — einmalig aufrufen bei App-Start */
    fun registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread({
            System.err.println("[BirdNetBridge] Shutdown-Hook: Daemon beenden...")
            stopDaemon()
        }, "birdnet-shutdown"))
    }

    @Serializable
    data class BridgeResult(
        val species: String = "",
        val scientific_name: String = "",
        val confidence: Float = 0f,
        val start_time: Float = 0f,
        val end_time: Float = 0f,
        val label: String = "",
        val error: String? = null
    )

    /**
     * Klassifiziert Audio-Samples ueber die Python-Bridge.
     *
     * Strategie: Temp-WAV erstellen, dann Daemon probieren, Fallback auf Prozess.
     *
     * @param samples PCM Mono Float [-1..1]
     * @param sampleRate Samplerate
     * @param topN Anzahl Ergebnisse
     * @param lat Breitengrad (optional, fuer ortsspezifische Filterung)
     * @param lon Laengengrad (optional)
     * @return Liste von ClassifierResult, absteigend nach Konfidenz
     */
    fun classify(
        samples: FloatArray,
        sampleRate: Int,
        topN: Int = 10,
        lat: Float = -1f,
        lon: Float = -1f,
        minConf: Float = 0.1f
    ): List<ClassifierResult> {
        if (samples.isEmpty()) return emptyList()

        return try {
            // 1. Temporaere WAV-Datei erstellen
            val tempWav = File.createTempFile("amsel_classify_", ".wav")
            tempWav.deleteOnExit()
            writeWav(samples, sampleRate, tempWav)

            // 2. classifyFile nutzt automatisch Daemon mit Fallback
            val results = classifyFile(tempWav, topN, lat, lon, minConf)

            // Temporaere Datei loeschen
            tempWav.delete()

            results
        } catch (e: Exception) {
            System.err.println("[BirdNetBridge] Klassifikation fehlgeschlagen: ${e.message}")
            emptyList()
        }
    }

    /**
     * Klassifiziert eine Audio-DATEI direkt.
     *
     * Strategie:
     * 1. Daemon starten falls noetig (einmalig ~10-15s, danach persistent)
     * 2. HTTP-Request an Daemon (schnell, ~2s)
     * 3. Fallback auf Python-Prozess wenn Daemon nicht verfuegbar
     */
    fun classifyFile(
        audioFile: File,
        topN: Int = 500,
        lat: Float = -1f,
        lon: Float = -1f,
        minConf: Float = 0.1f
    ): List<ClassifierResult> {
        if (!audioFile.exists()) {
            logger.debug("[classifyFile] Datei nicht gefunden: {}", audioFile.absolutePath)
            return emptyList()
        }

        // Scripts sicherstellen (bei .exe koennten sie fehlen)
        ensureScripts()

        // --- Daemon-Weg (bevorzugt) ---
        // Daemon starten wenn nicht laeuft
        if (!isDaemonRunning()) {
            logger.debug("[classifyFile] Daemon nicht erreichbar, starte...")
            startDaemon()
        }
        if (isDaemonRunning()) {
            logger.debug("[classifyFile] Nutze Daemon fuer {}", audioFile.absolutePath)
            val daemonResults = classifyViaDaemon(audioFile, minConf, lat, lon)
            if (daemonResults != null) {
                if (daemonResults.any { it.error != null }) {
                    logger.debug("[classifyFile] Daemon-Fehler: {}", daemonResults.first { it.error != null }.error)
                    // Fallback auf Prozess-Weg
                } else {
                    logger.debug("[classifyFile] Daemon: {} Ergebnisse", daemonResults.size)
                    return daemonResults
                        .filter { it.confidence >= minConf }
                        .sortedByDescending { it.confidence }
                        .let { if (topN > 0) it.take(topN) else it }
                        .map { ClassifierResult(
                            species = it.label.ifEmpty { it.species },
                            confidence = it.confidence,
                            startTime = it.start_time,
                            endTime = it.end_time,
                            scientificName = it.scientific_name
                        ) }
                }
            }
            logger.debug("[classifyFile] Daemon-Antwort null, Fallback auf Prozess")
        }

        // --- Fallback: Python-Prozess pro Aufruf (langsam) ---
        val scriptFile = getScriptFile()
        if (!scriptFile.exists()) {
            logger.debug("[classifyFile] Script nicht gefunden: {}", scriptFile.absolutePath)
            return emptyList()
        }

        return try {
            val pythonPath = findPython()
            if (pythonPath.isEmpty()) {
                logger.debug("[classifyFile] Python nicht gefunden!")
                return emptyList()
            }
            val cmd = mutableListOf(
                pythonPath,
                scriptFile.absolutePath,
                audioFile.absolutePath,
                "--min_conf", minConf.toString()
            )
            if (lat > 0 && lon > 0) {
                cmd.addAll(listOf("--lat", lat.toString(), "--lon", lon.toString()))
            }
            logger.debug("[classifyFile] Fallback-CMD: {}", cmd.joinToString(" "))

            val process = ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()

            // WICHTIG: stdout und stderr in separaten Threads lesen
            // (sonst Deadlock wenn Buffers volllaufen bei grossem Output!)
            var stdout = ""
            var stderr = ""
            val stdoutThread = Thread { stdout = process.inputStream.bufferedReader().readText().trim() }
            val stderrThread = Thread { stderr = process.errorStream.bufferedReader().readText().trim() }
            stdoutThread.start()
            stderrThread.start()
            val exitCode = process.waitFor()
            stdoutThread.join(10000)
            stderrThread.join(10000)

            logger.debug("[classifyFile] Exit={}, stdout={} chars, stderr={}", exitCode, stdout.length, stderr.take(200))

            if (exitCode != 0 || stdout.isEmpty()) {
                logger.debug("[classifyFile] FEHLER exit={} stderr={}", exitCode, stderr)
                return emptyList()
            }

            // JSON aus stdout extrahieren (Debug-Prints vor dem JSON ignorieren)
            val jsonStr = stdout.substringAfterLast("\n[").let { "[$it" }.takeIf { it.startsWith("[") }
                ?: stdout.lines().lastOrNull { it.trimStart().startsWith("[") }
                ?: run {
                    logger.debug("[classifyFile] Kein JSON in stdout gefunden!")
                    return emptyList()
                }
            logger.debug("[classifyFile] JSON: {}...", jsonStr.take(100))
            val results = json.decodeFromString<List<BridgeResult>>(jsonStr)
            if (results.any { it.error != null }) return emptyList()

            results
                .filter { it.confidence >= minConf }
                .sortedByDescending { it.confidence }
                .let { if (topN > 0) it.take(topN) else it }
                .map { ClassifierResult(
                    species = it.label.ifEmpty { it.species },
                    confidence = it.confidence,
                    startTime = it.start_time,
                    endTime = it.end_time,
                    scientificName = it.scientific_name
                ) }
        } catch (e: Exception) {
            System.err.println("[BirdNetBridge] classifyFile fehlgeschlagen: ${e.message}")
            emptyList()
        }
    }

    /**
     * Schreibt PCM Float-Samples als WAV-Datei.
     */
    private fun writeWav(samples: FloatArray, sampleRate: Int, outputFile: File) {
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val pcm16 = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        val bytes = ByteArray(pcm16.size * 2)
        for (i in pcm16.indices) {
            bytes[i * 2] = (pcm16[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (pcm16[i].toInt() shr 8 and 0xFF).toByte()
        }
        val bais = java.io.ByteArrayInputStream(bytes)
        val ais = AudioInputStream(bais, format, samples.size.toLong())
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outputFile)
    }
}
