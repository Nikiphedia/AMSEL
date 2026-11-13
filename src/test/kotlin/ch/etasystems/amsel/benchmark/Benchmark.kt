package ch.etasystems.amsel.benchmark

import ch.etasystems.amsel.core.audio.AudioDecoder
import ch.etasystems.amsel.core.classifier.ClassifierResult
import ch.etasystems.amsel.core.classifier.OnnxBirdNetV3
import ch.etasystems.amsel.core.similarity.CosineSimilarityMetric
import ch.etasystems.amsel.core.similarity.RecordingInfo
import ch.etasystems.amsel.core.similarity.RecordingProvider
import ch.etasystems.amsel.core.similarity.SimilarityEngine
import ch.etasystems.amsel.core.spectrogram.ChunkedSpectrogram
import ch.etasystems.amsel.data.SettingsStore
import ch.etasystems.amsel.data.reference.ReferenceLibrary
import ch.etasystems.amsel.data.reference.ReferenceLibraryFeatureProvider
import ch.etasystems.amsel.data.resolvedModelDir
import ch.etasystems.amsel.ui.compare.AudioManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Messlauf fuer Import, BirdNET-Scan und Aehnlichkeitssuche (TASK B1).
 *
 * Aufruf: gradlew benchmark --args="<langdatei> [<ordner mit kurzausschnitten>] [--gain-db=<dB>]"
 * --nur-import beendet nach dem Import (fuer Laeufe mit begrenztem Heap, -PbenchHeap=256m).
 * --kanal=<n> waehlt den Kanal der Langdatei: 0 automatisch, -1 Mittelwert, -2 lautester, 1..n Kanal n.
 * --gain-db verstaerkt die Langdatei vor dem Scan. Noetig fuer leise Feldaufnahmen: ohne Verstaerkung
 * ueberspringt der Stille-Filter des Klassifikators (-50 dBFS) alle Fenster, und es wird nichts gemessen.
 * --modell=<datei> rechnet mit dieser ONNX-Datei aus dem Modellordner statt BirdNET V3 (z.B. perch_v2.onnx).
 * --vergleich=<json> vergleicht die Kurzausschnitte Fenster fuer Fenster mit Sittas Werten
 *   (ausschnitte-kern-perch.json), schreibt build/benchmark/vergleich.csv und beendet.
 * Ausgabe: build/benchmark/benchmark.csv (eine Zeile je Lauf) und
 *          build/benchmark/treffer_<datei>.csv (sortierte Trefferliste des Scans).
 */

/** Erwartete Art je Kurzausschnitt, abgeschrieben aus K:\sittea\Audio\0316-ausschnitte\liste.txt. */
private val ERWARTET = mapOf(
    "A01-waldkauz-165740Z.wav" to "Strix aluco",
    "A02-waldkauz-165824Z.wav" to "Strix aluco",
    "A03-waldkauz-171605Z.wav" to "Strix aluco",
    "A05-waldkauz-172817Z.wav" to "Strix aluco",
    "A06-waldkauz-geraet-171813Z.wav" to "Strix aluco",
    "A09-graureiher-014956Z.wav" to "Ardea cinerea",
    "A10-schleiereule-015448Z.wav" to "Tyto alba",
    "A11-rotkehlchen-041631Z.wav" to "Erithacus rubecula",
    "A12-schwarzspecht-041422Z.wav" to "Dryocopus martius"
)

private const val SUCHE_SEKUNDEN = 5f

/** Online-Suche ist im Benchmark abgeschaltet: liefert nie Kandidaten. */
private object KeinNetz : RecordingProvider {
    override suspend fun search(query: String, page: Int, perPage: Int): List<RecordingInfo> = emptyList()
    override suspend fun downloadAudio(audioUrl: String, recordingId: String): File =
        throw UnsupportedOperationException("Benchmark laedt nichts herunter")
}

/** Tastet den belegten Heap alle 200 ms ab und merkt sich den hoechsten Wert. */
private class HeapWaechter : Thread("heap-waechter") {
    @Volatile var maxBytes = 0L
    @Volatile private var laeuft = true

    init { isDaemon = true }

    override fun run() {
        val rt = Runtime.getRuntime()
        while (laeuft) {
            val belegt = rt.totalMemory() - rt.freeMemory()
            if (belegt > maxBytes) maxBytes = belegt
            try { sleep(200) } catch (_: InterruptedException) { return }
        }
    }

    fun stoppen() { laeuft = false; interrupt() }
}

private fun mb(bytes: Long): String = String.format(Locale.US, "%.0f", bytes / 1_048_576.0)
private fun ms(nanos: Long): String = String.format(Locale.US, "%.0f", nanos / 1_000_000.0)

private inline fun <T> gemessen(block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val ergebnis = block()
    return ergebnis to (System.nanoTime() - start)
}

/** Modellordner: zuerst der aus den Einstellungen, sonst der Standardordner im Benutzerprofil. */
private fun modellSuchen(): Pair<File, File>? {
    val ordner = listOf(
        SettingsStore.load().resolvedModelDir(),
        File(System.getProperty("user.home"), "Documents/AMSEL/models")
    )
    for (dir in ordner) {
        val modell = listOf("birdnet_v3.onnx", "BirdNET+_V3.0-preview3_Global_11K_FP32.onnx")
            .map { File(dir, it) }.firstOrNull { it.exists() }
        val labels = listOf("birdnet_v3_labels.csv", "BirdNET+_V3.0-preview3_Global_11K_Labels.csv")
            .map { File(dir, it) }.firstOrNull { it.exists() }
        if (modell != null && labels != null) return modell to labels
    }
    return null
}

private fun trefferSchreiben(datei: File, treffer: List<ClassifierResult>) {
    val sortiert = treffer.sortedWith(
        compareBy<ClassifierResult> { it.startTime }.thenBy { it.scientificName }.thenByDescending { it.confidence }
    )
    datei.printWriter().use { out ->
        out.println("start_s,ende_s,art,konfidenz")
        for (t in sortiert) {
            out.println(String.format(Locale.US, "%.3f,%.3f,%s,%.4f", t.startTime, t.endTime, t.scientificName, t.confidence))
        }
    }
}

/**
 * Vergleicht je Fenster mit den Werten des Sitta-Kerns. Sitta rechnet 5-s-Fenster im Abstand von 3 s
 * und nennt je Fenster die fuenf besten Arten in Prozent.
 */
private fun mitSittaVergleichen(classifier: OnnxBirdNetV3, kurzOrdner: File, json: File, ausgabe: File) {
    val wurzel = Json.parseToJsonElement(json.readText()).jsonObject
    val dateien = wurzel["dateien"]!!.jsonObject
    val ueberlappung = classifier.adapter.fensterSek - 3f
    var fenster = 0
    var gleicheBeste = 0
    val abweichungen = ArrayList<Float>()
    File(ausgabe, "vergleich.csv").printWriter().use { out ->
        out.println("datei,start_s,sitta_art,sitta_prozent,amsel_prozent_dieser_art,abweichung,amsel_beste_art,amsel_beste_prozent")
        for ((name, jeFenster) in dateien) {
            val datei = File(kurzOrdner, name)
            if (!datei.isFile) { println("fehlt: $name"); continue }
            val seg = AudioDecoder.decode(datei)
            val res = runBlocking {
                classifier.classify(seg.samples, seg.sampleRate, overlap = ueberlappung, minConfidence = 0f, topN = 0)
            }
            for ((start, liste) in jeFenster.jsonObject) {
                val startSek = start.toFloat()
                val erster = liste.jsonArray.firstOrNull()?.jsonArray ?: continue
                val sittaArt = erster[0].jsonPrimitive.content
                val sittaProzent = erster[1].jsonPrimitive.float
                val imFenster = res.filter { kotlin.math.abs(it.startTime - startSek) < 0.01f }
                val beste = imFenster.maxByOrNull { it.confidence }
                // Nur die Kandidaten je Fenster sind bekannt (hoechstens 10); fehlt die Art, bleibt das Feld leer
                val dieselbe = imFenster.firstOrNull { it.scientificName == sittaArt }?.confidence?.times(100f)
                fenster++
                if (beste?.scientificName == sittaArt) gleicheBeste++
                if (dieselbe != null) abweichungen.add(dieselbe - sittaProzent)
                out.println(String.format(
                    Locale.US, "%s,%s,%s,%.1f,%s,%s,%s,%s", name, start, sittaArt, sittaProzent,
                    dieselbe?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    dieselbe?.let { String.format(Locale.US, "%+.1f", it - sittaProzent) } ?: "",
                    beste?.scientificName ?: "", beste?.let { String.format(Locale.US, "%.1f", it.confidence * 100f) } ?: ""
                ))
            }
        }
    }
    val betrag = abweichungen.map { kotlin.math.abs(it) }
    println("Vergleich mit Sitta: $fenster Fenster, gleiche beste Art in $gleicheBeste")
    if (betrag.isNotEmpty()) {
        println(String.format(
            Locale.US, "Sittas beste Art in %d Fenstern auch bei AMSEL unter den Kandidaten: Abweichung im Mittel %.1f, groesste %.1f Prozentpunkte",
            betrag.size, betrag.average(), betrag.max()
        ))
    }
}

fun main(args: Array<String>) {
    val gainDb = args.firstOrNull { it.startsWith("--gain-db=") }?.substringAfter("=")?.toFloatOrNull() ?: 0f
    val kanal = args.firstOrNull { it.startsWith("--kanal=") }?.substringAfter("=")?.toIntOrNull() ?: AudioDecoder.KANAL_AUTO
    val pfade = args.filterNot { it.startsWith("--") }
    if (pfade.isEmpty()) {
        System.err.println("Aufruf: benchmark <langdatei> [<ordner mit kurzausschnitten>] [--gain-db=<dB>]")
        exitProcess(2)
    }
    val langdatei = File(pfade[0])
    if (!langdatei.isFile) {
        System.err.println("Datei nicht gefunden: ${langdatei.absolutePath}")
        exitProcess(2)
    }
    val kurzOrdner = pfade.getOrNull(1)?.let { File(it) }
    if (kurzOrdner != null && !kurzOrdner.isDirectory) {
        System.err.println("Ordner nicht gefunden: ${kurzOrdner.absolutePath}")
        exitProcess(2)
    }
    val modellName = args.firstOrNull { it.startsWith("--modell=") }?.substringAfter("=")
    val vergleich = args.firstOrNull { it.startsWith("--vergleich=") }?.substringAfter("=")?.let { File(it) }
    val classifier = if (modellName != null) {
        val k = OnnxBirdNetV3.fuerDatei(modellName)
        val grund = if (k == null) "Modelldatei fehlt: $modellName" else k.pruefen()
        if (k == null || grund != null) {
            System.err.println("Modell nicht verwendbar: $grund")
            exitProcess(2)
        }
        k
    } else {
        val modell = modellSuchen()
        if (modell == null) {
            System.err.println("BirdNET-V3-Modell oder Labels nicht gefunden (Einstellungen und Documents/AMSEL/models geprueft)")
            exitProcess(2)
        }
        println("Modell: ${modell.first.absolutePath}")
        OnnxBirdNetV3(modell.first.absolutePath, modell.second.absolutePath)
    }
    println("Modellart: ${classifier.adapter.art}, ${classifier.speciesCount} Arten")
    if (vergleich != null) {
        if (kurzOrdner == null) { System.err.println("--vergleich braucht den Ordner mit den Kurzausschnitten"); exitProcess(2) }
        mitSittaVergleichen(classifier, kurzOrdner, vergleich, File("build/benchmark").also { it.mkdirs() })
        classifier.close()
        return
    }
    println("Max. Heap: ${mb(Runtime.getRuntime().maxMemory())} MB")

    val ausgabe = File("build/benchmark").also { it.mkdirs() }
    val minConf = SettingsStore.load().birdnetMinConf
    val waechter = HeapWaechter().also { it.start() }

    try {
        // --- Import wie in der App (AudioManager.importAudio), mit eigenem Heap-Hoechstwert ---
        System.gc()
        waechter.maxBytes = 0L
        val audioManager = AudioManager()
        val (_, importNs) = gemessen {
            runBlocking {
                audioManager.importAudio(
                    file = langdatei, maxFreqHz = 16000f, minDisplayDurationSec = 10f, shortFileStartPct = 0.5f,
                    sliceLengthMin = 10f, sliceOverlapSec = 5f, initialZoomDurationSec = 30f, kanal = kanal
                )
            }
        }
        Thread.sleep(250)  // dem Waechter einen letzten Abtastwert lassen
        val importHeapMb = mb(waechter.maxBytes)
        audioManager.closeAudio()
        println("Import: ${ms(importNs)} ms, Heap max. $importHeapMb MB")
        if ("--nur-import" in args) exitProcess(0)
        System.gc()

        // --- Langtest ---
        val (segment, decodeNs) = gemessen { AudioDecoder.decode(langdatei, kanal) }
        println("Dekodiert: ${segment.durationSec} s bei ${segment.sampleRate} Hz in ${ms(decodeNs)} ms")

        val (_, overviewNs) = gemessen { runBlocking { ChunkedSpectrogram.computeOverview(segment) } }
        println("Uebersicht: ${ms(overviewNs)} ms")

        if (gainDb != 0f) {
            val faktor = Math.pow(10.0, gainDb / 20.0).toFloat()
            val s = segment.samples
            for (i in s.indices) s[i] = (s[i] * faktor).coerceIn(-1f, 1f)
        }
        val (treffer, scanNs) = gemessen {
            runBlocking { classifier.classify(segment.samples, segment.sampleRate, minConfidence = minConf, topN = 0) }
        }
        val rtf = segment.durationSec / (scanNs / 1e9)
        val stats = classifier.lastClassifyStats
        println("Scan: ${ms(scanNs)} ms, RTF ${String.format(Locale.US, "%.1f", rtf)}, ${treffer.size} Treffer, " +
            "${stats?.classifiedChunks} von ${stats?.totalChunks} Fenstern gerechnet (Rest als Stille uebersprungen)")
        trefferSchreiben(File(ausgabe, "treffer_${langdatei.nameWithoutExtension}_gain${gainDb.toInt()}_kanal$kanal${if (modellName != null) "_" + classifier.adapter.art else ""}.csv"), treffer)

        // --- Aehnlichkeitssuche (nur offline, nur wenn lokale Features vorhanden) ---
        val provider = ReferenceLibraryFeatureProvider(ReferenceLibrary())
        val hatFeatures = provider.loadAllFeatures(CosineSimilarityMetric.cacheKey).isNotEmpty() ||
            provider.loadAllMfccFeatures().isNotEmpty()
        var sucheErst = ""
        var sucheZweit = ""
        if (hatFeatures) {
            val engine = SimilarityEngine(KeinNetz, provider, CosineSimilarityMetric)
            val anfrage = segment.subRangeSec(0f, SUCHE_SEKUNDEN)
            sucheErst = ms(gemessen { runBlocking { engine.compare(anfrage) } }.second)
            sucheZweit = ms(gemessen { runBlocking { engine.compare(anfrage) } }.second)
            println("Suche: $sucheErst ms, zweite $sucheZweit ms")
        } else {
            println("Suche: uebersprungen (keine lokalen Referenz-Features)")
        }

        // --- Kurztest ---
        var kurzErkannt = ""
        var kurzTop1 = ""
        if (kurzOrdner != null) {
            var erkannt = 0
            var top1 = 0
            File(ausgabe, "kurztest.csv").printWriter().use { out ->
                out.println("datei,erwartet,erkannt,beste_konfidenz_erwartet,beste_art,beste_konfidenz")
                for ((name, art) in ERWARTET) {
                    val datei = File(kurzOrdner, name)
                    if (!datei.isFile) {
                        out.println("$name,$art,fehlt,,,")
                        continue
                    }
                    val seg = AudioDecoder.decode(datei)
                    val res = runBlocking { classifier.classify(seg.samples, seg.sampleRate, minConfidence = minConf, topN = 0) }
                    val besteErwartet = res.filter { it.scientificName == art }.maxOfOrNull { it.confidence }
                    val beste = res.maxByOrNull { it.confidence }
                    if (besteErwartet != null) erkannt++
                    if (beste?.scientificName == art) top1++
                    out.println(String.format(
                        Locale.US, "%s,%s,%d,%s,%s,%s", name, art, if (besteErwartet != null) 1 else 0,
                        besteErwartet?.let { String.format(Locale.US, "%.4f", it) } ?: "",
                        beste?.scientificName ?: "",
                        beste?.let { String.format(Locale.US, "%.4f", it.confidence) } ?: ""
                    ))
                }
            }
            kurzErkannt = erkannt.toString()
            kurzTop1 = top1.toString()
            println("Kurztest: $erkannt von ${ERWARTET.size} mit erwarteter Art unter den Kandidaten, $top1 als beste Art")
        }

        // Zugesicherter virtueller Speicher des Prozesses (Heap + nativer Speicher), ohne neue Dependency.
        // Sicherer Cast: die Sun-Erweiterung ist nicht auf jeder JVM vorhanden.
        val prozessMb = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.committedVirtualMemorySize?.let { mb(it) } ?: ""

        waechter.stoppen()
        val csv = File(ausgabe, "benchmark.csv")
        if (!csv.exists()) {
            csv.writeText(
                "datei,dauer_s,samplerate,kanal,gain_db,import_ms,import_heap_max_mb,decode_ms,overview_ms,scan_ms,scan_rtf,scan_fenster,scan_gerechnet," +
                    "scan_treffer,suche_ms,suche2_ms,kurz_erkannt,kurz_top1,heap_max_mb,prozess_ende_mb\n"
            )
        }
        csv.appendText(String.format(
            Locale.US, "%s,%.1f,%d,%d,%.0f,%s,%s,%s,%s,%s,%.2f,%s,%s,%d,%s,%s,%s,%s,%s,%s\n",
            langdatei.name, segment.durationSec, segment.sampleRate, kanal, gainDb, ms(importNs), importHeapMb, ms(decodeNs), ms(overviewNs), ms(scanNs),
            rtf, stats?.totalChunks ?: "", stats?.classifiedChunks ?: "", treffer.size, sucheErst, sucheZweit,
            kurzErkannt, kurzTop1, mb(waechter.maxBytes), prozessMb
        ))
        println("Geschrieben: ${csv.absolutePath}")
    } finally {
        classifier.close()
    }
    exitProcess(0)
}
