import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

group = "ch.etasystems.amsel"
version = "0.0.9"

dependencies {
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing)

    // HTTP Client (Xeno-Canto API)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)

    // DSP
    implementation(libs.jtransforms)

    // Audio Decode (MP3 + FLAC + AAC/M4A)
    implementation(libs.jflac)
    implementation(libs.mp3spi)
    implementation(libs.jcodec)

    // ONNX Runtime (EfficientNet Embedding)
    implementation(libs.onnxruntime)

    // PDF-Export (U4)
    implementation(libs.pdfbox)

    // Logging
    implementation(libs.slf4j.simple)

    // Tests (nur Testklassenpfad, nicht im Installer)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Messlauf fuer Import, Scan und Suche: gradlew benchmark --args="<langdatei> [<kurz-ordner>]"
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Misst Dekodierung, Uebersicht, BirdNET-Scan und Aehnlichkeitssuche an einer Audiodatei"
    mainClass.set("ch.etasystems.amsel.benchmark.BenchmarkKt")
    classpath = sourceSets["test"].runtimeClasspath
}

compose.desktop {
    application {
        mainClass = "ch.etasystems.amsel.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "AMSEL"
            packageVersion = "0.0.9"
            description = "Sonogramm-Vergleichstool für Vögel und Fledermäuse"
            vendor = "ETA Systems"

            windows {
                menuGroup = "AMSEL"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }
    }
}
