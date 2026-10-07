# Plexamp AA Lyrics

**Synchronisierte Songtexte für Plexamp in Android Auto.**

Plexamp zeigt Songtexte nur auf dem Handy an. Dieser Patch bringt sie auf den Bildschirm im Auto: Während ein Song läuft, steht die aktuelle Zeile im „Gerade läuft“-Bildschirm von Android Auto und wechselt im Takt der Musik.

Das Repo enthält **keinen Plexamp-Code und keine APK**. Es enthält nur den Patch und ein Skript, das ihn in deine eigene Plexamp-APK einbaut.

## Funktionen

- **Zeitgesteuerte Songtexte im Auto.** Die aktuelle Zeile erscheint groß als Titel, darunter „Titel – Interpret“.
- **Taste mit drei Stufen** in der Android-Auto-Steuerleiste, reihum:

  | Stufe | Anzeige |
  |---|---|
  | **An** | aktuelle Zeile, darunter „Titel – Interpret“ |
  | **Erweitert** | aktuelle Zeile, darunter die nächste, im Albumfeld die übernächste |
  | **Aus** | normale Anzeige ohne Songtext |

  Die gewählte Stufe bleibt nach einem Neustart erhalten.
- **Nur im Auto.** Auf dem Handy, im Sperrbildschirm und in der Benachrichtigung bleibt alles wie gewohnt.
- **Einstellungsdialog** über einen Kurzbefehl am App-Icon (lange drücken → „Songtexte AA“), mit Verbindungstest.
- Songtexte kommen **direkt von deinem Plex-Server**: Plex-Pass-Songtexte (LyricFind) und LRC-Dateien.

## Voraussetzungen

- Plexamp für Android als APK (getestet mit **4.50.19**)
- ein Plex-Server mit zeitgesteuerten Songtexten, erreichbar über **HTTPS** (die übliche `…plex.direct:32400`-Adresse)
- zum Bauen: Java JDK 11 oder neuer, `python3`, `curl`, `unzip`, `zip`; Linux, macOS oder Windows mit WSL

## Bauen

```bash
git clone https://github.com/yzfsoldier/plexamp-aa-lyrics.git
cd plexamp-aa-lyrics
./build.sh /pfad/zu/Plexamp-4_50_19.apk
```

Das Ergebnis `Plexamp-4_50_19-AA-Lyrics.apk` liegt danach im Projektordner. Ein Durchlauf dauert etwa eine Minute.

Was `build.sh` macht:

1. lädt beim ersten Mal die Werkzeuge nach `build/tools/` (apktool, dex-tools, uber-apk-signer, android.jar – zusammen ca. 90 MB, alles von GitHub)
2. zerlegt die APK mit apktool
3. setzt die Haken in den Plexamp-Code (`build/patch_smali.py`)
4. kompiliert den Patch aus `src/` und wandelt ihn in Android-dex um
5. führt die Selbsttests aus `test/` aus
6. baut die APK wieder zusammen und fügt den Patch als zusätzliche `classesN.dex` ein
7. richtet sie aus und signiert sie

### Signierschlüssel

Android nimmt ein Update nur an, wenn es mit **demselben Schlüssel** signiert ist wie die installierte App. Gebaut wird mit `ks.jks` im Projektordner:

- **Fehlt die Datei**, erzeugt `build.sh` automatisch einen neuen Schlüssel.
- **Hast du schon eine gepatchte Version installiert**, leg deren `ks.jks` in den Projektordner. Dann lässt sich die neue Version einfach drüber installieren.

Der Schlüssel steht in der `.gitignore` und landet nie im Repo. Einen anderen Schlüssel gibst du per Umgebungsvariablen an:

```bash
KEYSTORE=mein.jks KS_ALIAS=meinalias KS_PASS=geheim ./build.sh Plexamp.apk
```

## Installieren und einrichten

1. **Original-Plexamp deinstallieren.** Die gepatchte App hat eine andere Signatur. Einstellungen und Downloads gehen dabei verloren.
2. Die gebaute APK installieren und Plexamp **einmal öffnen**. Dabei entsteht der Kurzbefehl am App-Icon.
3. **Android Auto für Apps außerhalb des Play Stores freischalten:**
   - In den Android-Auto-Einstellungen mehrmals auf „Version“ tippen, bis die Entwickleroptionen erscheinen.
   - Dort „Unbekannte Quellen“ aktivieren.
4. **Server eintragen:**
   - Plexamp-Icon lange drücken → **„Songtexte AA“**.
   - Am einfachsten: In Plex Web bei einem Titel „Informationen → XML anzeigen“ öffnen und die komplette Adresse aus der Adresszeile einfügen. Das Token steckt darin.
   - **Testen** antippen, dann **Speichern**.

Updates über den Play Store gibt es für die gepatchte App nicht. Für eine neue Plexamp-Version `build.sh` mit der neuen APK erneut ausführen.

## Neue Plexamp-Versionen

`build/patch_smali.py` prüft jede Stelle, an der der Patch in Plexamp eingreift. Hat sich dort etwas geändert, bricht der Build mit einer konkreten Meldung ab, etwa:

```
  CustomActions (Taste): FEHLER – CustomActions.buildLayout: Ankerstelle 0x gefunden (erwartet genau 1)
```

Es entsteht also keine kaputte APK. Der betroffene Patch muss dann an den neuen Code angepasst werden. Ein erneuter Durchlauf über bereits gepatchten Code fügt nichts doppelt ein.

## Wie es funktioniert

Plexamp meldet den Wiedergabestatus aus seiner React-Native-Oberfläche an eine Android-Mediensitzung (Media3). Diese Sitzung zeigt Android Auto an. Der Patch hängt sich an genau diese Übergabe (`TreblePlayer.updateFromState`):

1. Beim Songwechsel sucht `LyricsCore` den Titel auf deinem Plex-Server und holt den Songtext-Stream (`/library/streams/…?format=xml`).
2. Ein Takt von 200 ms bestimmt anhand der Wiedergabeposition die aktuelle Zeile.
3. Wechselt die Zeile, bekommt die Mediensitzung Titel und Untertitel neu. Das passiert nur, solange Android Auto verbunden ist.

| Pfad | Inhalt |
|---|---|
| `src/…/LyricsCore.java` | Server-Abfragen, Plex-XML- und LRC-Parser (reines Java, testbar) |
| `src/…/LyricsHook.java` | Anzeige in Android Auto, Taste Aus/An/Erweitert, Erkennung von Android Auto |
| `src/…/LyricsSettings.java` | Einstellungsdialog und Kurzbefehl am App-Icon |
| `stubs/` | Platzhalter-Signaturen von Plexamp-, Media3- und Guava-Klassen, nur zum Kompilieren |
| `test/` | Selbsttests: Parser, Abfrage gegen einen Test-Server, Eingabeprüfung |
| `build/patch_smali.py` | Haken in `TreblePlayer`, `MainActivity`, `CustomActions`, `PlexampSessionCallback` |
| `build/fetch-tools.sh` | lädt die Build-Werkzeuge |
| `build.sh` | der komplette Build |

## Fehlersuche

```bash
adb logcat -s PlexampLyricsMod
```

Das Log zeigt, ob Android Auto erkannt wurde, ob Songtexte geladen wurden und welche Anzeigestufe aktiv ist. Tokens werden nie ins Log geschrieben.

## Einschränkungen

- **Android Auto gibt die Oberfläche vor.** Apps können dort keinen eigenen Bildschirm zeichnen, nur Titel, Untertitel und Album füllen. Wie viel davon sichtbar ist, hängt vom Auto ab.
- **Wo die Taste landet,** neben „Weiter“ oder im ⋯-Menü, entscheidet Android Auto.
- **Nur zeitgesteuerte Songtexte** werden angezeigt. Reine Texte ohne Zeitangaben nicht.
- **Unverschlüsseltes HTTP zum Server** blockiert Android in dieser App. Bitte die HTTPS-Adresse verwenden.

## Hinweis

Inoffizielles Projekt, nicht mit Plex Inc. verbunden. Plex und Plexamp sind Marken von Plex Inc. Gedacht für den privaten Gebrauch mit einer selbst bezogenen Plexamp-APK. Das Verändern der App verstößt möglicherweise gegen die Nutzungsbedingungen von Plex.

Bitte nicht während der Fahrt mitlesen. Die Stufe „Erweitert“ ist vor allem für Beifahrer gedacht.
