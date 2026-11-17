# JARVIS Drive (DRIVORA)

Car dashboard for Android head units and phones: speedometer HUD, trip and odometer,
vehicle profiles, music player with a saved library, Google map with navigation hand-off,
and a command assistant. Fits wide, 16:9, 4:3 and vertical screens.

## Data sources

| Source | What it gives | Needs |
| --- | --- | --- |
| GPS | speed, trip, odometer, heading, altitude, route trail | nothing extra |
| OBD2 (ELM327, Bluetooth or Wi-Fi) | speed from the car, RPM, coolant temperature, fuel level, engine load, battery voltage, check-engine | an ELM327 adapter |
| Demo drive | simulated values for trying the screen | nothing |

Speed uses OBD when an adapter is connected, otherwise GPS.

Not available from standard OBD2: headlight / indicator state, gear position, the car's own odometer.
Those need codes specific to each car model.

## Layout

- `src/index.src.html` - the whole app (ES5 JavaScript, no dependencies)
- `www/index.html` - built copy with the font embedded; this is what ships
- `native/ObdLinkPlugin.java` - Bluetooth / Wi-Fi serial link to the adapter
- `native/MediaLibPlugin.java` - lists the music stored on the device
- `native/PlayerPlugin.java` - plays the music outside the WebView, without taking audio focus, so the
  speech recognizer cannot pause it; pauses for phone calls; steering-wheel / headset buttons
- `native/VoicePlugin.java` - speech recognition and spoken replies for the built-in assistant
- `native/WakeDetector.java` - the "Hey Jarvis" wake word: a Java port of the openWakeWord streaming pipeline
- `native/WakeEngine.java` - keeps the microphone open, runs the wake word model (ONNX Runtime), hands the
  audio after "Hey Jarvis" to the speech recognizer
- `native/HandsFreeService.java` - foreground service so hands-free keeps working while Google Maps is in front
- `native/models/` - openWakeWord models (`melspectrogram`, `embedding_model`, `hey_jarvis_v0.1`)
- `native/MainActivity.java` - registers the plugins, keeps the screen on
- `native/brand/logo-source.png` - app logo; `python3 tools/make-icons.py` turns it into `native/res/` icons and splash screens
- `tools/android-patch.js` - adds permissions, native code and the signing key to the generated Android project
- `.github/workflows/build-apk.yml` - builds the APK on every push to `main`

## Build

Push to `main` (or run the workflow by hand). The APK is in the run's Artifacts as `DRIVORA-apk`.

After editing `src/index.src.html`: `node tools/build.js`, then commit `www/index.html`.

Tests (need Chrome): `cd tools && npm install && npm test`. Screenshots at head-unit sizes: `node tools/shot.js`.

## Built-in assistant

A small helper for simple driving tasks, called Jarvis. With hands-free on (Settings), say
"Hey Jarvis" and then the command, without touching the screen. Or tap the mic, or type.
It understands English, plus basic Sinhala and Singlish phrases.

**How hands-free works.** An on-device wake word model (openWakeWord "hey_jarvis") listens to the
microphone all the time. It needs no speech service and no internet while waiting, so there are no
restart sounds and no data use, and song lyrics do not wake it. When it hears "Hey Jarvis" a short tone
plays, the music turns down, and the speech recognizer takes the command. On Android 13+ the recognizer
gets the same audio, including what was said right after "Jarvis", so "Hey Jarvis play music" in one
breath works; on older Android, wait for the tone. A notification ("Listening for Hey Jarvis", with a
Stop button) keeps it listening while Google Maps is in front. Settings: how easily it wakes
(Less / Normal / More) and noise cancellation (the phone's noise suppressor, echo canceller and gain control).

**Commands are understood by meaning, not exact words.** Fillers ("can you", "please", "for me") are
dropped, key words are found anywhere in the sentence, "and" / "then" joins two commands, and when the
recognizer's first guess makes no sense its other guesses are tried. If nothing fits, Jarvis says
what it heard.

- "go to Matara", "take me to Galle", "I want to go to Ella", "how do I get to Kandy", "change destination to Jaffna", "matara yamu", "mahanuwarata yamu" - locks the destination and shows the route
- "start" / "let's go" - begins guidance in Google Maps; "start navigation to Matara" does both; "cancel the route" clears it
- "open google map", "show me the map", "where am I"
- "play music", "put on some songs", "pause", "next song", "change the song", "previous", "play <song name>", "volume up", "volume 50", "shuffle on"
- "what is my speed", "how fast am I going", "what time is it", "how far", "fuel", "engine temperature", "battery", "when is the next service"
- "open the map and go to Matara" - two commands at once
- "show trail", "limit 70", "theme red", "reset trip", "hud", "stop listening"

Voice language and spoken replies are in Settings. The meaning is worked out in `parseCmd()` and carried out in `execCmd()` in `src/index.src.html`.

## Hook for a fuller AI agent

`window.DRIVORA.command("go to Kandy")` runs any assistant command.
`window.DRIVORA.live` holds current speed, RPM, temperatures and light states.

Wake word: openWakeWord by David Scripka (code Apache-2.0); the pre-trained models are CC BY-NC-SA 4.0,
so this app is for personal, non-commercial use.

Font: Barlow Condensed, SIL Open Font License (`tools/fonts/OFL-LICENSE.txt`).
