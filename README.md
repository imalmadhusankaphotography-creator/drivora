# DRIVORA

Car dashboard for Android head units and phones: speedometer HUD, trip and odometer,
vehicle profiles, music player, Google Maps hand-off, and a command assistant.

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
- `native/MainActivity.java` - registers the plugin, keeps the screen on
- `tools/android-patch.js` - adds permissions, native code and the signing key to the generated Android project
- `.github/workflows/build-apk.yml` - builds the APK on every push to `main`

## Build

Push to `main` (or run the workflow by hand). The APK is in the run's Artifacts as `DRIVORA-apk`.

After editing `src/index.src.html`: `node tools/build.js`, then commit `www/index.html`.

Tests (need Chrome): `cd tools && npm install && npm test`.

## Hook for the AI agent

`window.DRIVORA.command("go to Kandy")` runs any assistant command.
`window.DRIVORA.live` holds current speed, RPM, temperatures and light states.

Font: Barlow Condensed, SIL Open Font License (`tools/fonts/OFL-LICENSE.txt`).
