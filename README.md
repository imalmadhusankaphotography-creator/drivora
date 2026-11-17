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
"Hey Jarvis, play music" without touching the screen; "Hey Jarvis" alone makes it wait for the
next sentence. Or tap the mic, or type.
It understands English, plus basic Sinhala and Singlish phrases.

Settings has **Noise cancellation for voice** (Android 13+): the app records the microphone itself,
runs the phone's noise suppressor, echo canceller and gain control plus a low-rumble filter over it,
and gives the cleaned sound to the speech recognizer. If the phone's speech service cannot take it,
the option switches itself off and listening carries on as before. The assistant panel also shows
the last sentence the microphone picked up, so you can see what it heard.

- "go to Kandy" / "lock destination to Galle" / "mahanuwarata yamu" - locks the destination and shows the route
- "start" - begins guidance in Google Maps; "cancel navigation" clears it
- "play music", "pause", "next", "previous", "play <song name>", "shuffle on"
- "what is my speed", "what time is it", "how far", "fuel", "engine temperature", "battery"
- "show map", "show trail", "limit 70", "theme red", "reset trip", "hud"

Voice language and spoken replies are in Settings. All logic is in `runCommand()` in `src/index.src.html`.

## Hook for a fuller AI agent

`window.DRIVORA.command("go to Kandy")` runs any assistant command.
`window.DRIVORA.live` holds current speed, RPM, temperatures and light states.

Font: Barlow Condensed, SIL Open Font License (`tools/fonts/OFL-LICENSE.txt`).
