const fs = require("fs");
const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
        Header, Footer, AlignmentType, LevelFormat,
        HeadingLevel, BorderStyle, WidthType, ShadingType,
        PageNumber, PageBreak, TabStopType, TabStopPosition } = require("docx");

// ── Farben ──
const BLUE = "2E5090";
const LIGHT_BLUE = "D5E8F0";
const DARK = "333333";
const GRAY = "666666";

// ── Hilfsfunktionen ──
function heading1(text) {
    return new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun({ text, bold: true, size: 32, font: "Arial", color: BLUE })] });
}
function heading2(text) {
    return new Paragraph({ heading: HeadingLevel.HEADING_2, spacing: { before: 200, after: 100 }, children: [new TextRun({ text, bold: true, size: 26, font: "Arial", color: BLUE })] });
}
function heading3(text) {
    return new Paragraph({ heading: HeadingLevel.HEADING_3, spacing: { before: 160, after: 80 }, children: [new TextRun({ text, bold: true, size: 22, font: "Arial", color: DARK })] });
}
function para(text, opts = {}) {
    return new Paragraph({ spacing: { after: 120 }, children: [new TextRun({ text, size: 20, font: "Arial", color: DARK, ...opts })] });
}
function paraRuns(runs) {
    return new Paragraph({ spacing: { after: 120 }, children: runs.map(r => typeof r === "string" ? new TextRun({ text: r, size: 20, font: "Arial", color: DARK }) : new TextRun({ size: 20, font: "Arial", color: DARK, ...r })) });
}
function emptyLine() {
    return new Paragraph({ spacing: { after: 60 }, children: [] });
}

const border = { style: BorderStyle.SINGLE, size: 1, color: "CCCCCC" };
const borders = { top: border, bottom: border, left: border, right: border };
const cellMargins = { top: 60, bottom: 60, left: 100, right: 100 };

function headerCell(text, width) {
    return new TableCell({
        borders, width: { size: width, type: WidthType.DXA },
        shading: { fill: LIGHT_BLUE, type: ShadingType.CLEAR },
        margins: cellMargins,
        children: [new Paragraph({ children: [new TextRun({ text, bold: true, size: 20, font: "Arial", color: DARK })] })]
    });
}
function cell(text, width) {
    return new TableCell({
        borders, width: { size: width, type: WidthType.DXA },
        margins: cellMargins,
        children: [new Paragraph({ children: [new TextRun({ text, size: 20, font: "Arial", color: DARK })] })]
    });
}

// ── Numbering-Config ──
const numbering = {
    config: [
        { reference: "bullets", levels: [{ level: 0, format: LevelFormat.BULLET, text: "\u2022", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
        { reference: "bullets2", levels: [{ level: 0, format: LevelFormat.BULLET, text: "\u2022", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
        { reference: "bullets3", levels: [{ level: 0, format: LevelFormat.BULLET, text: "\u2022", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
        { reference: "bullets4", levels: [{ level: 0, format: LevelFormat.BULLET, text: "\u2022", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
        { reference: "bullets5", levels: [{ level: 0, format: LevelFormat.BULLET, text: "\u2022", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
        { reference: "bullets6", levels: [{ level: 0, format: LevelFormat.BULLET, text: "\u2022", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
        { reference: "bullets7", levels: [{ level: 0, format: LevelFormat.BULLET, text: "\u2022", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
        { reference: "bullets8", levels: [{ level: 0, format: LevelFormat.BULLET, text: "\u2022", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
        { reference: "steps1", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
        { reference: "steps2", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
        { reference: "steps3", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
        { reference: "steps4", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
    ]
};

function bullet(text, ref = "bullets") {
    return new Paragraph({ numbering: { reference: ref, level: 0 }, spacing: { after: 60 }, children: [new TextRun({ text, size: 20, font: "Arial", color: DARK })] });
}
function bulletRuns(runs, ref = "bullets") {
    return new Paragraph({ numbering: { reference: ref, level: 0 }, spacing: { after: 60 }, children: runs.map(r => typeof r === "string" ? new TextRun({ text: r, size: 20, font: "Arial", color: DARK }) : new TextRun({ size: 20, font: "Arial", color: DARK, ...r })) });
}
function step(text, ref = "steps1") {
    return new Paragraph({ numbering: { reference: ref, level: 0 }, spacing: { after: 60 }, children: [new TextRun({ text, size: 20, font: "Arial", color: DARK })] });
}

// ── Shortcut-Tabelle ──
function shortcutTable(rows) {
    const colW = [3500, 5860];
    return new Table({
        width: { size: 9360, type: WidthType.DXA },
        columnWidths: colW,
        rows: [
            new TableRow({ children: [headerCell("Tastenkombination", colW[0]), headerCell("Funktion", colW[1])] }),
            ...rows.map(([k, v]) => new TableRow({ children: [cell(k, colW[0]), cell(v, colW[1])] }))
        ]
    });
}

// ══════════════════════════════════════════════════════
// DOKUMENT
// ══════════════════════════════════════════════════════

const doc = new Document({
    styles: {
        default: { document: { run: { font: "Arial", size: 20 } } },
        paragraphStyles: [
            { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true, run: { size: 32, bold: true, font: "Arial", color: BLUE }, paragraph: { spacing: { before: 240, after: 200 }, outlineLevel: 0 } },
            { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true, run: { size: 26, bold: true, font: "Arial", color: BLUE }, paragraph: { spacing: { before: 200, after: 120 }, outlineLevel: 1 } },
            { id: "Heading3", name: "Heading 3", basedOn: "Normal", next: "Normal", quickFormat: true, run: { size: 22, bold: true, font: "Arial", color: DARK }, paragraph: { spacing: { before: 160, after: 80 }, outlineLevel: 2 } },
        ]
    },
    numbering,
    sections: [
        // ── TITELSEITE ──
        {
            properties: {
                page: { size: { width: 11906, height: 16838 }, margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 } }
            },
            children: [
                emptyLine(), emptyLine(), emptyLine(), emptyLine(), emptyLine(),
                new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 200 }, children: [new TextRun({ text: "BirdSono", size: 72, bold: true, font: "Arial", color: BLUE })] }),
                new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 100 }, children: [new TextRun({ text: "Sonogramm-Vergleichstool", size: 36, font: "Arial", color: GRAY })] }),
                new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 40 }, children: [new TextRun({ text: "Version 0.0.1", size: 28, font: "Arial", color: GRAY })] }),
                emptyLine(), emptyLine(),
                new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 600 }, children: [new TextRun({ text: "Bedienungsanleitung", size: 32, font: "Arial", color: DARK })] }),
                emptyLine(), emptyLine(), emptyLine(), emptyLine(), emptyLine(), emptyLine(),
                new Paragraph({ alignment: AlignmentType.CENTER, border: { top: { style: BorderStyle.SINGLE, size: 6, color: BLUE, space: 8 } }, spacing: { before: 400 }, children: [new TextRun({ text: "Fuer die akustische Artbestimmung von Voegeln und Fledermaeuse", size: 20, font: "Arial", color: GRAY, italics: true })] }),
                new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 100 }, children: [new TextRun({ text: "Stand: Maerz 2026", size: 20, font: "Arial", color: GRAY })] }),
            ]
        },
        // ── INHALT ──
        {
            properties: {
                page: { size: { width: 11906, height: 16838 }, margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 } }
            },
            headers: {
                default: new Header({ children: [new Paragraph({
                    children: [
                        new TextRun({ text: "BirdSono v0.0.1 — Bedienungsanleitung", size: 16, font: "Arial", color: GRAY, italics: true }),
                    ],
                    tabStops: [{ type: TabStopType.RIGHT, position: TabStopPosition.MAX }],
                    border: { bottom: { style: BorderStyle.SINGLE, size: 1, color: "CCCCCC", space: 4 } }
                })] })
            },
            footers: {
                default: new Footer({ children: [new Paragraph({
                    alignment: AlignmentType.CENTER,
                    children: [new TextRun({ text: "Seite ", size: 16, font: "Arial", color: GRAY }), new TextRun({ children: [PageNumber.CURRENT], size: 16, font: "Arial", color: GRAY })]
                })] })
            },
            children: [
                // ════════════════════════════════════════
                // 1. EINFUEHRUNG
                // ════════════════════════════════════════
                heading1("1. Einfuehrung"),
                para("BirdSono ist ein Desktop-Werkzeug zur visuellen und akustischen Analyse von Vogelstimmen und Fledermausrufen. Die Software erzeugt Sonogramme (Spektrogramme) aus Audioaufnahmen und vergleicht diese automatisch mit einer Referenzdatenbank (Xeno-Canto)."),
                heading2("1.1 Hauptfunktionen"),
                bullet("Sonogramm-Darstellung: Mel-skalierte Spektrogramme mit konfigurierbarer Farbpalette", "bullets"),
                bullet("Frequenzbereich: Bis 16 kHz (Voegel) bzw. 125 kHz (Fledermaeuse)", "bullets"),
                bullet("Filter-Pipeline: Kontrast, Expander/Gate, Limiter, Bandpass, Median", "bullets"),
                bullet("Artbestimmung: MFCC-basierter Aehnlichkeitsvergleich (offline + online)", "bullets"),
                bullet("Ereigniserkennung: Automatische Detektion von Rufen/Gesaengen", "bullets"),
                bullet("Offline-Datenbank: Lokaler Cache von Xeno-Canto-Sonogrammen", "bullets"),
                bullet("Annotation: Markierung und Beschriftung von Zeitbereichen", "bullets"),
                bullet("Export: Annotierte Sonogramme als PNG-Bild", "bullets"),
                heading2("1.2 Systemvoraussetzungen"),
                bullet("Windows 10/11 (64-Bit)", "bullets2"),
                bullet("Mindestens 4 GB RAM (8 GB empfohlen fuer grosse Dateien)", "bullets2"),
                bullet("Java Runtime wird NICHT benoetigt (ist in der EXE enthalten)", "bullets2"),
                bullet("Internetverbindung fuer Online-Suche und Datenbank-Download (optional)", "bullets2"),
                heading2("1.3 Installation"),
                para("Fuehren Sie die Datei BirdSono-0.0.1.exe aus. Der Installer erstellt einen Eintrag im Startmenue unter 'BirdSono'. Nach der Installation kann BirdSono direkt gestartet werden."),

                new Paragraph({ children: [new PageBreak()] }),

                // ════════════════════════════════════════
                // 2. OBERFLAECHE
                // ════════════════════════════════════════
                heading1("2. Benutzeroberflaeche"),
                para("Die Oberflaeche ist in folgende Bereiche gegliedert (von oben nach unten):"),

                heading2("2.1 Werkzeugleiste (Toolbar)"),
                para("Am oberen Rand befinden sich die wichtigsten Aktionschalter:"),
                new Table({
                    width: { size: 9360, type: WidthType.DXA },
                    columnWidths: [2800, 6560],
                    rows: [
                        new TableRow({ children: [headerCell("Symbol", 2800), headerCell("Funktion", 6560)] }),
                        new TableRow({ children: [cell("Datei oeffnen", 2800), cell("Laedt eine Audiodatei (WAV, MP3, FLAC, OGG)", 6560)] }),
                        new TableRow({ children: [cell("Auswahl-Modus", 2800), cell("Aktiviert das Markierungswerkzeug im Sonogramm", 6560)] }),
                        new TableRow({ children: [cell("Annotation", 2800), cell("Erstellt eine benannte Annotation aus der Auswahl", 6560)] }),
                        new TableRow({ children: [cell("Zoom +/-", 2800), cell("Vergroessert/verkleinert den sichtbaren Zeitbereich", 6560)] }),
                        new TableRow({ children: [cell("Play / Pause / Stop", 2800), cell("Audiowiedergabe mit Positionsanzeige", 6560)] }),
                        new TableRow({ children: [cell("Filter", 2800), cell("Oeffnet/schliesst das Filter-Panel", 6560)] }),
                        new TableRow({ children: [cell("Auto-Detect", 2800), cell("Erkennt automatisch Klangereignisse", 6560)] }),
                        new TableRow({ children: [cell("Vergleichen", 2800), cell("Startet Aehnlichkeitssuche (Artbestimmung)", 6560)] }),
                        new TableRow({ children: [cell("Export", 2800), cell("Exportiert das Sonogramm als PNG", 6560)] }),
                        new TableRow({ children: [cell("API-Key", 2800), cell("Xeno-Canto API-Schluessel konfigurieren", 6560)] }),
                        new TableRow({ children: [cell("Download", 2800), cell("Offline-Datenbank herunterladen", 6560)] }),
                    ]
                }),

                heading2("2.2 Uebersichtsstreifen (Overview)"),
                para("Der schmale Streifen oben zeigt das gesamte Sonogramm im Ueberblick. Ein blauer Rahmen markiert den aktuell sichtbaren Ausschnitt (Viewport)."),
                heading3("Interaktion mit der Uebersicht"),
                bulletRuns([{ text: "Viewport verschieben: ", bold: true }, "Klicken und Ziehen innerhalb des blauen Rahmens. Das Detailbild wird waehrend des Ziehens eingefroren und erst nach dem Loslassen neu berechnet — so ist das Verschieben fluessig und ruckelfrei."], "bullets3"),
                bulletRuns([{ text: "Gummiband-Auswahl: ", bold: true }, "Klicken und Ziehen AUSSERHALB des Viewports spannt ein Gummiband auf. Beim Loslassen wird der markierte Zeitbereich zum neuen Viewport."], "bullets3"),
                bulletRuns([{ text: "Klick: ", bold: true }, "Ein Klick ausserhalb des Viewports zentriert diesen auf die angeklickte Position."], "bullets3"),

                heading2("2.3 Timeline"),
                para("Direkt unter der Uebersicht befindet sich die Timeline mit zwei Griffen (Handles):"),
                bulletRuns([{ text: "Linker/Rechter Griff: ", bold: true }, "Einzeln ziehbar — aendert Start bzw. Ende des sichtbaren Bereichs."], "bullets4"),
                bulletRuns([{ text: "Bereich: ", bold: true }, "Innerhalb der Griffe ziehen verschiebt den gesamten Viewport."], "bullets4"),
                para("Zeitangaben (Start, Ende, Bereichsdauer) werden automatisch oberhalb angezeigt."),

                heading2("2.4 Detail-Sonogramm (Zoomed View)"),
                para("Das grosse Hauptfenster zeigt den aktuellen Ausschnitt als hochaufgeloestes Mel-Spektrogramm. Hier werden auch Annotationen, erkannte Ereignisse und die Wiedergabeposition (roter Strich) dargestellt."),
                paraRuns([{ text: "Hinweis: ", bold: true, italics: true }, "Waehrend des Verschiebens in der Uebersicht oder Timeline bleibt das alte Bild stehen. Erst nach dem Loslassen wird das Spektrogramm fuer den neuen Ausschnitt berechnet."]),

                heading2("2.5 Annotations-Panel (links)"),
                para("Zeigt alle erstellten Annotationen als Liste. Jede Annotation enthaelt einen editierbaren Namen, den Zeitbereich und optional Suchergebnisse."),

                heading2("2.6 Ergebnis-Panel (unten)"),
                para("Nach einer Aehnlichkeitssuche werden die Treffer hier angezeigt — gruppiert nach Art. Jede Artgruppe zeigt den besten Treffer prominent, weitere Varianten sind aufklappbar."),

                new Paragraph({ children: [new PageBreak()] }),

                // ════════════════════════════════════════
                // 3. SCHNELLSTART
                // ════════════════════════════════════════
                heading1("3. Schnellstart"),
                para("So bestimmen Sie eine Vogelart in fuenf Schritten:"),

                step("Audiodatei laden: Klicken Sie auf 'Datei oeffnen' und waehlen Sie eine WAV-, MP3-, FLAC- oder OGG-Datei.", "steps1"),
                step("Zeitbereich waehlen: Navigieren Sie in der Uebersicht zum gewuenschten Ausschnitt. Nutzen Sie das Gummiband (ausserhalb des blauen Rahmens ziehen) um einen Ruf/Gesang einzugrenzen.", "steps1"),
                step("Optional: Annotation erstellen: Waehlen Sie im Detailbild einen Zeit-/Frequenzbereich aus und klicken Sie auf 'Annotation'.", "steps1"),
                step("Vergleichen: Klicken Sie auf 'Vergleichen'. BirdSono durchsucht die Offline-Datenbank (und bei Bedarf Xeno-Canto online) nach aehnlichen Sonogrammen.", "steps1"),
                step("Ergebnis pruefen: Im Ergebnis-Panel erscheinen die Treffer nach Art gruppiert. Klicken Sie auf einen Treffer, um das Referenz-Sonogramm daneben anzuzeigen.", "steps1"),

                new Paragraph({ children: [new PageBreak()] }),

                // ════════════════════════════════════════
                // 4. FILTER
                // ════════════════════════════════════════
                heading1("4. Filter-Pipeline"),
                para("BirdSono bietet eine mehrstufige Filter-Pipeline, die das Sonogramm in Echtzeit veraendert. Alle Filter wirken sich direkt auf die Darstellung aus (Live-Vorschau mit 300ms Verzoegerung). Die Filter werden in folgender Reihenfolge angewendet:"),

                heading2("4.1 Kontrast (Spektrale Subtraktion)"),
                para("Schaetzt das Grundrauschen aus den ersten Frames und subtrahiert es. Erhoet den Kontrast zwischen Signal und Hintergrund."),
                bulletRuns([{ text: "Staerke: ", bold: true }, "0.5 (sanft) bis 3.0 (aggressiv)"], "bullets5"),

                heading2("4.2 Expander / Gate"),
                para("Reduziert leise Hintergrundsignale und hebt laute Vordergrund-Ereignisse hervor."),
                new Table({
                    width: { size: 9360, type: WidthType.DXA },
                    columnWidths: [2500, 6860],
                    rows: [
                        new TableRow({ children: [headerCell("Parameter", 2500), headerCell("Beschreibung", 6860)] }),
                        new TableRow({ children: [cell("Modus", 2500), cell("EXPANDER: sanfte Absenkung unter Schwelle. GATE: harte Stummschaltung.", 6860)] }),
                        new TableRow({ children: [cell("Schwelle (Threshold)", 2500), cell("-40 dB bis +16 dB (0.5 dB Schritte). Signale unter diesem Pegel werden abgesenkt.", 6860)] }),
                        new TableRow({ children: [cell("Ratio", 2500), cell("Verhaeltnis der Absenkung (2:1 bis 20:1). Hoeher = aggressiver.", 6860)] }),
                        new TableRow({ children: [cell("Range (Tiefe)", 2500), cell("Maximale Absenkung in dB (0 bis -120 dB). Begrenzt wie weit leise Signale gedrueckt werden.", 6860)] }),
                        new TableRow({ children: [cell("Knee", 2500), cell("Uebergangsbreite (0 = hart, 12 = weich). Bestimmt wie abrupt der Uebergang ist.", 6860)] }),
                        new TableRow({ children: [cell("Hysterese", 2500), cell("Unterschiedliche Oeffnungs-/Schliessschwellen (0-6 dB). Verhindert Flattern.", 6860)] }),
                        new TableRow({ children: [cell("Attack/Release/Hold", 2500), cell("Erweiterte Zeitparameter (unter 'Erweitert'). Steuern wie schnell das Gate reagiert.", 6860)] }),
                    ]
                }),

                heading2("4.3 Limiter"),
                para("Harter Begrenzer: Alles ueber dem Ceiling-Wert wird abgeschnitten. Nuetzlich um uebermaessig laute Impulse (Windstoss, Klick, Schlag) aus dem Sonogramm zu entfernen."),
                bulletRuns([{ text: "Ceiling: ", bold: true }, "0 dB (kein Limit) bis -40 dB (aggressiv). Relativ zum Maximalwert im Spektrogramm."], "bullets6"),

                heading2("4.4 Bandpass"),
                para("Frequenzfilter mit logarithmischen Schiebereglern. Nur Signale innerhalb des eingestellten Frequenzbands werden angezeigt."),
                bulletRuns([{ text: "Low: ", bold: true }, "Untere Grenzfrequenz (z.B. 200 Hz — filtert Windgeraeusche)"], "bullets7"),
                bulletRuns([{ text: "High: ", bold: true }, "Obere Grenzfrequenz (z.B. 8000 Hz). Bis 16 kHz fuer Voegel, bis 125 kHz fuer Fledermaeuse."], "bullets7"),
                para("Die naechstliegende musikalische Note wird neben jeder Frequenz angezeigt (z.B. '440 Hz (A4)')."),

                heading2("4.5 Median-Filter"),
                para("2D-Medianfilter zur Glaettung. Entfernt einzelne Stoerpixel. Kerngroesse 3 (sanft) bis 7 (stark)."),

                heading2("4.6 Filter-Presets"),
                para("Filtereinstellungen koennen als benannte Presets gespeichert und jederzeit wieder geladen werden:"),
                step("Stellen Sie die gewuenschten Filter ein.", "steps2"),
                step("Klicken Sie auf 'Speichern' in der Preset-Leiste.", "steps2"),
                step("Geben Sie einen Namen ein (z.B. 'Singvoegel Standard', 'Fledermaus Nacht').", "steps2"),
                step("Zum Laden waehlen Sie das Preset aus dem Dropdown.", "steps2"),
                para("Presets werden dauerhaft in den Einstellungen gespeichert und stehen nach einem Neustart wieder zur Verfuegung."),

                new Paragraph({ children: [new PageBreak()] }),

                // ════════════════════════════════════════
                // 5. ARTBESTIMMUNG
                // ════════════════════════════════════════
                heading1("5. Artbestimmung"),

                heading2("5.1 Ablauf"),
                para("BirdSono vergleicht Audioausschnitte ueber MFCC-Merkmale (Mel-Frequency Cepstral Coefficients) — ein in der Bioakustik bewaehrtes Verfahren:"),
                step("MFCC-Vektor wird aus dem ausgewaehlten Bereich extrahiert.", "steps3"),
                step("Der Vektor wird per Kosinus-Aehnlichkeit mit der lokalen Datenbank verglichen.", "steps3"),
                step("Falls kein Offline-Treffer: Xeno-Canto wird online durchsucht.", "steps3"),
                step("Ergebnisse werden nach Art gruppiert, bester Treffer pro Art zuerst.", "steps3"),

                heading2("5.2 Offline-Datenbank"),
                para("Fuer schnelle, Internet-unabhaengige Bestimmung kann eine lokale Sonogramm-Datenbank heruntergeladen werden:"),
                step("Klicken Sie auf das Download-Symbol (Wolke) in der Toolbar.", "steps4"),
                step("Waehlen Sie die gewuenschten Arten aus der Liste (73 europaeische Vogelarten vorbelegt).", "steps4"),
                step("Setzen Sie die maximale Anzahl pro Art oder waehlen Sie 'Alle verfuegbaren'.", "steps4"),
                step("Starten Sie den Download. Der Fortschritt wird angezeigt.", "steps4"),
                para("Die Datenbank wird unter %APPDATA%/BirdSono/cache/ gespeichert. Nur Sonogramm-Bilder werden heruntergeladen (keine Audiodaten), was den Speicherbedarf gering haelt."),

                heading2("5.3 Xeno-Canto API"),
                para("Fuer die Online-Suche wird ein kostenloser API-Schluessel von xeno-canto.org benoetigt:"),
                step("Registrieren Sie sich auf https://xeno-canto.org", "steps1"),
                step("Fordern Sie unter API-Einstellungen einen Schluessel an.", "steps1"),
                step("Geben Sie den Schluessel ueber das Schluessel-Symbol in der Toolbar ein.", "steps1"),
                para("Der Schluessel wird dauerhaft in den lokalen Einstellungen gespeichert."),

                heading2("5.4 Ergebnisse lesen"),
                para("Treffer werden im Ergebnis-Panel nach Art gruppiert angezeigt:"),
                bulletRuns([{ text: "Hauptkarte: ", bold: true }, "Bester Treffer pro Art mit Artname, Aehnlichkeit (%), Qualitaet und Herkunftsland."], "bullets8"),
                bulletRuns([{ text: "Varianten-Badge: ", bold: true }, "Zeigt die Anzahl weiterer Treffer derselben Art. Klick klappt die Varianten auf."], "bullets8"),
                bulletRuns([{ text: "Referenz-Sonogramm: ", bold: true }, "Beim Anklicken eines Treffers wird das zugehoerige Referenz-Sonogramm angezeigt."], "bullets8"),

                new Paragraph({ children: [new PageBreak()] }),

                // ════════════════════════════════════════
                // 6. EREIGNISERKENNUNG
                // ════════════════════════════════════════
                heading1("6. Automatische Ereigniserkennung"),
                para("Die Auto-Detect-Funktion erkennt Klangereignisse (Rufe, Gesaenge) im aktuellen Sichtbereich basierend auf Energieanalyse:"),
                bullet("Berechnet RMS-Energie pro Zeitframe", "bullets"),
                bullet("Gleitender Durchschnitt als Basislinie", "bullets"),
                bullet("Signale ueber dem Schwellenwert werden als Ereignis markiert", "bullets"),
                bullet("Mindestdauer 50ms, Maximaldauer 10s", "bullets"),
                emptyLine(),
                para("Erkannte Ereignisse werden als farbige Kaesten im Sonogramm dargestellt. Um die erkannten Ereignisse zu bestimmen, klicken Sie anschliessend auf 'Vergleichen'."),

                new Paragraph({ children: [new PageBreak()] }),

                // ════════════════════════════════════════
                // 7. WIEDERGABE
                // ════════════════════════════════════════
                heading1("7. Audiowiedergabe"),
                para("BirdSono kann den geladenen Audioausschnitt abspielen:"),
                bullet("Play: Startet die Wiedergabe ab der aktuellen Position.", "bullets2"),
                bullet("Pause: Haelt an der aktuellen Position an.", "bullets2"),
                bullet("Stop: Setzt auf den Anfang zurueck.", "bullets2"),
                para("Die aktuelle Wiedergabeposition wird sowohl im Uebersichtsstreifen (roter Strich mit Dreieck) als auch im Detail-Sonogramm (roter Strich mit Dreiecken oben und unten) angezeigt."),

                new Paragraph({ children: [new PageBreak()] }),

                // ════════════════════════════════════════
                // 8. FLEDERMAUS-MODUS
                // ════════════════════════════════════════
                heading1("8. Fledermaus-Modus"),
                para("BirdSono unterstuetzt auch die Analyse von Fledermausrufen. Im Fledermaus-Modus wird der Frequenzbereich automatisch auf 125 kHz erweitert. Die Mel-Skalierung und MFCC-Parameter werden entsprechend angepasst."),
                para("Der Modus wird automatisch erkannt, wenn die Abtastrate der Audiodatei ueber 96 kHz liegt (typisch fuer Ultraschall-Detektoren)."),

                new Paragraph({ children: [new PageBreak()] }),

                // ════════════════════════════════════════
                // 9. TIPPS
                // ════════════════════════════════════════
                heading1("9. Tipps und Hinweise"),

                heading2("9.1 Optimale Aufnahmequalitaet"),
                bullet("Verwenden Sie Aufnahmen mit moeglichst wenig Hintergrundgeraeuschen.", "bullets3"),
                bullet("Qualitaet A oder B auf Xeno-Canto liefert die zuverlaessigsten Vergleichsergebnisse.", "bullets3"),
                bullet("WAV-Dateien (unkomprimiert) liefern bessere Ergebnisse als MP3.", "bullets3"),

                heading2("9.2 Filter-Empfehlungen"),
                new Table({
                    width: { size: 9360, type: WidthType.DXA },
                    columnWidths: [2500, 6860],
                    rows: [
                        new TableRow({ children: [headerCell("Situation", 2500), headerCell("Empfohlene Filter", 6860)] }),
                        new TableRow({ children: [cell("Wind/Rauschen", 2500), cell("Kontrast (1.5-2.0) + Bandpass Low 200 Hz + Expander -10 dB", 6860)] }),
                        new TableRow({ children: [cell("Verkehrslaerm", 2500), cell("Bandpass Low 1500 Hz + Expander Gate-Modus", 6860)] }),
                        new TableRow({ children: [cell("Laute Impulse", 2500), cell("Limiter Ceiling -10 dB", 6860)] }),
                        new TableRow({ children: [cell("Allgemein Voegel", 2500), cell("Bandpass 500-12000 Hz + Kontrast 1.5", 6860)] }),
                        new TableRow({ children: [cell("Fledermaeuse", 2500), cell("Bandpass 15000-80000 Hz + Expander -15 dB", 6860)] }),
                    ]
                }),

                heading2("9.3 Bedienung Uebersicht/Timeline"),
                bullet("Ziehen Sie den Viewport in der Uebersicht frei hin und her — das Detailbild wird erst nach dem Loslassen aktualisiert.", "bullets4"),
                bullet("Nutzen Sie das Gummiband (ausserhalb des blauen Bereichs ziehen) um schnell einen neuen Zeitbereich zu waehlen.", "bullets4"),
                bullet("In der Timeline koennen Sie beide Griffe einzeln oder den gesamten Bereich verschieben.", "bullets4"),

                new Paragraph({ children: [new PageBreak()] }),

                // ════════════════════════════════════════
                // 10. DATEIFORMATE
                // ════════════════════════════════════════
                heading1("10. Unterstuetzte Dateiformate"),
                new Table({
                    width: { size: 9360, type: WidthType.DXA },
                    columnWidths: [1800, 3000, 4560],
                    rows: [
                        new TableRow({ children: [headerCell("Format", 1800), headerCell("Erweiterung", 3000), headerCell("Hinweise", 4560)] }),
                        new TableRow({ children: [cell("WAV", 1800), cell(".wav", 3000), cell("Unkomprimiert, beste Qualitaet. Empfohlen.", 4560)] }),
                        new TableRow({ children: [cell("MP3", 1800), cell(".mp3", 3000), cell("Komprimiert, haeufig bei Xeno-Canto.", 4560)] }),
                        new TableRow({ children: [cell("FLAC", 1800), cell(".flac", 3000), cell("Verlustfrei komprimiert. Gute Qualitaet bei kleiner Datei.", 4560)] }),
                        new TableRow({ children: [cell("OGG Vorbis", 1800), cell(".ogg", 3000), cell("Komprimiert, offenes Format.", 4560)] }),
                    ]
                }),

                new Paragraph({ children: [new PageBreak()] }),

                // ════════════════════════════════════════
                // 11. DATENSPEICHERUNG
                // ════════════════════════════════════════
                heading1("11. Datenspeicherung"),
                para("BirdSono speichert alle Daten lokal auf Ihrem Computer:"),
                new Table({
                    width: { size: 9360, type: WidthType.DXA },
                    columnWidths: [4000, 5360],
                    rows: [
                        new TableRow({ children: [headerCell("Pfad", 4000), headerCell("Inhalt", 5360)] }),
                        new TableRow({ children: [cell("%APPDATA%/BirdSono/settings.json", 4000), cell("Einstellungen, API-Key, Filter-Presets", 5360)] }),
                        new TableRow({ children: [cell("%APPDATA%/BirdSono/cache/sono/", 4000), cell("Heruntergeladene Referenz-Sonogramme", 5360)] }),
                        new TableRow({ children: [cell("%APPDATA%/BirdSono/cache/index.json", 4000), cell("Index der Offline-Datenbank", 5360)] }),
                    ]
                }),

                emptyLine(),

                // ════════════════════════════════════════
                // 12. FEHLERBEHEBUNG
                // ════════════════════════════════════════
                heading1("12. Fehlerbehebung"),
                new Table({
                    width: { size: 9360, type: WidthType.DXA },
                    columnWidths: [3500, 5860],
                    rows: [
                        new TableRow({ children: [headerCell("Problem", 3500), headerCell("Loesung", 5860)] }),
                        new TableRow({ children: [cell("Kein Ton bei Wiedergabe", 3500), cell("Pruefen Sie die Windows-Audioausgabe. BirdSono nutzt die Standard-Audioausgabe.", 5860)] }),
                        new TableRow({ children: [cell("Online-Suche schlaegt fehl", 3500), cell("Pruefen Sie den API-Key und die Internetverbindung. Der Key muss gueltig sein.", 5860)] }),
                        new TableRow({ children: [cell("Sonogramm ist leer/schwarz", 3500), cell("Erhoehen Sie den Kontrast-Filter oder pruefen Sie den Bandpass-Bereich.", 5860)] }),
                        new TableRow({ children: [cell("Grosse Datei laedt langsam", 3500), cell("Normal bei Dateien >5 Minuten. Das Spektrogramm wird chunked berechnet.", 5860)] }),
                        new TableRow({ children: [cell("Kein Ergebnis bei Vergleich", 3500), cell("Stellen Sie sicher, dass eine Annotation oder ein Zoom-Bereich aktiv ist.", 5860)] }),
                    ]
                }),

                emptyLine(), emptyLine(),

                new Paragraph({ alignment: AlignmentType.CENTER, border: { top: { style: BorderStyle.SINGLE, size: 4, color: BLUE, space: 8 } }, spacing: { before: 400 }, children: [new TextRun({ text: "BirdSono v0.0.1 — Maerz 2026", size: 18, font: "Arial", color: GRAY, italics: true })] }),
            ]
        }
    ]
});

// ── Generieren ──
Packer.toBuffer(doc).then(buffer => {
    fs.writeFileSync("D:/80002/birdsono/docs/BirdSono_Bedienungsanleitung.docx", buffer);
    console.log("OK: BirdSono_Bedienungsanleitung.docx geschrieben (" + buffer.length + " Bytes)");
});
