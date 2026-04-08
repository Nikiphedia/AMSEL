package ch.etasystems.amsel.data

import ch.etasystems.amsel.core.filter.ExpanderGate
import ch.etasystems.amsel.core.filter.FilterConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Ein gespeichertes Filter-Preset (Job).
 * Enthaelt alle FilterConfig-Parameter als serialisierbare Felder.
 */
@Serializable
data class FilterPreset(
    val name: String,
    // Globaler Bypass
    val bypass: Boolean = false,
    // Noise-Filter
    val noiseFilter: Boolean = false,
    val noiseFilterPercent: Float = 30f,
    // Legacy Kontrast
    val spectralSubtraction: Boolean = false,
    val spectralSubtractionAlpha: Float = 1.5f,
    val noiseEstimationFrames: Int = 10,
    // Expander/Gate
    val expanderGate: Boolean = false,
    val expanderThreshold: Float = 0f,
    val expanderRatio: Float = 2f,
    val expanderModeGate: Boolean = false,  // true = GATE, false = EXPANDER
    val expanderRangeDb: Float = -80f,
    val expanderKneeDb: Float = 0f,
    val expanderHysteresisDb: Float = 0f,
    val expanderAttackFrames: Int = 0,
    val expanderReleaseFrames: Int = 0,
    val expanderHoldFrames: Int = 0,
    // Limiter
    val limiter: Boolean = false,
    val limiterThresholdDb: Float = 0f,
    // Bandpass
    val bandpass: Boolean = false,
    val bandpassLowHz: Float = 1000f,
    val bandpassHighHz: Float = 16000f,
    // Median
    val medianFilter: Boolean = false,
    val medianKernelSize: Int = 3,
    // Spectral Gating
    val spectralGating: Boolean = false,
    val spectralGatingSensitivity: Float = 1.5f,
    val spectralGatingThresholdDb: Float = -30f,
    val spectralGatingSoftness: Float = 2f,
    // Normalisierung
    val normalize: Boolean = false,
    val normalizeGainLog10: Float = 0f
) {
    /** Konvertiert Preset in FilterConfig */
    fun toFilterConfig(): FilterConfig = FilterConfig(
        bypass = bypass,
        noiseFilter = noiseFilter,
        noiseFilterPercent = noiseFilterPercent,
        spectralSubtraction = spectralSubtraction,
        spectralSubtractionAlpha = spectralSubtractionAlpha,
        noiseEstimationFrames = noiseEstimationFrames,
        expanderGate = expanderGate,
        expanderThreshold = expanderThreshold,
        expanderRatio = expanderRatio,
        expanderMode = if (expanderModeGate) ExpanderGate.Mode.GATE else ExpanderGate.Mode.EXPANDER,
        expanderRangeDb = expanderRangeDb,
        expanderKneeDb = expanderKneeDb,
        expanderHysteresisDb = expanderHysteresisDb,
        expanderAttackFrames = expanderAttackFrames,
        expanderReleaseFrames = expanderReleaseFrames,
        expanderHoldFrames = expanderHoldFrames,
        limiter = limiter,
        limiterThresholdDb = limiterThresholdDb,
        bandpass = bandpass,
        bandpassLowHz = bandpassLowHz,
        bandpassHighHz = bandpassHighHz,
        medianFilter = medianFilter,
        medianKernelSize = medianKernelSize,
        spectralGating = spectralGating,
        spectralGatingSensitivity = spectralGatingSensitivity,
        spectralGatingThresholdDb = spectralGatingThresholdDb,
        spectralGatingSoftness = spectralGatingSoftness,
        normalize = normalize,
        normalizeGainLog10 = normalizeGainLog10
    )

    companion object {
        /** Erstellt Preset aus aktuellem FilterConfig */
        fun fromFilterConfig(name: String, config: FilterConfig): FilterPreset = FilterPreset(
            name = name,
            bypass = config.bypass,
            noiseFilter = config.noiseFilter,
            noiseFilterPercent = config.noiseFilterPercent,
            spectralSubtraction = config.spectralSubtraction,
            spectralSubtractionAlpha = config.spectralSubtractionAlpha,
            noiseEstimationFrames = config.noiseEstimationFrames,
            expanderGate = config.expanderGate,
            expanderThreshold = config.expanderThreshold,
            expanderRatio = config.expanderRatio,
            expanderModeGate = config.expanderMode == ExpanderGate.Mode.GATE,
            expanderRangeDb = config.expanderRangeDb,
            expanderKneeDb = config.expanderKneeDb,
            expanderHysteresisDb = config.expanderHysteresisDb,
            expanderAttackFrames = config.expanderAttackFrames,
            expanderReleaseFrames = config.expanderReleaseFrames,
            expanderHoldFrames = config.expanderHoldFrames,
            limiter = config.limiter,
            limiterThresholdDb = config.limiterThresholdDb,
            bandpass = config.bandpass,
            bandpassLowHz = config.bandpassLowHz,
            bandpassHighHz = config.bandpassHighHz,
            medianFilter = config.medianFilter,
            medianKernelSize = config.medianKernelSize,
            spectralGating = config.spectralGating,
            spectralGatingSensitivity = config.spectralGatingSensitivity,
            spectralGatingThresholdDb = config.spectralGatingThresholdDb,
            spectralGatingSoftness = config.spectralGatingSoftness,
            normalize = config.normalize,
            normalizeGainLog10 = config.normalizeGainLog10
        )
    }
}

/**
 * Vergleichs-Algorithmus fuer die Aehnlichkeitssuche.
 */
@Serializable
enum class ComparisonAlgorithm {
    MFCC_BASIC,        // Aktuell: 26-dim summary + cosine (schnell)
    MFCC_DTW,          // Enhanced: Frame-Level MFCC+Delta, DTW (genauer)
    ONNX_EFFICIENTNET, // EfficientNet mel-spectrogram
    EMBEDDING,         // BirdNET-Embedding oder MFCC-Pseudo-Embedding + Vektor-Suche
    BIRDNET,           // BirdNET V2.4 via Python-Bridge (6000+ Arten, TFLite)
    BIRDNET_V3         // BirdNET V3.0 via ONNX Runtime (11000+ Arten, 32kHz)
}

/**
 * Persistente App-Einstellungen.
 * Wird als JSON in %APPDATA%/AMSEL/settings.json gespeichert.
 */
@Serializable
data class AppSettings(
    val xenoCantoApiKey: String = "",
    val filterPresets: List<FilterPreset> = emptyList(),
    /** Maximale Frequenz fuer Vogel-Spektrogramme in Hz (Standard 16000) */
    val maxFrequencyHz: Int = 16000,
    /** Export: Untere Grenzfrequenz in Hz (Standard 0) */
    val exportFreqMinHz: Int = 0,
    /** Export: Obere Grenzfrequenz in Hz (Standard 16000) */
    val exportFreqMaxHz: Int = 16000,
    /** Export: Schrittweite Frequenz-Achse in Hz (Standard 2000 = 2kHz pro cm) */
    val exportFreqStepHz: Int = 2000,
    /** Export: Sekunden pro cm auf der Zeitachse (Standard ~1.82 = 55mm/sec) */
    val exportSecPerCm: Float = 1.818f,
    /** Export: Zeilenlaenge in cm (Standard 19.25 = 3.5sec bei 5.5cm/sec) */
    val exportRowLengthCm: Float = 19.25f,
    /** Vergleichs-Algorithmus fuer Aehnlichkeitssuche */
    val comparisonAlgorithm: ComparisonAlgorithm = ComparisonAlgorithm.MFCC_BASIC,
    /** Globaler Standort: Breitengrad (-1 = deaktiviert) */
    val locationLat: Float = 47.4f,
    /** Globaler Standort: Laengengrad (-1 = deaktiviert) */
    val locationLon: Float = 8.5f,
    /** Globaler Standort: Ortsbezeichnung (nur zur Anzeige) */
    val locationName: String = "Schweiz",
    /** BirdNET: Minimale Konfidenz (0.0-1.0) */
    val birdnetMinConf: Float = 0.1f,
    /** BirdNET: Bearbeitetes Material analysieren (Filter + Volume Envelope angewendet) */
    val birdnetUseFiltered: Boolean = true,
    /** Event-Klick: Vorlauf in Sekunden (wie viel vor dem Event angezeigt wird) */
    val eventPrerollSec: Float = 10f,
    /** Event-Klick: Nachlauf in Sekunden (wie viel nach dem Event angezeigt wird) */
    val eventPostrollSec: Float = 20f,
    /** Sprache fuer Art-Namen: EN (Englisch), DE (Deutsch), SCIENTIFIC (Latein) */
    val speciesLanguage: String = "DE",
    /** Wissenschaftliche Namen IMMER anzeigen (default: true — wissenschaftlicher Standard) */
    val showScientificNames: Boolean = true,
    /** Bearbeiter-Name fuer Export-Metadaten (PNG tEXt-Chunk AMSEL:Operator) */
    val operatorName: String = "",
    /** Aufnahmegeraet fuer Export-Metadaten (PNG tEXt-Chunk AMSEL:Device) */
    val deviceName: String = "",
    /** Kurze Aufnahmen: Mindestanzeigedauer in Sekunden (Anzeige wird auf diese Laenge gestreckt) */
    val minDisplayDurationSec: Float = 10f,
    /** Kurze Aufnahmen: Mindestexportdauer in Sekunden */
    val minExportDurationSec: Float = 10f,
    /** Kurze Aufnahmen: Startposition der Datei im Fenster (0.0=links, 0.5=mitte, 1.0=rechts) */
    val shortFileStartPct: Float = 0.1f,
    /** UI: Seitenleisten-Breite in dp */
    val sidebarWidth: Float = 280f,
    /** UI: Galerie-Hoehe in dp */
    val galleryHeight: Float = 250f,
    /** UI: Fenster-Breite in dp (0 = Standard 1400) */
    val windowWidth: Int = 0,
    /** UI: Fenster-Hoehe in dp (0 = Standard 900) */
    val windowHeight: Int = 0,
    /** UI: Fenster X-Position (-1 = System-Standard) */
    val windowX: Int = -1,
    /** UI: Fenster Y-Position (-1 = System-Standard) */
    val windowY: Int = -1,
    /** Chunk-Laenge fuer grosse Dateien in Minuten (Standard 10) */
    val chunkLengthMin: Float = 10f,
    /** Chunk-Ueberlappung in Sekunden (Standard 5) */
    val chunkOverlapSec: Float = 5f,
    /** Zuletzt geoeffnetes Projekt (Pfad zur .amsel.json, leer = keins) */
    val lastProjectPath: String = "",
    /** Aktives ONNX-Modell (Dateiname im models/-Ordner) */
    val activeModelFilename: String = "birdnet_v3.onnx",
    /** Aktives Artenset fuer Download und Klassifikation (ID aus region_sets.json) */
    val activeRegionSet: String = "all",
    /** Minimale Qualitaet fuer XC-Downloads (A=beste, E=schlechteste) */
    val referenceMinQualityDownload: String = "B",
    /** Minimale Qualitaet fuer Referenzanzeige */
    val referenceMinQualityDisplay: String = "C",
    /** Ordner fuer importierte Audio-Dateien (leer = ~/Documents/AMSEL/audio/) */
    val audioImportDir: String = "",
    /** Ordner fuer Projekt-Dateien (leer = neben Audio-Datei, bisheriges Verhalten) */
    val projectDir: String = "",
    /** Ordner fuer Exporte (leer = System-Desktop) */
    val exportDir: String = "",
    /** Ordner fuer ONNX-Modelle + Python-Skripte (leer = ~/Documents/AMSEL/models/) */
    val modelDir: String = "",
    /** Setup-Assistent wurde abgeschlossen */
    val setupComplete: Boolean = false
)

/** Gibt den aufgeloesten Modell-Ordner zurueck (Setting oder Default). */
fun AppSettings.resolvedModelDir(): File {
    return if (modelDir.isNotBlank()) File(modelDir)
    else File(System.getProperty("user.home"), "Documents/AMSEL/models")
}

/** Gibt den aufgeloesten Audio-Import-Ordner zurueck (Setting oder Default). */
fun AppSettings.resolvedAudioImportDir(): File {
    return if (audioImportDir.isNotBlank()) File(audioImportDir)
    else File(System.getProperty("user.home"), "Documents/AMSEL/audio")
}

/** Gibt den aufgeloesten Projekt-Ordner zurueck, oder null (= neben Audio-Datei). */
fun AppSettings.resolvedProjectDir(): File? {
    return if (projectDir.isNotBlank()) File(projectDir) else null
}

/** Gibt den aufgeloesten Export-Ordner zurueck, oder null (= System-Standard). */
fun AppSettings.resolvedExportDir(): File? {
    return if (exportDir.isNotBlank()) File(exportDir) else null
}

object SettingsStore {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val settingsDir: File by lazy {
        // Sichtbarer Ordner in Dokumente (nicht verstecktes AppData)
        val userHome = System.getProperty("user.home")
        File(userHome, "Documents/AMSEL").also { it.mkdirs() }
    }

    private val settingsFile: File get() = File(settingsDir, "settings.json")

    fun load(): AppSettings {
        return try {
            if (settingsFile.exists()) {
                json.decodeFromString(settingsFile.readText())
            } else {
                AppSettings()
            }
        } catch (_: Exception) {
            AppSettings()
        }
    }

    fun save(settings: AppSettings) {
        try {
            settingsFile.writeText(json.encodeToString(AppSettings.serializer(), settings))
        } catch (_: Exception) {
            // Stille Fehlerbehandlung — Settings sind nicht kritisch
        }
    }

    // ================================================================
    // Preset-Komfort-Methoden
    // ================================================================

    fun loadPresets(): List<FilterPreset> = load().filterPresets

    fun savePreset(preset: FilterPreset) {
        val settings = load()
        // Vorhandenes Preset mit gleichem Namen ersetzen
        val updated = settings.filterPresets.filter { it.name != preset.name } + preset
        save(settings.copy(filterPresets = updated))
    }

    fun deletePreset(name: String) {
        val settings = load()
        save(settings.copy(filterPresets = settings.filterPresets.filter { it.name != name }))
    }
}
