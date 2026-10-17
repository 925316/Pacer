# Pacer

An open-source motion simulation tool for Android: it makes a device report a
believable, physically consistent movement track, for research and lawful
testing of location-based apps.

Pacer is built from scratch. It is not a fork or a copy of any existing project;
where a technique was learned from a GPL-3.0 reference implementation, the
attribution is recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

> **Legal notice.** For study, research and lawful testing on devices you own
> only. Do not use it for fake attendance, exam cheating, or any use that breaks
> a platform's rules or the law. You are responsible for your own behaviour.

## What it does

**Motion engine (pure Kotlin, no Android dependency, unit tested)**

- Humanised speed curve: a start ramp, noisy cruise with fatigue, decelerations
  and random pauses, instead of a constant-speed straight line.
- Physical consistency: steps, cadence and stride are derived from the same
  speed curve that drives the coordinates, and stride grows with speed, so a run
  does not imply an impossible cadence.
- Signal realism: accuracy warm-up, satellite count, occasional dropouts,
  altitude drift, and micro-movement when standing still.
- Track self-audit: the same heuristics a server would use to spot a fake upload
  (speed plausibility, displacement autocorrelation, accuracy variance,
  step-to-distance ratio, speed-to-cadence correlation, duplicate tracks, zone
  and checkpoint checks) so a track can be checked before it is used.

**LSPosed module**

- Location injection at the `LocationManager` boundary, through Google Play
  fused location, and directly inside the AMap, Baidu and Tencent location SDKs.
  Coordinate systems are handled per SDK and resolved at runtime (AMap and
  Tencent use GCJ-02; Baidu's is read from `BDLocation.getCoorType()`, because
  its default is not BD-09 in current releases).
- Sensor simulation: step counter, step detector and accelerometer, kept in
  agreement with the injected coordinates.
- GNSS status and NMEA sentence spoofing; raw GNSS registrations are refused.
- Anti-detection: mock-location flags, AppOps, `Settings.Secure`, permission
  self-checks, system features, test providers, and a system_server layer that
  also answers AppOps and settings globally.
- A deterministic virtual radio environment derived from the simulated
  coordinate: the WiFi scan list and every `WifiInfo` accessor, plus the
  `ConnectivityManager` view of the same radio, so two APIs describing one radio
  cannot disagree. The cell and BLE views come from the same model (cells on the
  device's own operator, beacons re-jittered at a fixed cadence), and the phone
  and Bluetooth processes carry that answer for apps that are not themselves in
  the module scope.

**Control app**

- Straight-line or route simulation, speed presets or a custom speed.
- Route building: type coordinates, paste a map link, search a place, or tap
  waypoints on an OpenStreetMap view; reorder and delete waypoints.
- Floating joystick for live speed and heading, and a local engine preview that
  needs no hooks at all.

## Requirements

- Android 8.0 (API 26) or newer.
- LSPosed (or a compatible framework exposing the libxposed API 101+).
- Root for the in-process hooks. The app itself installs and runs without root;
  only the hook side needs the framework.

## Install and verify

1. **Get the APK.** Download the `pacer-debug-apks` artifact from the latest run
   of the `ci` workflow (Actions tab), or build one from a `v*` tag release. The
   APK is debug-signed and the module app and the hook live in the same package.
2. **Install** it and open it once so the config channel is initialised
   (`init()` binds to the framework service).
3. **Enable the module** in LSPosed and set its scope to the target app. Add
   **System Framework** as well if you want the global system_server layer, and
   **Settings Storage** if you want the settings-provider hook. The module also
   asks for the **phone** and **Bluetooth** processes in its own scope
   declaration; keep them enabled if an app you did not scope should still be
   answered.
4. **Restart the target app** (force-stop it first). Hooks are installed when its
   process loads, so an app that was already running is not covered.
5. **Start a simulation** from the Pacer app: choose a speed, add waypoints (map,
   search or paste a link), then *Start humanized route simulation*.
6. **Verify** what actually attached:

   ```
   adb logcat | grep -E "pacer hooks|pacer config"
   ```

   You should see one line per process, for example:

   ```
   pacer hooks: total=41 | installed[anti-detection=2, env-connectivity=2, env-wifi=9, ...] | failed[none]
   pacer config: active=true speed=3.0 at=(39.9042,116.4074) route=true steps=true
   ```

### Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| `pacer hooks: total=0` in the target process | The target app is not in the module scope, or it was not restarted after enabling. |
| No `pacer hooks` line at all | The module was not loaded into that process: check scope, and that the module is enabled. |
| `failed[...]` names a hooker | That installer threw; the name identifies it. Other hookers still work. |
| `pacer config: active=false` while a simulation is running | The config channel did not deliver. The hook re-reads the shared preferences every few seconds as a fallback, so check the module app saw the Xposed service bind. |
| `config channel not ready yet` in the app | The app could not bind to the framework service; the module must be enabled in LSPosed. |
| Coordinates do not change in the target app | The app may read data Pacer does not fabricate (cell towers, Bluetooth beacons) or use an uncovered SDK. |

## Architecture

| Module | Contents |
| --- | --- |
| `core-motion` | The pure Kotlin engine and its unit tests. No Android dependency. |
| `xposed` | The LSPosed module: hookers, the config reader and the install report. Ships as a library that the app packages. |
| `app` | The control UI and the writer of the shared configuration. |

The module app and the hooks communicate through the framework's shared
preferences: the app writes a group named `pacer` and every hooked process reads
it, so no server or backend is involved anywhere in the project.

## Build

All builds for this project are performed exclusively within a continuous
integration (CI) environment. No local Android SDK installation is required, and
local builds are not officially supported. This approach ensures that every build
is executed in a consistent, reproducible and isolated environment, so all
artifacts are produced under identical conditions.

The build process is fully automated through GitHub Actions. Two workflows are
defined:

- **Continuous Integration** (`.github/workflows/ci.yml`): triggered on every push
  to `master`. It compiles the source, runs the unit tests, assembles the debug
  APK, and then asserts that the APK really contains the libxposed module
  declaration and the hook entry class. That last check matters: an app APK can
  build cleanly while shipping no hooks at all if the hook library is not a
  dependency of the app.
- **Release** (`.github/workflows/release.yml`): triggered by a tag matching
  `v*`. It runs the same tests, builds the APK, performs the same module-content
  assertion, and attaches the result to a GitHub release.

The CI and release workflows are the only supported way to build this project.
Local builds may behave differently and are not supported.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
