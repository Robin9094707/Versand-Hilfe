# Versandhilfe 📦

Eine native Android-App für die vielen Pakete zu Hause: Paketfoto, Versandlabel/QR, Empfänger und Notizen gehören sichtbar zusammen. Deutsche Oberfläche mit Material 3, hell/dunkel, animierter Einführung und Android-Splashscreen. Android 8.0 oder neuer.

## APK ausprobieren

1. [GitHub Actions → Android APK](https://github.com/Robin9094707/Versand-Hilfe/actions/workflows/android.yml) öffnen.
2. Den letzten erfolgreichen Lauf wählen und unter **Artifacts** `Versandhilfe-Android-APK` herunterladen (GitHub-Anmeldung nötig).
3. ZIP entpacken, `Versandhilfe-1.0.0.apk` auf dem Android-Handy öffnen. Installation aus dieser Quelle erlauben.
4. Beim Start einen eigenen Ordner auswählen, z. B. **Dokumente/Versandhilfe-Daten**. Android/data und Android/obb eignen sich nicht.

Das ist eine installierbare Test-APK, keine Play-Store-Veröffentlichung. Der CI-Debug-Schlüssel wird zwischen Builds gecacht, damit Updates normalerweise ohne Neuinstallation funktionieren. Bei gelöschtem Cache kann sich die Testsignatur ändern; vorher ZIP exportieren. Für einen dauerhaften Release einen eigenen geschützten Signierschlüssel einrichten.

## Für Mama: so geht’s

- **Neues Paket:** Artikelname, Empfänger, Lagerplatz und optional Adresse/Frist eintragen.
- **Fotos & Unterlagen:** Paket fotografieren; Versandlabel oder QR als Bild bzw. PDF hinzufügen. Auch mehrere Labels, Adressbilder und Einlieferungsbelege sind möglich.
- **Nummer aufschreiben:** `P-001`, `P-002` … auf den echten Karton schreiben. Die App und das Paket sind damit schnell zuzuordnen.
- **Bereit zur Abgabe:** Status umstellen. In „Abgeben“ Paketfoto vergleichen und das originale Versandlabel bildschirmfüllend öffnen. Zoomen, PDF-Seiten wechseln, teilen oder in einer geeigneten App drucken.
- **Abgegeben:** Einzeln oder mehrere ausgewählte Pakete markieren. Später Beleg hinzufügen.
- **Teilen aus anderen Apps:** Fotos/PDFs über Android „Teilen → Versandhilfe“ einem vorhandenen oder neuen Paket zuordnen.

Suche nach Titel, Nummer, Empfänger, Adresse, Notiz, Lagerplatz, Plattform oder Sendungsnummer. Filter nach Status und Anbieter, Sortierung nach Neuheit oder Frist. Paketverlauf und optional Benachrichtigungen.

## Ordner, Backup und Neuinstallation

Die App arbeitet mit einer atomar geschriebenen lokalen Kopie. Nach jeder gespeicherten Änderung wird die gewählte SAF-Ordnersicherung aktualisiert. Sie enthält **alle Anhänge als echte Dateien**, nicht nur temporäre Android-URIs. Dateien werden über SHA-256 geprüft. Erst nach vollständiger Dateisicherung wird ein neuer Metadaten-Snapshot veröffentlicht. Zehn Metadatenstände bleiben erhalten; alte Anhangdateien werden bewusst nicht automatisch gelöscht.

Ordnerstruktur:

```
Versandhilfe/
  assets/<sha256>.jpg|png|webp|pdf
  snapshots/<zeitstempel>-<uuid>.json
```

Nach Neuinstallation **denselben Ordner neu auswählen**. Androids Zugriffsfreigabe muss erneut erteilt werden. Die App findet den neuesten vollständigen Snapshot, prüft die Dateien und bietet „Sammlung laden“ an. Beschädigte/unvollständige Snapshots werden übersprungen. Vorhandene lokale Pakete werden zusammengeführt; bei derselben ID gewinnt die neuere Bearbeitung. Paketnummern werden bei Import-Kollisionen neu vergeben – bitte auf betroffenen Kartons prüfen.

Unter **Sicherung** kann man jederzeit den Ordner neu verbinden, explizit sichern sowie ein vollständiges ZIP exportieren/importieren. ZIP-Import validiert Dateipfade, Format, Größen und Prüfsummen vor Übernahme. Maximal 40 MB pro Anhang, 500 MB und 2.000 Einträge pro importiertem ZIP. JPG, PNG, WebP und PDF. Bei Ausfall des Ordners bleibt die lokale Änderung gespeichert und ein sichtbarer Hinweis fordert zur erneuten Sicherung auf. Hintergrundarbeit versucht später erneut zu sichern. Vor Deinstallation unbedingt „Alle Änderungen im Ordner gesichert“ prüfen oder ZIP exportieren.

**Wichtig:** Ein Ordner auf demselben Handy schützt nicht vor Geräteverlust. ZIP zusätzlich extern sichern. Ordner/ZIP enthalten Adressen und Labels unverschlüsselt. Frühere Sicherungen können entfernte Pakete enthalten. Ein späterer Import eines alten Backups kann entfernte Einträge wiederherstellen. Die App ist für eine Person/ein schreibendes Gerät gedacht, nicht für parallele Ordnersynchronisation zwischen mehreren Handys.

## Code-Erkennung und Tracking

Die gebündelten ML-Kit-Modelle lesen QR-/Barcodes und lateinischen Text auf dem Gerät. Bei Label- und Adressimport erfolgt automatisch eine Erkennung, ansonsten über das Scan-Symbol. Bei PDFs wird die **erste Seite** erkannt; alle Seiten bleiben im Viewer verfügbar. Erkannter Text kann als Adresse oder Notiz übernommen werden. Vorschläge für Sendungsnummern brauchen immer die Bestätigung der Person. Ein Versand-/Abgabe-QR ist oft **keine** Sendungsnummer und wird nicht einfach als Tracking aktiviert. Die Originaldatei bleibt erhalten. Unsichere numerische Codes ohne Anbieterhinweis werden nicht geraten.

- DHL, Hermes, DPD, UPS, GLS, FedEx und Deutsche Post: direkte Links zur offiziellen Sendungsverfolgung mit manuell oder bestätigt erkannter Nummer. Die Anbieter-Webseite kann zusätzlich die PLZ abfragen.
- **Automatisch:** optionale TrackingMore-V4-Integration. Eigenes Konto/API-Schlüssel und je nach Anbieter/Tarif kostenpflichtiges API-Kontingent erforderlich. Unter Sicherung zustimmen und Schlüssel speichern; danach pro Paket aktivieren.
- Die App sendet nur Sendungsnummer und Anbieter-Code an TrackingMore, keine Fotos, Adressen oder Notizen. Der Schlüssel wird mit Android Keystore verschlüsselt und nicht exportiert. Nach Neuinstallation erneut einrichten.
- Für weitere Anbieter einen TrackingMore-Anbieter-Code manuell eintragen. Kein Anspruch auf Unterstützung sämtlicher Versandprodukte; Anbieter-Codes können angepasst werden.
- WorkManager prüft ungefähr alle sechs Stunden bei Internetzugang. Android kann Arbeit verschieben. Änderungen erzeugen bei erlaubten Benachrichtigungen eine Meldung; Fristerinnerungen werden höchstens einmal täglich ausgegeben. „Tracking jetzt aktualisieren“ ist ebenfalls verfügbar.
- Keine inoffiziellen Carrier-APIs und kein Scraping. Ohne echten API-Schlüssel konnte das produktive Tracking nicht gegen ein Kundenkonto getestet werden.

## Entwicklung & Prüfung

Android Studio, JDK 17 und SDK 36; Gradle 8.13. In Android Studio öffnen oder mit installiertem Gradle:

```sh
gradle wrapper --gradle-version 8.13
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

Material 3 **1.4.0** ist die am 01.11.2026 verifizierte stabile Version. Reproduzierbare Toolchain: AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2025.10.01. CI baut bei Push/PR und manuell. Ein zweiter Job führt Speicher-/ZIP- und Compose-Tests auf einem Android-35-Emulator aus und erstellt Screenshots. Berichte werden als Actions-Artefakte gespeichert.

Quellen: [Material 3 Releases](https://developer.android.com/jetpack/androidx/releases/compose-material3), [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files), [ML Kit Barcode](https://developers.google.com/ml-kit/vision/barcode-scanning/android), [TrackingMore SDK / API V4](https://github.com/TrackingMore-API/trackingmore-sdk-python).
