# Versandhilfe 📦

Eine native Android-App für die vielen Pakete zu Hause: Paketfoto, Versandlabel/QR, Empfänger und Notizen gehören sichtbar zusammen. Deutsche Oberfläche mit Material 3, hell/dunkel, animierter Einführung und Android-Splashscreen. Android 8.0 oder neuer.

## APK ausprobieren

1. [GitHub Actions → Android APK](https://github.com/Robin9094707/Versand-Hilfe/actions/workflows/android.yml) öffnen.
2. Den letzten erfolgreichen Lauf wählen und unter **Artifacts** `Versandhilfe-Android-APK` herunterladen (GitHub-Anmeldung nötig).
3. ZIP entpacken, `Versandhilfe-2.0.0.apk` auf dem Android-Handy öffnen. Installation aus dieser Quelle erlauben.
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
- Die App sendet nur Sendungsnummer und Anbieter-Code an TrackingMore, keine Fotos, Adressen oder Notizen. Der Schlüssel wird mit Android Keystore verschlüsselt und ab Version 2.0 passwortgeschützt mitgesichert. Siehe den Abschnitt zum KI-Modus.
- Die voreingestellten deutschen Anbieter werden gegen die aktuelle API-Anbieterliste aufgelöst. Für weitere Anbieter einen TrackingMore-Anbieter-Code manuell eintragen. Kein Anspruch auf Unterstützung sämtlicher Versandprodukte; Anbieter-Codes können angepasst werden.
- WorkManager prüft die lokalen Aufgaben ungefähr stündlich; Online-Tracking frühestens alle sechs Stunden je Paket. Lokale Sicherung und Erinnerungen können auch offline laufen. Android kann Arbeit verschieben. Änderungen erzeugen bei erlaubten Benachrichtigungen eine Meldung; Fristerinnerungen werden höchstens einmal täglich ausgegeben. „Tracking jetzt aktualisieren“ ist ebenfalls verfügbar.
- Keine inoffiziellen Carrier-APIs und kein Scraping. Ohne echten API-Schlüssel konnte das produktive Tracking nicht gegen ein Kundenkonto getestet werden.

## Entwicklung & Prüfung

Android Studio, JDK 17 und SDK 36; Gradle 8.13. In Android Studio öffnen oder mit installiertem Gradle:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

Material 3 **1.4.0** ist die am 01.11.2026 verifizierte stabile Version. Reproduzierbare Toolchain: AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2025.10.01. CI baut bei Push/PR und manuell. Ein zweiter Job führt Speicher-/ZIP- und Compose-Tests auf einem Android-35-Emulator aus und erstellt Screenshots. Berichte werden als Actions-Artefakte gespeichert.

Quellen: [Material 3 Releases](https://developer.android.com/jetpack/androidx/releases/compose-material3), [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files), [ML Kit Barcode](https://developers.google.com/ml-kit/vision/barcode-scanning/android), [TrackingMore SDK / API V4](https://github.com/TrackingMore-API/trackingmore-sdk-python).

## Neu in 2.0: optionaler OpenAI-Pakethelfer

Unter **Sicherung → KI & dein Profil** Namen und Backup-Passwort (mindestens 10 Zeichen) eintragen. Danach eigenen OpenAI-API-Schlüssel hinterlegen, der Datenübertragung zustimmen und KI aktivieren. Die bestehende App bleibt ohne KI nutzbar. Kein ChatGPT-Login und kein fest eingebauter API-Schlüssel.

Verwendet wird die offizielle **Responses API** (`POST https://api.openai.com/v1/responses`) mit **GPT-5.6 Luna**, `store:false`, `reasoning.effort:none`, strikten JSON-Schemas und begrenzter Ausgabelänge. Neue Fotos/Screenshots werden im Automatikmodus als Paket, Label, Adresse, Beleg oder Kombination eingeordnet. OCR/QR-Erkennung bleibt lokal verfügbar. Vorhandene Angaben werden nicht überschrieben. Auch geteilte Bilder/PDFs gehen zunächst in den Editor. Bei PDFs wird die erste Seite analysiert. Bilder werden für den Upload verkleinert; OpenAI erhält Bild und begrenzten OCR-Text. API-Schlüssel werden nie ins Repository, in Prompts oder in Protokolle geschrieben.

**Prüfung statt Fehlerfreiheitsversprechen:** KI kann falsch lesen. Übernommene Felder zeigen Erkennungsquellen und müssen vor dem Speichern als geprüft bestätigt werden. „Erstellt am“ ist keine Abgabefrist. Relative Fristen alter Screenshots und Datumsangaben ohne nachvollziehbares Jahr werden nicht als sichere Frist übernommen. Bestellnummer und Sendungsnummer sind getrennt. KI verändert weder Paketstatus noch Tracking-Ergebnisse.

Der Paket-Chat ist ein eingeschränkter Intent-Router, kein allgemeiner Chatbot: Offensichtliche sachfremde und gemischte Fragen werden vor dem Netzwerkaufruf abgelehnt. Für andere Paketfragen liefert das Modell ausschließlich einen erlaubten Such-/Filterauftrag. **Antworttexte, Zahlen, Termine und anklickbare Paketverweise werden dann lokal aus echten gespeicherten Daten erzeugt.** Es gibt keine frei formulierten Modellantworten, keine Modell-URLs und keine Schreibaktionen im Chat. Die Standardschaltflächen funktionieren ohne API-Aufruf. Umfangreiche freie Fragen sind absichtlich begrenzt und können abgewiesen werden.

Ein App-Limit von standardmäßig 20 (wählbar 5/10/20/50) KI-Aufrufversuchen pro lokalem Kalendertag und kein automatischer Retry begrenzen die Nutzung. Auch fehlgeschlagene Versuche zählen. Die Verbrauchsanzeige ist eine Token-basierte Orientierung nach den am 01.11.2026 dokumentierten Luna-Preisen von 0,20 USD/Mio. Eingabe- und 1,20 USD/Mio. Ausgabetokens; maßgeblich ist die OpenAI-Abrechnung. Kein unbegrenzter Hintergrund-Chat. Die Startzusammenfassung, zeitabhängige Begrüßung, Fristberechnung und Erinnerungen werden lokal erzeugt und verbrauchen keine KI-Tokens.

**Hintergrund:** WorkManager kontrolliert ungefähr stündlich die lokalen Aufgaben und erinnert frühestens zur gewählten Tageszeit höchstens einmal pro Tag. TrackingMore bleibt auf frühestens sechs Stunden je Paket begrenzt. Normales Schließen beendet geplante Arbeit nicht. Androids Akkuoptimierung/Doze kann sie verschieben; „Stopp erzwingen“ blockiert Hintergrundarbeit bis zum erneuten Öffnen. Benachrichtigungen müssen erlaubt sein. Kein Dauerbetrieb und keine exakten Uhrzeitversprechen.

**Vollständiges Backup:** Name, KI-Modus, Limits, Erinnerungsoptionen, Chat (letzte 80 Nachrichten), Erkennungsquellen, Paketdaten und Anhänge werden in Ordner/ZIP gespeichert. OpenAI- und TrackingMore-Schlüssel werden zusätzlich als passwortgeschützter AES-256-GCM-Tresor mit zufälligem Salt/Nonce und PBKDF2-HMAC-SHA256 (210.000 Runden) mitgesichert. Lokal schützt Android Keystore Schlüssel und gespeichertes Backup-Passwort. Das Passwort selbst wird nicht exportiert. Nach Neuinstallation denselben Ordner/ZIP laden, dann **API-Schlüssel aus Backup entsperren**. Ohne korrektes Passwort sind die Schlüssel nicht wiederherstellbar; Paketdaten bleiben nutzbar. Andere Backup-Inhalte sind weiterhin unverschlüsselt. Alte Backups können ältere Schlüssel enthalten. Keine separate Datenmigration; die bestehenden JSON-Strukturen werden um optionale Felder ergänzt.

Die HTTP-Anbindung wird mit MockWebServer einschließlich Authentifizierungsfehlern, unvollständigen Antworten und Refusals geprüft. Ohne echten Kundenschlüssel wurde kein kostenpflichtiger Live-API-Aufruf durchgeführt. Unter eigenem Schlüssel können Modellfreigaben, Guthaben und OpenAI-Limits abweichen.

Offizielle Quellen: [GPT-5.6 Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna), [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [Vision](https://developers.openai.com/api/docs/guides/images-vision).

## Version 2.1 – erst lokal scannen, dann KI

Jeder Foto-/PDF-Import liest zuerst mit dem gebündelten ML-Kit-Modell lokal QR-/Barcodes und Text. Auch ohne OpenAI-Schlüssel bleibt das nutzbar. „QR-Code mit Kamera scannen“ verwendet CameraX und denselben lokalen Decoder; nach einem Treffer wird das tatsächliche Kamerafoto gespeichert, kein neuer Versandcode erzeugt. Die Kamera wird beim Schließen freigegeben. Rohinhalte werden beim Paket gespeichert und mit Ordnersicherung/ZIP wiederhergestellt. Im aktivierten KI-Modus gehen Bild, OCR, Rohinhalte und getrennte lokale Adressrollen an OpenAI; die vier Rückfragen selbst verbrauchen keine API-Aufrufe.

### Adressrollen und unterstützte Formate

Der implementierte DHL-Parser unterstützt bewusst nur die beobachtete deutsche `OF|…|0|…|A|…|V2.0`-Variante mit genau 27 Feldern: 5–10 Absender, 11–16 Empfänger (nullbasierte Indizes). Name, Zusatz, Straße, PLZ, Ort und Land werden separat validiert. Diese Zuordnung basiert auf dem vom Nutzer bestätigten Beispiel; sie ist **keine öffentlich dokumentierte, universelle DHL-Spezifikation**. Im Paket wird ausschließlich die Zieladresse übernommen. Die Rohdaten können weiterhin beide Rollen enthalten. Bestehende manuelle Angaben werden bei einem Widerspruch zur Prüfung angezeigt und nicht still überschrieben. Andere OF-Versionen, ungültige Felder und verschiedene Empfänger in mehreren Codes benötigen eine menschliche Auswahl.

Für Hermes, DPD, UPS, GLS, FedEx und Deutsche Post werden die lokal lesbaren Rohdaten/OCR für die Erkennung genutzt. Es wird kein universelles proprietäres Adressschema behauptet: viele mobile Labels enthalten nur eine Druck-/Retourenreferenz. `RET`-/`PAK`-IDs und unbeschriftete Zahlen-/Datumsfelder im DHL-QR werden nicht als Trackingnummer oder Abgabefrist verwendet. Tracking-Vorschläge bleiben konservativ (beschriftete Nummern, bekannte UPS-Form, Parameter offizieller Tracking-Domains). Unklare Rollen werden nicht automatisch als Zieladresse ausgefüllt. KI-Vorschläge bleiben prüfpflichtig; eine fehlerfreie Bilderkennung kann nicht garantiert werden.

Nach der KI-Erkennung fragt die App in einfacher Sprache nach Inhalt, Empfänger und Lagerplatz. Zum Schluss zeigt sie die Angaben zusammen an. „Bereit zum Abgeben“ ist erst mit Paketname, Empfänger und Lagerplatz möglich; „Erst einmal anlegen“ lässt das Paket in Vorbereitung. Es wird niemals durch Bilderkennung als bereits abgegeben markiert. Anschließend wird bewusst über „Paket speichern“ gesichert.

Offizielle Grundlagen (geprüft am 01.11.2026):

- [DHL Mobile Paketmarke und PAK-/RET-ID](https://www.dhl.de/de/privatkunden/pakete-versenden/angebot-online-frankierung/mobile-paketmarke.html)
- [Hermes Mobiler Paketschein](https://www.myhermes.de/versenden/mobiler-paketschein/)
- [DPD mobiler Paketschein](https://www.dpd.com/de/de/faq/bekomme-ich-den-paketschein-in-der-app-angezeigt-oder-muss-ich-ihn-ausdrucken/)
- [UPS mobile Barcodes und QR-Codes](https://www.ups.com/us/en/support/manage-your-profile/stay-connected)
- [Google ML Kit: lokal gebündeltes Barcode-Modell, Rohinhalte](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- [CameraX Bildanalyse und Ressourcenfreigabe](https://developer.android.com/media/camera/camerax/analyze)
- [OpenAI Vision: Bilderkennung und Grenzen](https://developers.openai.com/api/docs/guides/images-vision)

Tests verwenden ausschließlich erfundene Adressen und Codes. Persönliche Versandlabels und API-Schlüssel gehören nicht ins Repository.
