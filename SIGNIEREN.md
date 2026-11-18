# Installer signieren (SignPath Foundation)

Stand: 18.11.2026. **Noch nicht eingerichtet:** der Antrag bei SignPath ist nicht gestellt, der Workflow ist noch nie gelaufen.

Ziel: Der Windows-Installer wird auf GitHub aus den Quellen gebaut und mit einem Zertifikat der SignPath Foundation signiert (kostenlos fuer Open-Source-Projekte). Der Schluessel liegt bei SignPath, nicht im Repository und nicht auf einem Rechner des Projekts.

## Was im Repository vorbereitet ist

`.github/workflows/release.yml` ("Installer bauen"):

1. laeuft bei jedem Tag `v*` und von Hand (Actions → Installer bauen → Run workflow)
2. Tests, dann `packageMsi` auf einem GitHub-eigenen Windows-Runner
3. laedt den unsignierten Installer als Artefakt hoch
4. **nur wenn SignPath eingerichtet ist:** reicht ihn zum Signieren ein, wartet auf die Freigabe (bis 1 Stunde) und haengt den signierten Installer an den Release des Tags

Ohne SignPath endet der Lauf nach Schritt 3.

## Bedingungen von SignPath (https://signpath.org/terms, gelesen am 18.11.2026)

| Bedingung | Stand bei AMSEL |
|---|---|
| OSI-Lizenz, **kein kommerzielles Dual-Licensing** | GPL v3. **Offen:** die Zeile "Fuer kommerzielle Lizenzoptionen: support@etasystems.ch" im README widerspricht dem und muesste weg |
| keine proprietaeren Bestandteile | Die Modelle (BirdNET, Perch) sind nicht im Installer. Lizenzen der mitgelieferten Bibliotheken und der Artenliste (`species_master.json`) nicht einzeln geprueft |
| Projekt gepflegt und bereits veroeffentlicht | ja (Releases v0.0.5 bis v0.0.10) |
| Bau aus den Quellen, nachvollziehbar, auf GitHub-eigenen Runnern | mit dem Workflow vorbereitet, noch nicht gelaufen |
| jede Signatur von Hand freigegeben | geschieht in SignPath (Rolle Approver) |
| Zwei-Faktor-Anmeldung fuer alle Beteiligten, bei GitHub und SignPath | vom Kontoinhaber zu pruefen |
| Deinstallation moeglich | ja (MSI, Windows "Apps") |
| Abschnitt "Code signing policy" auf der Startseite des Projekts und bei den Downloads | Entwurf unten, noch nicht im README |
| Angabe, welche Daten das Programm uebertraegt | Entwurf unten |

Die Annahme liegt im Ermessen von SignPath; einen Anspruch gibt es nicht.

## Schritte

1. Entscheiden, ob die Zeile zu kommerziellen Lizenzen aus dem README entfernt wird. Ohne das ist der Antrag aussichtslos.
2. Zwei-Faktor-Anmeldung im GitHub-Konto einschalten, falls noch nicht geschehen.
3. Den Abschnitt "Code signing policy" (unten) ins README und in den Text der Releases uebernehmen.
4. Workflow einmal von Hand laufen lassen und pruefen, dass ein Installer entsteht.
5. Antrag stellen: https://signpath.org/apply
6. Nach der Annahme, in SignPath: Projekt anlegen, als Trusted Build System "GitHub.com" verknuepfen, Artefakt-Konfiguration fuer ein ZIP mit einer MSI-Datei, Signing Policy fuer Releases. Die SignPath GitHub App fuer dieses Repository installieren.
7. In GitHub unter Settings → Secrets and variables → Actions eintragen:
   - Secret `SIGNPATH_API_TOKEN`
   - Variablen `SIGNPATH_ORGANIZATION_ID`, `SIGNPATH_PROJECT_SLUG`, `SIGNPATH_SIGNING_POLICY_SLUG`
8. Naechsten Release taggen. Der Workflow reicht den Installer ein; die Anfrage in SignPath freigeben.

## Entwurf: Abschnitt fuers README

```markdown
## Code signing policy

Free code signing provided by [SignPath.io](https://signpath.io), certificate by [SignPath Foundation](https://signpath.org).

- Committers and reviewers: [Nikiphedia](https://github.com/Nikiphedia)
- Approvers: [Nikiphedia](https://github.com/Nikiphedia)

### Privacy policy

This program will not transfer any information to other networked systems unless specifically requested by the user or the person installing or operating it. On request it contacts xeno-canto.org to search and download reference recordings, using the API key entered by the user.
```

Vor dem Uebernehmen pruefen: ob ausser Xeno-Canto weitere Verbindungen bestehen (Modell-Download). Im Quelltext fand eine Suche nach festen Adressen nur `xeno-canto.org` und `127.0.0.1` (lokaler Python-Dienst); zusammengesetzte Adressen findet eine solche Suche nicht.
