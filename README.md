# Hey Claude für Android

Kleine Android-App, die im Hintergrund **offline** auf das Weckwort „Hey Claude“ hört und dann den Standard-Sprachassistenten des Handys startet (bei dir: Claude). Kein Button, kein Umweg über Google. Die Erkennung läuft komplett auf dem Handy mit [openWakeWord](https://github.com/dscripka/openWakeWord)-Modellen über ONNX Runtime.

## Installation

1. **APK herunterladen:** Auf dem Handy diese Seite öffnen:
   <https://github.com/Eichi87/hey-claude-android/releases/latest/download/hey-claude.apk>
   (alle Versionen: <https://github.com/Eichi87/hey-claude-android/releases>)
2. **Installation aus unbekannten Quellen erlauben:** Beim Öffnen der heruntergeladenen Datei fragt Android nach. Für den Browser (z. B. Chrome) „Aus dieser Quelle zulassen“ einschalten und zurück zur Installation.
3. **App öffnen und Berechtigungen setzen:** Unter „Berechtigungen“ alle vier Knöpfe antippen:
   - **Mikrofon** – zum Lauschen
   - **Benachrichtigungen** – für die dauerhafte Anzeige „Hört auf Hey Claude“
   - **Über anderen Apps einblenden** – nötig, damit der Assistent aus dem Hintergrund starten darf
   - **Akku-Optimierung ausnehmen** – sonst beendet Android den Dienst nach einiger Zeit
4. **Akku-Optimierung abschalten:** Falls der Knopf nicht direkt hilft: *Einstellungen → Apps → Hey Claude → Akku → „Nicht eingeschränkt“*. Bei Samsung zusätzlich unter *Akku → Hintergrundnutzungslimits* sicherstellen, dass Hey Claude nicht in „Apps im Standby“ steht.
5. Schalter **„Auf ‚Hey Claude‘ hören“** einschalten.

Updates: Neue APK genauso herunterladen und installieren. Sie wird über die alte Version installiert, die Einstellungen bleiben erhalten.

## Bedienung

- **Erkennungsschwelle** (Standard 0,50): niedriger = reagiert leichter, löst aber öfter versehentlich aus. Der angezeigte „aktuelle Erkennungswert“ hilft beim Einstellen.
- **Pausieren/Fortsetzen** direkt in der Benachrichtigung.
- Nach einer Erkennung gibt die App das Mikrofon sofort frei und lauscht erst wieder, wenn keine andere App mehr aufnimmt (mindestens 5 Sekunden Pause).
- Startet statt Claude ein anderer Assistent, die Option „Claude-App direkt öffnen“ einschalten.
- **Nach einem Neustart:** Bis Android 13 startet die App von selbst. Ab Android 14 erlaubt Android das für Mikrofon-Dienste nicht mehr; dann erscheint die Benachrichtigung „Tippen zum Aktivieren“.

## Technik

- Kotlin, minSdk 26, targetSdk 34, Paket `de.eichberger.heyclaude`
- Modelle in `app/src/main/assets`: `melspectrogram.onnx` und `embedding_model.onnx` (Release-Assets von openWakeWord v0.5.1; Lizenzangaben siehe dortiges Repository) sowie `hey_claude.onnx` von [openwakeword.com/library/1656](https://openwakeword.com/library/1656) (nur private, nicht-kommerzielle Nutzung)
- Pipeline wie openWakeWord 0.6.0: 16 kHz Mono, 1280-Sample-Blöcke, Mel-Transformation x/10+2, Embedding-Fenster 76×32, Weckwort-Modell mit 16×96
- Gebaut per GitHub Actions (`.github/workflows/build.yml`) bei jedem Push auf `main`. Signiert mit dem festen Schlüssel `debug.keystore` aus dem Repo.

Hinweis: Weil der Schlüssel öffentlich ist, könnte theoretisch jemand eine eigene APK mit gleichem Paketnamen signieren. Installiere deshalb nur APKs von dieser Releases-Seite.
