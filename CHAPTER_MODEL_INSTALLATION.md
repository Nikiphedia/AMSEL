# Modelle installieren

AMSEL benötigt ein Klassifikationsmodell um Tierlaute zu erkennen. Aktuell werden zwei BirdNET-Modelle unterstützt.

---

## Option 1: BirdNET V3.0 ONNX (empfohlen)

Nativ in AMSEL integriert — kein Python nötig. Erkennt 11'560 Arten (Vögel, Insekten, Amphibien, Säugetiere).

> **Hinweis:** BirdNET V3.0 ist eine Developer Preview (Beta). Die Erkennung ist bereits sehr gut, kann sich aber in zukünftigen Versionen noch ändern.

### Download

1. **Modell herunterladen** von Zenodo:
   - https://doi.org/10.5281/zenodo.18247420
   - Datei: `BirdNET+_V3.0-preview3_Global_11K_FP32.onnx` (516 MB)
   - Oder kleiner: `BirdNET+_V3.0-preview3_Global_11K_FP16.onnx` (259 MB, gleiche Genauigkeit)

2. **Labels herunterladen** vom selben GitHub-Repo:
   - https://github.com/birdnet-team/birdnet-V3.0-dev
   - Datei: `BirdNET+_V3.0-preview3_Global_11K_Labels.csv`

### Installation

3. **Dateien ablegen unter:**
   ```
   C:\Users\[DEIN-NAME]\Documents\AMSEL\models\
   ```

   In PowerShell:
   ```powershell
   # Ordner erstellen (falls nötig)
   mkdir "$env:USERPROFILE\Documents\AMSEL\models" -Force

   # Dateien verschieben (Beispiel aus Downloads-Ordner)
   Move-Item "$env:USERPROFILE\Downloads\BirdNET+_V3.0-preview3_Global_11K_FP32.onnx" "$env:USERPROFILE\Documents\AMSEL\models\"
   Move-Item "$env:USERPROFILE\Downloads\BirdNET+_V3.0-preview3_Global_11K_Labels.csv" "$env:USERPROFILE\Documents\AMSEL\models\"
   ```

4. **AMSEL starten** — das Modell wird automatisch erkannt.

5. **Prüfen:** Einstellungen → Tab "Analyse" → BirdNET V3.0 sollte als Option erscheinen. Falls nicht: Einstellungen → "Modelle verwalten..." → prüfen ob die Datei korrekt erkannt wurde.

### FP32 vs FP16

| Variante | Dateigrösse | Genauigkeit | RAM-Bedarf |
|----------|------------|-------------|------------|
| FP32 | 516 MB | Referenz | ~1.5 GB |
| FP16 | 259 MB | Gleich | ~800 MB |

**Empfehlung:** FP16 reicht für die meisten Anwendungsfälle. FP32 nur bei Geräten mit viel RAM (>16 GB).

---

## Option 2: BirdNET V2.4 (Python)

Benötigt eine Python-Installation. Erkennt 6'362 Vogelarten.

### Voraussetzungen

- Python **3.11, 3.12 oder 3.13** (Python 3.14 wird NICHT unterstützt!)

### Installation

1. **Python installieren** (falls nötig):
   ```powershell
   winget install Python.Python.3.12
   ```
   PowerShell danach neu starten.

2. **BirdNET Python-Paket installieren:**
   ```powershell
   pip install birdnet
   ```

   Falls mehrere Python-Versionen installiert sind:
   ```powershell
   py -3.12 -m pip install birdnet
   ```

3. **Prüfen:**
   ```powershell
   python -c "import birdnet; print('BirdNET OK')"
   ```

4. **AMSEL starten** — BirdNET V2.4 (Python) erscheint automatisch in den Einstellungen.

### Häufige Fehler

| Fehler | Lösung |
|--------|--------|
| `No matching distribution found for birdnet` | Python-Version ist zu neu (3.14). Python 3.12 installieren. |
| `python: command not found` | Python nicht installiert oder nicht im PATH. |
| `ModuleNotFoundError: No module named 'birdnet'` | `pip install birdnet` nochmals ausführen. |

---

## Modell-Ordner Übersicht

Nach der Installation sollte der Ordner so aussehen:

```
C:\Users\[NAME]\Documents\AMSEL\models\
├── BirdNET+_V3.0-preview3_Global_11K_FP32.onnx   ← V3.0 Modell
├── BirdNET+_V3.0-preview3_Global_11K_Labels.csv   ← V3.0 Labels
├── models.json                                     ← Wird automatisch erstellt
├── classify.py                                     ← V2.4 Python-Bridge
└── classify_daemon.py                              ← V2.4 Python-Bridge
```

> **Tipp:** Es können mehrere Modelle gleichzeitig installiert sein. In den Einstellungen (Tab "Analyse" → "Modelle verwalten...") kann zwischen ihnen gewechselt werden.

---

## Eigene Modelle (Custom ONNX)

AMSEL unterstützt auch eigene ONNX-Modelle. In den Einstellungen → "Modelle verwalten..." → "Modell hinzufügen" kann eine beliebige `.onnx`-Datei mit optionaler Labels-Datei importiert werden.

Voraussetzungen für Custom-Modelle:
- ONNX-Format (.onnx)
- Audio-Input: 3-Sekunden Chunks, 48 kHz Sample-Rate
- Output: Score-Vektor pro Chunk
- Labels-Datei: Eine Art pro Zeile (wissenschaftlicher Name + englischer Name)

---

## Lizenz der Modelle

- **BirdNET V3.0:** CC BY-SA 4.0 — kommerziell nutzbar mit Namensnennung
- **BirdNET V2.4:** CC BY-NC-SA 4.0 — nur für nicht-kommerzielle/Forschungszwecke

> Zitation: Kahl, S., Wood, C. M., Eibl, M., & Klinck, H. (2021). BirdNET: A deep learning solution for avian diversity monitoring. Ecological Informatics, 61, 101236.
